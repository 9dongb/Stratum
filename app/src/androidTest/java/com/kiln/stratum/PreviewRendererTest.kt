package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileKey
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 엔진의 화면용 축소본 그리기(GPU)를 에뮬레이터에서 확인한다.
 * 테스트는 메인 스레드가 아닌 스레드에서 돌므로, 백그라운드에서 GPU로 그릴 수 있는지도 함께 확인된다.
 */
@RunWith(AndroidJUnit4::class)
class PreviewRendererTest {

    /** 타일마다 빨강·파랑이 번갈아 칠해진 사진. 이음매가 생기면 두 색이 섞인 픽셀이 나온다. */
    private fun checkerDocument(width: Int, height: Int): Document {
        val pixels = IntArray(width * height) {
            val column = (it % width) / TILE_SIZE
            val row = (it / width) / TILE_SIZE
            if ((column + row) % 2 == 0) Color.RED else Color.BLUE
        }
        val out = ByteArrayOutputStream()
        check(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out))
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(out.toByteArray())), "Background")
        return (result as ImportResult.Success).document
    }

    /** GPU 비트맵을 픽셀을 읽을 수 있는 복사본으로. */
    private fun Bitmap.readable(): Bitmap = checkNotNull(copy(Bitmap.Config.ARGB_8888, false))

    /** 축소본의 모든 픽셀이 빨강이나 파랑 그대로인지(섞이지 않았는지), 그리고 타일 칸에 맞는 색인지 확인한다. */
    private fun assertCheckerWithoutSeams(preview: Bitmap, tilePixels: Int) {
        for (y in 0 until preview.height) {
            for (x in 0 until preview.width) {
                val expected = if ((x / tilePixels + y / tilePixels) % 2 == 0) Color.RED else Color.BLUE
                val actual = preview.getPixel(x, y)
                if (actual != expected) {
                    fail("($x, $y): expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}")
                }
            }
        }
    }

    @Test
    fun render_hasSizeOfLevelOnGpu() {
        val document = checkerDocument(1000, 600)

        val preview = checkNotNull(PreviewRenderer.render(document, level = 1))

        assertEquals(500, preview.width)
        assertEquals(300, preview.height)
        assertEquals(Bitmap.Config.HARDWARE, preview.config)
    }

    @Test
    fun render_halfSizeHasNoSeamsAcrossTilesAndBlocks() {
        // 2600px는 타일 11장이라 8×8 묶음 경계(2048px)도 지난다
        val document = checkerDocument(2600, 520)

        val preview = checkNotNull(PreviewRenderer.render(document, level = 1)).readable()

        assertEquals(1300, preview.width)
        assertEquals(260, preview.height)
        assertCheckerWithoutSeams(preview, tilePixels = TILE_SIZE / 2)
    }

    @Test
    fun render_quarterSizeHalvesAgainWithoutSeams() {
        val document = checkerDocument(2600, 520)

        val preview = checkNotNull(PreviewRenderer.render(document, level = 2)).readable()

        assertEquals(650, preview.width)
        assertEquals(130, preview.height)
        assertCheckerWithoutSeams(preview, tilePixels = TILE_SIZE / 4)
    }

    @Test
    fun render_oddSizeKeepsLastRowAndColumn() {
        // 1001×521은 끝 타일이 233×9px라, 반씩 줄이면 끝 픽셀이 반만 덮인다. 그래도 끝 줄·끝 칸까지 칠해져야 한다.
        // 에뮬레이터의 GPU(소프트웨어)는 홀수 크기에서 일부 타일 경계 한 줄에 1/255쯤 이웃 색이 섞인다.
        // 예전 그리기 방식에서도 같은 자리에 생기고 눈에 보이지 않는 차이라, 불투명한지와 색이 거의 같은지만 본다.
        val document = checkerDocument(1001, 521)

        for (level in 1..3) {
            val preview = checkNotNull(PreviewRenderer.render(document, level)).readable()
            val tilePixels = TILE_SIZE shr level
            for (y in 0 until preview.height) {
                for (x in 0 until preview.width) {
                    val expected = if ((x / tilePixels + y / tilePixels) % 2 == 0) Color.RED else Color.BLUE
                    val actual = preview.getPixel(x, y)
                    val close = Color.alpha(actual) == 255 &&
                        abs(Color.red(actual) - Color.red(expected)) <= 2 &&
                        abs(Color.green(actual) - Color.green(expected)) <= 2 &&
                        abs(Color.blue(actual) - Color.blue(expected)) <= 2
                    if (!close) {
                        fail("level $level ($x, $y): expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}")
                    }
                }
            }
        }
    }

    @Test
    fun renderRegion_matchesSameAreaOfWholeRender() {
        // 2048px 묶음 경계를 넘는 영역. 에뮬레이터 GPU는 전체 그림 쪽에서도 1/255쯤 반올림 차이를 내서(0단계 포함)
        // ±2까지 같다고 본다. 자리가 어긋나면 빨강·파랑이 바뀌어 255만큼 달라지므로 이것으로 충분하다.
        val size = PixelSize(2600, 520)
        val document = checkerDocument(size.width, size.height)

        for (level in 0..2) {
            val region = checkNotNull(PreviewLevels.region(size, level, PixelRect(1900, 100, 2300, 400)))
            val whole = checkNotNull(PreviewRenderer.render(document, level)).readable()
            val part = checkNotNull(PreviewRenderer.renderRegion(document, region)).readable()

            assertEquals(region.previewWidth, part.width)
            assertEquals(region.previewHeight, part.height)
            val tolerance = 2
            for (y in 0 until part.height) {
                for (x in 0 until part.width) {
                    val expected = whole.getPixel(region.previewLeft + x, region.previewTop + y)
                    val actual = part.getPixel(x, y)
                    val close = abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance &&
                        abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
                        abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
                        abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
                    if (!close) {
                        fail("level $level ($x, $y): expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}")
                    }
                }
            }
        }
    }

    @Test
    fun renderRegion_isOnGpuAndKeepsOddEdgeOpaque() {
        // 1001×521 문서의 오른쪽 아래 구석. 끝 타일이 233×9px라 반으로 줄이면 끝 픽셀이 반만 덮인다.
        val document = checkerDocument(1001, 521)
        val region = checkNotNull(PreviewLevels.region(PixelSize(1001, 521), 1, PixelRect(800, 400, 1001, 521)))

        val preview = checkNotNull(PreviewRenderer.renderRegion(document, region))

        assertEquals(Bitmap.Config.HARDWARE, preview.config)
        val readable = preview.readable()
        for (x in 0 until readable.width) assertEquals(255, Color.alpha(readable.getPixel(x, readable.height - 1)))
        for (y in 0 until readable.height) assertEquals(255, Color.alpha(readable.getPixel(readable.width - 1, y)))
    }

    @Test
    fun render_appliesLayerOpacity() {
        val document = checkerDocument(256, 256)
        val halfTransparent = SetLayerOpacity(document.layers.single().id, 0.5f).applyTo(document)

        val pixel = checkNotNull(PreviewRenderer.render(halfTransparent, level = 0)).readable().getPixel(10, 10)

        assertTrue("alpha was ${Color.alpha(pixel)}", abs(Color.alpha(pixel) - 128) <= 1)
        assertEquals(255, Color.red(pixel))
    }

    @Test
    fun render_drawsTilesKeptInJavaHeap() {
        // 힙에 담긴 타일(테스트·작은 데이터)도 그릴 수 있다
        val tile = Tile.of(IntArray(TILE_PIXEL_COUNT) { Color.GREEN })
        val document = Document.create(
            width = 100,
            height = 100,
            firstLayerName = "Background",
            firstLayerRaster = Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to tile))
        )

        val preview = checkNotNull(PreviewRenderer.render(document, level = 0)).readable()

        assertEquals(100, preview.width)
        assertEquals(Color.GREEN, preview.getPixel(50, 50))
    }
}
