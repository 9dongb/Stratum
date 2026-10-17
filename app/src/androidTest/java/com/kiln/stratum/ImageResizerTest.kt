package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 엔진의 이미지 크기 변경(GPU)을 에뮬레이터에서 확인한다. GPU 반올림 때문에 색은 ±2까지 같다고 본다. */
@RunWith(AndroidJUnit4::class)
class ImageResizerTest {

    /** [color]로 칠한 사진을 PNG로 만들어 앱과 같은 방법으로 불러온다. */
    private fun photo(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Document {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    private fun Document.raster(index: Int = 0): Raster = (layers[index].content as RasterContent).raster

    private fun Document.pixel(x: Int, y: Int, layer: Int = 0): Int =
        raster(layer).tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private fun near(expected: Int, actual: Int, tolerance: Int = 2): Boolean =
        abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun hex(color: Int) = Integer.toHexString(color)

    @Test
    fun sameSize_changesNothing() {
        val document = photo(300, 200) { _, _ -> Color.RED }

        assertSame(document, ResizeImage(300, 200).applyTo(document))
    }

    @Test
    fun solidCanvas_staysOneTile() {
        val canvas = checkNotNull(BlankCanvas.create(1001, 521, CanvasFill.Solid(Color.WHITE), "Background"))
        val white = canvas.raster().tiles.values.first()

        val resized = ResizeImage(500, 260).applyTo(canvas)

        assertEquals(PixelSize(500, 260), PixelSize(resized.width, resized.height))
        assertEquals(TileGrid(500, 260).keys().toSet(), resized.raster().tiles.keys)
        assertTrue(resized.raster().tiles.values.all { it === white })
    }

    @Test
    fun halfSize_matchesPreviewLevelOne() {
        // 자리마다 다른 색. 반으로 줄이면 정확히 2×2 평균이라 축소본 1단계와 같아야 한다.
        val document = photo(600, 400) { x, y -> Color.rgb((x * 7 + y * 3) and 0xFF, (x * 13) and 0xFF, (y * 11) and 0xFF) }

        val resized = ResizeImage(300, 200).applyTo(document)
        val preview = checkNotNull(PreviewRenderer.render(document, 1)).copy(Bitmap.Config.ARGB_8888, false)

        assertEquals(300, preview.width)
        for (y in 0 until 200) {
            for (x in 0 until 300) {
                if (!near(preview.getPixel(x, y), resized.pixel(x, y))) {
                    fail("($x, $y): ${hex(resized.pixel(x, y))} vs preview ${hex(preview.getPixel(x, y))}")
                }
            }
        }
    }

    @Test
    fun gradient_hasNoSeamsAtBlockAndTileEdges() {
        // 가로로 밝아지는 회색. 0.7배로 줄이면 대상 1024px 묶음과 256px 타일 경계를 여러 번 지난다.
        val document = photo(3000, 300) { x, _ -> (x * 255 / 2999).let { Color.rgb(it, it, it) } }

        val resized = ResizeImage(2100, 210).applyTo(document)

        for (y in listOf(0, 105, 209)) {
            for (x in 1 until 2100) {
                val step = Color.red(resized.pixel(x, y)) - Color.red(resized.pixel(x - 1, y))
                // 한 픽셀에 원본 약 1.4픽셀만큼(밝기 약 0.12) 밝아지므로, 이음매가 있으면 크게 튄다
                if (step < -1 || step > 2) fail("seam at ($x, $y): step $step")
            }
        }
    }

    @Test
    fun upscale_keepsEdgesAndColors() {
        // 한 가지 색이지만 사진으로 불러와 한 가지 색 표시가 없는 이미지. 캔버스 끝이 어두워지면 안 된다.
        val document = photo(100, 80) { _, _ -> Color.rgb(200, 30, 60) }

        val resized = ResizeImage(250, 200).applyTo(document)

        for (y in 0 until 200) {
            for (x in 0 until 250) {
                if (!near(Color.rgb(200, 30, 60), resized.pixel(x, y))) fail("($x, $y): ${hex(resized.pixel(x, y))}")
            }
        }
    }

    @Test
    fun hiddenLayer_isResizedToo() {
        val base = photo(400, 200) { _, _ -> Color.BLUE }
        val top = photo(400, 200) { x, _ -> if (x < 200) Color.RED else Color.GREEN }
        // 위 레이어를 보이지 않게(불투명도 0) 한 두 레이어 문서
        val withLayer = AddLayer("Layer 1", 1).applyTo(base)
        val document = SetLayerOpacity(LayerId(1), 0f).applyTo(
            withLayer.copy(layers = listOf(withLayer.layers[0], withLayer.layers[1].copy(content = top.layers[0].content)))
        )

        val resized = ResizeImage(200, 100).applyTo(document)

        assertEquals(0f, resized.layers[1].opacity)
        assertTrue(near(Color.RED, resized.pixel(50, 50, layer = 1)))
        assertTrue(near(Color.GREEN, resized.pixel(150, 50, layer = 1)))
        assertTrue(near(Color.BLUE, resized.pixel(100, 50, layer = 0)))
    }

    @Test
    fun differentAxes_halveOnlyTheShrunkAxis() {
        // 한 픽셀 폭 검정·흰 세로줄
        val stripes = photo(1024, 512) { x, _ -> if (x % 2 == 0) Color.BLACK else Color.WHITE }

        // 가로만 반으로 줄이면 줄이 섞여 회색이 된다
        val narrow = ResizeImage(512, 512).applyTo(stripes)
        for ((x, y) in listOf(0 to 0, 100 to 300, 511 to 511)) {
            val gray = Color.red(narrow.pixel(x, y))
            assertTrue("($x, $y) is $gray", gray in 125..130)
        }
        // 세로만 반으로 줄이면 줄은 그대로다
        val short = ResizeImage(1024, 256).applyTo(stripes)
        for ((x, y) in listOf(0 to 0, 101 to 100, 1023 to 255)) {
            val expected = if (x % 2 == 0) Color.BLACK else Color.WHITE
            assertTrue("($x, $y): ${hex(short.pixel(x, y))}", near(expected, short.pixel(x, y)))
        }
    }

    @Test
    fun cancel_stopsTheCommand() {
        val document = photo(3000, 300) { x, _ -> Color.rgb(x and 0xFF, 0, 0) }

        assertThrows(CancellationException::class.java) {
            ResizeImage(1500, 150).applyTo(document) { throw CancellationException() }
        }
    }
}
