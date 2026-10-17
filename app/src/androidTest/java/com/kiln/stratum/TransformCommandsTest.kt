package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.platform.FlipImage
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.transform.Orientation
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

/**
 * 엔진의 회전·뒤집기·자르기(안드로이드 복사)를 에뮬레이터에서 확인한다.
 * 픽셀 값은 바뀌지 않고 자리만 옮겨져야 한다. 반투명 픽셀도 섞어, 알파를 미리 곱한 값이 반올림 없이 옮겨지는지 본다.
 */
@RunWith(AndroidJUnit4::class)
class TransformCommandsTest {

    /** 끝 타일이 잘리는 홀수 크기 */
    private val size = PixelSize(601, 301)

    /** 좌표마다 다른 색. 일곱 자리마다 하나는 반투명이다. */
    private fun colorAt(x: Int, y: Int): Int {
        val alpha = if ((x + y) % 7 == 0) 128 else 255
        return Color.argb(alpha, x and 0xFF, y and 0xFF, ((x shr 8) * 40 + (y shr 8) * 120) and 0xFF)
    }

    /** 사진을 PNG로 만들어 앱과 같은 방법으로 불러온다. 끝 타일은 캔버스 안쪽 크기의 비트맵이다. */
    private fun photo(): Document {
        val pixels = IntArray(size.width * size.height) { colorAt(it % size.width, it / size.width) }
        val bitmap = Bitmap.createBitmap(pixels, size.width, size.height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    private val Document.raster: Raster
        get() = (layers.single().content as RasterContent).raster

    private fun Document.pixel(x: Int, y: Int): Int =
        raster.tiles.getValue(TileKey(x / TILE_SIZE, y / TILE_SIZE)).pixelAt(x % TILE_SIZE, y % TILE_SIZE)

    /** [actual]의 모든 픽셀이 [expected]의 [from] 자리 픽셀과 같은지 본다. */
    private fun assertMoved(expected: Document, actual: Document, from: (x: Int, y: Int) -> Pair<Int, Int>) {
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val (sx, sy) = from(x, y)
                if (actual.pixel(x, y) != expected.pixel(sx, sy)) {
                    fail("($x, $y) is ${Integer.toHexString(actual.pixel(x, y))}, expected ${Integer.toHexString(expected.pixel(sx, sy))} from ($sx, $sy)")
                }
            }
        }
    }

    /** 타일마다 메모리 크기(비트맵 크기)가 같은지. 끝 타일은 캔버스 안쪽 크기다. */
    private fun assertSameTileSizes(expected: Document, actual: Document) {
        assertEquals(expected.raster.tiles.keys, actual.raster.tiles.keys)
        for ((key, tile) in expected.raster.tiles) {
            assertEquals("$key", tile.byteCount, actual.raster.tiles.getValue(key).byteCount)
        }
    }

    @Test
    fun rotateClockwise_movesEveryPixel() {
        val original = photo()

        val rotated = RotateImage(clockwise = true).applyTo(original)

        assertEquals(PixelSize(301, 601), PixelSize(rotated.width, rotated.height))
        assertMoved(original, rotated) { x, y ->
            val from = Orientation.ROTATE_CLOCKWISE.inverse.mapPixel(x, y, rotated.width, rotated.height)
            from.x to from.y
        }
    }

    @Test
    fun flips_mirrorEveryPixel() {
        val original = photo()

        assertMoved(original, FlipImage(horizontal = true).applyTo(original)) { x, y -> size.width - 1 - x to y }
        assertMoved(original, FlipImage(horizontal = false).applyTo(original)) { x, y -> x to size.height - 1 - y }
    }

    @Test
    fun fourTurnsAndTwoFlips_keepEveryPixel() {
        val original = photo()
        var turned = original
        repeat(4) { turned = RotateImage(clockwise = true).applyTo(turned) }
        val flippedTwice = FlipImage(horizontal = true).applyTo(FlipImage(horizontal = true).applyTo(original))
        val flippedTwiceVertically = FlipImage(horizontal = false).applyTo(FlipImage(horizontal = false).applyTo(original))

        for (result in listOf(turned, flippedTwice, flippedTwiceVertically)) {
            assertSameTileSizes(original, result)
            assertMoved(original, result) { x, y -> x to y }
        }
    }

    @Test
    fun crop_keepsTheRectangle_andTrimsEdgeTiles() {
        val original = photo()
        val rect = PixelRect(100, 50, 400, 250)

        val cropped = CropImage(rect, size).applyTo(original)

        assertEquals(PixelSize(300, 200), PixelSize(cropped.width, cropped.height))
        assertMoved(original, cropped) { x, y -> x + rect.left to y + rect.top }
        // 오른쪽 끝 타일은 캔버스 안쪽(44×200)만 담는다
        assertEquals(44 * 200 * 4, cropped.raster.tiles.getValue(TileKey(1, 0)).byteCount)
    }

    @Test
    fun crop_planned_forAnotherSize_changesNothing() {
        val original = photo()

        assertSame(original, CropImage(PixelRect(0, 0, 100, 100), PixelSize(600, 300)).applyTo(original))
        assertSame(original, CropImage(PixelRect.of(size), size).applyTo(original))
    }

    @Test
    fun blankCanvas_staysOneTile() {
        val canvas = checkNotNull(BlankCanvas.create(1001, 521, CanvasFill.Solid(Color.WHITE), "Background"))
        val white = canvas.raster.tiles.values.first()

        for (result in listOf(
            RotateImage(clockwise = false).applyTo(canvas),
            FlipImage(horizontal = true).applyTo(canvas),
            CropImage(PixelRect(3, 7, 900, 500), PixelSize(1001, 521)).applyTo(canvas)
        )) {
            assertEquals(TileGrid(result.width, result.height).keys().toSet(), result.raster.tiles.keys)
            assertTrue(result.raster.tiles.values.all { it === white })
        }
    }

    @Test
    fun cancel_stopsTheCommand() {
        val original = photo()

        assertThrows(CancellationException::class.java) {
            RotateImage(clockwise = true).applyTo(original) { throw CancellationException() }
        }
    }

    @Test
    fun rotatedDocument_drawsAsPreview() {
        val rotated = RotateImage(clockwise = true).applyTo(photo())

        val preview = checkNotNull(PreviewRenderer.render(rotated, 0)).copy(Bitmap.Config.ARGB_8888, false)

        assertEquals(301, preview.width)
        assertEquals(601, preview.height)
        // 불투명한 픽셀 몇 곳이 문서와 같은지(GPU가 그려 ±2)
        for ((x, y) in listOf(0 to 0, 300 to 0, 0 to 600, 300 to 600, 150 to 333)) {
            val expected = rotated.pixel(x, y)
            if (Color.alpha(expected) != 255) continue
            val actual = preview.getPixel(x, y)
            val near = abs(Color.red(expected) - Color.red(actual)) <= 2 &&
                abs(Color.green(expected) - Color.green(actual)) <= 2 &&
                abs(Color.blue(expected) - Color.blue(actual)) <= 2
            assertTrue("($x, $y): ${Integer.toHexString(actual)} vs ${Integer.toHexString(expected)}", near)
        }
    }
}
