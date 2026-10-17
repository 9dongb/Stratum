package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.importing.ImportFailure
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 엔진의 사진 불러오기(안드로이드에서만 도는 부분)를 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class PhotoImporterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 좌표마다 다른 색. 빨강·초록에 좌표를 넣고, 256을 넘는 자리는 파랑에 넣는다. */
    private fun coordinateColor(x: Int, y: Int): Int =
        Color.argb(255, x and 0xFF, y and 0xFF, ((x shr 8) * 40 + (y shr 8) * 120) and 0xFF)

    private fun bitmapOf(width: Int, height: Int, color: (Int, Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun Bitmap.encode(format: Bitmap.CompressFormat): ByteArray {
        val out = ByteArrayOutputStream()
        check(compress(format, 100, out)) { "Could not encode test image" }
        return out.toByteArray()
    }

    private fun sourceOf(bytes: ByteArray) = ImageDecoder.createSource(ByteBuffer.wrap(bytes))

    private fun importDocument(bytes: ByteArray, sampleSize: Int = 1): Document {
        val result = PhotoImporter.import(sourceOf(bytes), "Background", sampleSize)
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    private val Document.raster: Raster
        get() = (layers.single().content as RasterContent).raster

    private fun Document.pixel(x: Int, y: Int): Int =
        raster.tiles.getValue(TileKey(x / TILE_SIZE, y / TILE_SIZE)).pixelAt(x % TILE_SIZE, y % TILE_SIZE)

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int = 8) {
        val near = Color.alpha(expected) == Color.alpha(actual) &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        assertTrue("Expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}", near)
    }

    @Test
    fun png_putsEveryPixelInTheRightTile() {
        val width = 300
        val height = 200
        val bytes = bitmapOf(width, height, ::coordinateColor).encode(Bitmap.CompressFormat.PNG)

        val info = PhotoImporter.readInfo(sourceOf(bytes))
        val document = importDocument(bytes)

        assertEquals(PixelSize(width, height), info?.size)
        assertEquals("image/png", info?.mimeType)
        assertEquals(width, document.width)
        assertEquals(height, document.height)
        assertEquals("Background", document.layers.single().name)
        // 300×200은 가로 2장, 세로 1장. 타일 경계(x = 255, 256)를 포함해 모든 픽셀이 제자리에 있어야 한다
        assertEquals(setOf(TileKey(0, 0), TileKey(1, 0)), document.raster.tiles.keys)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val expected = coordinateColor(x, y)
                val actual = document.pixel(x, y)
                if (expected != actual) {
                    fail("($x, $y): expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}")
                }
            }
        }
        // 끝 타일에서 캔버스 밖은 투명으로 읽힌다
        assertEquals(0, document.raster.tiles.getValue(TileKey(1, 0)).pixelAt(100, 0))
    }

    @Test
    fun png_keepsTransparency() {
        val bytes = bitmapOf(10, 10) { x, _ ->
            when {
                x < 3 -> Color.RED
                x < 6 -> Color.TRANSPARENT
                else -> Color.argb(128, 0, 0, 255)
            }
        }.encode(Bitmap.CompressFormat.PNG)

        val document = importDocument(bytes)

        assertEquals(Color.RED, document.pixel(0, 0))
        assertEquals(0, Color.alpha(document.pixel(4, 0)))
        // 알파를 미리 곱해 저장하므로 반투명 픽셀은 색에 반올림 차이가 날 수 있다
        assertColorNear(Color.argb(128, 0, 0, 255), document.pixel(8, 0), tolerance = 2)
    }

    @Test
    fun jpeg_appliesExifRotation() {
        // 왼쪽 위 빨강, 오른쪽 위 초록, 왼쪽 아래 파랑, 오른쪽 아래 흰색
        val bitmap = bitmapOf(300, 200) { x, y ->
            when {
                y < 100 && x < 150 -> Color.RED
                y < 100 -> Color.GREEN
                x < 150 -> Color.BLUE
                else -> Color.WHITE
            }
        }
        val file = File(context.cacheDir, "rotated.jpg")
        file.writeBytes(bitmap.encode(Bitmap.CompressFormat.JPEG))
        // 시계 방향으로 90° 돌려 보여 주라는 표시(방향 6)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val info = PhotoImporter.readInfo(ImageDecoder.createSource(file))
        val result = PhotoImporter.import(ImageDecoder.createSource(file), "Background")
        val document = (result as ImportResult.Success).document

        assertEquals(60_000L, info?.size?.pixelCount)
        assertEquals(200, document.width)
        assertEquals(300, document.height)
        // 돌린 뒤: 왼쪽 위 파랑, 오른쪽 위 빨강, 왼쪽 아래 흰색, 오른쪽 아래 초록(JPEG라 색이 조금 다를 수 있다)
        assertColorNear(Color.BLUE, document.pixel(50, 75))
        assertColorNear(Color.RED, document.pixel(150, 75))
        assertColorNear(Color.WHITE, document.pixel(50, 225))
        assertColorNear(Color.GREEN, document.pixel(150, 225))
        file.delete()
    }

    @Test
    fun sampleSize_halvesWhileDecoding() {
        val bytes = bitmapOf(600, 400, ::coordinateColor).encode(Bitmap.CompressFormat.PNG)

        val document = importDocument(bytes, sampleSize = 2)

        assertEquals(300, document.width)
        assertEquals(200, document.height)
    }

    @Test
    fun highBitDepthPng_isStoredAsEightBitSrgb() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.RGBA_F16).apply {
            eraseColor(Color.rgb(200, 100, 50))
        }

        val document = importDocument(bitmap.encode(Bitmap.CompressFormat.PNG))

        assertColorNear(Color.rgb(200, 100, 50), document.pixel(10, 10), tolerance = 3)
    }

    @Test
    fun brokenFile_isUnreadable() {
        val bytes = "not a photo".toByteArray()

        assertNull(PhotoImporter.readInfo(sourceOf(bytes)))
        assertEquals(
            ImportResult.Failure(ImportFailure.UNREADABLE),
            PhotoImporter.import(sourceOf(bytes), "Background")
        )
    }

    @Test
    fun cancel_stopsBetweenTiles() {
        // 600×400은 타일 3×2 = 6장
        val bytes = bitmapOf(600, 400, ::coordinateColor).encode(Bitmap.CompressFormat.PNG)
        var checks = 0

        assertThrows(CancellationException::class.java) {
            PhotoImporter.import(sourceOf(bytes), "Background") {
                checks++
                if (checks == 3) throw CancellationException()
            }
        }
        assertEquals(3, checks)
    }
}
