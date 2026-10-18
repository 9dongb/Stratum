package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.command.SetLayerVisible
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.export.ExportFormat.JPEG
import com.kiln.stratum.engine.export.ExportFormat.PNG
import com.kiln.stratum.engine.export.ExportFormat.WEBP
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.export.ExportSize
import com.kiln.stratum.engine.export.ExportSizes
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.raster.PixelSize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 엔진의 내보내기(그리기·압축)를 에뮬레이터에서 확인한다. GPU로 합성한 색은 반올림 때문에 ±2까지 같다고 본다. */
@RunWith(AndroidJUnit4::class)
class ImageExporterTest {

    /** [color](알파를 미리 곱하지 않은 색)로 칠한 비트맵 */
    private fun bitmapOf(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** [bitmap]을 PNG로 만들어 앱과 같은 방법으로 불러온다. */
    private fun photo(bitmap: Bitmap): Document {
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    private fun photo(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Document = photo(bitmapOf(width, height, color))

    /** [document]를 [settings]대로 내보낸 파일 내용 */
    private fun export(document: Document, settings: ExportSettings): ByteArray =
        ImageExporter.render(document, settings).use { image ->
            ByteArrayOutputStream().also { image.writeTo(it) }.toByteArray()
        }

    /** 내보낸 파일을 다시 읽은 비트맵(알파를 미리 곱해 담는다) */
    private fun decode(bytes: ByteArray): Bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    /** 알파를 미리 곱한 원래 값. getPixel은 반투명 픽셀에서 반올림되므로 정확히 비교할 때 쓴다. */
    private fun rawBytes(bitmap: Bitmap): ByteArray =
        ByteBuffer.allocate(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(it) }.array()

    private fun near(expected: Int, actual: Int, tolerance: Int = 2): Boolean =
        abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun hex(color: Int) = Integer.toHexString(color)

    /** JPEG에 사진 정보(APP1 'Exif') 묶음이 있는지 */
    private fun hasExif(jpeg: ByteArray): Boolean {
        val exif = "Exif".toByteArray()
        return (0 until jpeg.size - 8).any { i ->
            jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xE1.toByte() && (0 until 4).all { jpeg[i + 4 + it] == exif[it] }
        }
    }

    @Test
    fun png_oneLayerAtOriginalSize_isExact() {
        // 일곱 자리마다 반투명. 601×301은 끝 타일이 캔버스 밖으로 걸친다.
        val source = bitmapOf(601, 301) { x, y ->
            val alpha = if ((x + y) % 7 == 0) 77 else 255
            Color.argb(alpha, (x * 5) and 0xFF, (y * 3) and 0xFF, (x + y) and 0xFF)
        }

        val decoded = decode(export(photo(source), ExportSettings(PNG, 90, PixelSize(601, 301))))

        assertEquals(PixelSize(601, 301), PixelSize(decoded.width, decoded.height))
        // 알파를 미리 곱한 값까지 원본과 같다
        assertArrayEquals(rawBytes(source), rawBytes(decoded))
    }

    @Test
    fun twoLayers_matchThePreviewAtFullSize() {
        val base = photo(600, 400) { x, y -> Color.rgb((x * 7 + y * 3) and 0xFF, (x * 13) and 0xFF, (y * 11) and 0xFF) }
        val top = photo(600, 400) { x, _ -> if (x < 300) Color.RED else Color.argb(128, 0, 0, 255) }
        // 위 레이어를 반만 보이게(불투명도 0.5) 한 두 레이어 문서
        val withLayer = AddLayer("Layer 1", 1).applyTo(base)
        val document = SetLayerOpacity(LayerId(1), 0.5f).applyTo(
            withLayer.copy(layers = listOf(withLayer.layers[0], withLayer.layers[1].copy(content = top.layers[0].content)))
        )

        val decoded = decode(export(document, ExportSettings(PNG, 90, PixelSize(600, 400))))
        val preview = checkNotNull(PreviewRenderer.render(document, 0)).copy(Bitmap.Config.ARGB_8888, false)

        for (y in 0 until 400 step 3) {
            for (x in 0 until 600 step 3) {
                if (!near(preview.getPixel(x, y), decoded.getPixel(x, y))) {
                    fail("($x, $y): ${hex(decoded.getPixel(x, y))} vs preview ${hex(preview.getPixel(x, y))}")
                }
            }
        }
    }

    @Test
    fun hiddenLayers_areLeftOut() {
        val source = bitmapOf(300, 200) { x, y -> Color.rgb((x * 7) and 0xFF, (y * 5) and 0xFF, 90) }
        val base = photo(source)
        val top = photo(300, 200) { _, _ -> Color.RED }
        val withLayer = AddLayer("Layer 1", 1).applyTo(base)
        val shown = withLayer.copy(layers = listOf(withLayer.layers[0], withLayer.layers[1].copy(content = top.layers[0].content)))
        val hidden = SetLayerVisible(LayerId(1), false).applyTo(shown)

        assertEquals(Color.RED, decode(export(shown, ExportSettings(PNG, 90, PixelSize(300, 200)))).getPixel(10, 10))
        // 보이는 레이어 하나만 남으면 원본 크기 PNG는 그 레이어를 그대로 베낀다(숨긴 레이어는 빠진다)
        assertArrayEquals(rawBytes(source), rawBytes(decode(export(hidden, ExportSettings(PNG, 90, PixelSize(300, 200))))))
        // 줄여 그려도 숨긴 레이어는 빠진다
        val smaller = decode(export(hidden, ExportSettings(JPEG, 95, PixelSize(150, 100))))
        assertTrue(hex(smaller.getPixel(5, 5)), Color.red(smaller.getPixel(5, 5)) < 200)
    }

    @Test
    fun jpeg_putsTransparencyOnWhite_andHasNoExif() {
        // 왼쪽은 불투명한 빨강, 가운데 띠는 반투명 빨강, 오른쪽은 투명
        val document = photo(400, 200) { x, _ ->
            when {
                x < 180 -> Color.RED
                x < 220 -> Color.argb(128, 255, 0, 0)
                else -> Color.TRANSPARENT
            }
        }

        val bytes = export(document, ExportSettings(JPEG, 95, PixelSize(400, 200)))
        val decoded = decode(bytes)

        assertTrue(hex(decoded.getPixel(60, 100)), near(Color.RED, decoded.getPixel(60, 100), tolerance = 8))
        assertTrue(hex(decoded.getPixel(340, 100)), near(Color.WHITE, decoded.getPixel(340, 100), tolerance = 8))
        // 반투명 빨강은 흰 바탕 위라 분홍이다(그냥 압축하면 검은 바탕 위의 어두운 빨강이 된다)
        assertTrue(hex(decoded.getPixel(200, 100)), near(Color.rgb(255, 127, 127), decoded.getPixel(200, 100), tolerance = 12))
        assertFalse(hasExif(bytes))
    }

    @Test
    fun transparentCanvas_isWhiteInJpeg_andTransparentInPng() {
        val document = checkNotNull(BlankCanvas.create(700, 500, CanvasFill.Transparent, "Background"))

        val jpeg = decode(export(document, ExportSettings(JPEG, 90, PixelSize(700, 500))))
        val png = decode(export(document, ExportSettings(PNG, 90, PixelSize(350, 250))))

        for ((x, y) in listOf(0 to 0, 350 to 250, 699 to 499)) {
            assertTrue(hex(jpeg.getPixel(x, y)), near(Color.WHITE, jpeg.getPixel(x, y), tolerance = 1))
        }
        assertEquals(PixelSize(350, 250), PixelSize(png.width, png.height))
        val pixels = IntArray(350 * 250).also { png.getPixels(it, 0, 350, 0, 0, 350, 250) }
        assertTrue(pixels.all { Color.alpha(it) == 0 })
    }

    @Test
    fun longEdgePreset_resizesWithoutSeams() {
        // 가로로 밝아지는 회색. 긴 변 2048로 줄이면 대상 1024px 묶음과 256px 타일 경계를 지난다.
        val document = photo(3000, 300) { x, _ -> (x * 255 / 2999).let { Color.rgb(it, it, it) } }
        val size = ExportSizes.pixelsOf(PixelSize(3000, 300), ExportSize.LongEdge(2048))

        val decoded = decode(export(document, ExportSettings(PNG, 90, size)))

        assertEquals(PixelSize(2048, 205), PixelSize(decoded.width, decoded.height))
        for (y in listOf(0, 102, 204)) {
            for (x in 1 until 2048) {
                val step = Color.red(decoded.getPixel(x, y)) - Color.red(decoded.getPixel(x - 1, y))
                // 한 픽셀에 원본 약 1.5픽셀만큼(밝기 약 0.12) 밝아지므로, 이음매가 있으면 크게 튄다
                if (step < -1 || step > 2) fail("seam at ($x, $y): step $step")
            }
        }
        assertTrue(near(Color.BLACK, decoded.getPixel(0, 100), tolerance = 3))
        assertTrue(near(Color.WHITE, decoded.getPixel(2047, 100), tolerance = 3))
    }

    @Test
    fun webp_isReadable() {
        val document = photo(500, 300) { x, _ -> if (x < 250) Color.rgb(30, 120, 200) else Color.rgb(240, 200, 40) }

        val decoded = decode(export(document, ExportSettings(WEBP, 90, PixelSize(500, 300))))

        assertEquals(PixelSize(500, 300), PixelSize(decoded.width, decoded.height))
        assertTrue(hex(decoded.getPixel(100, 150)), near(Color.rgb(30, 120, 200), decoded.getPixel(100, 150), tolerance = 8))
        assertTrue(hex(decoded.getPixel(400, 150)), near(Color.rgb(240, 200, 40), decoded.getPixel(400, 150), tolerance = 8))
    }

    @Test
    fun cancel_stopsRenderingAndWriting() {
        // 잘 압축되지 않는 무늬라 PNG가 여러 조각(4KB씩)으로 쓰인다
        val document = photo(1000, 300) { x, y -> Color.rgb((x * 31 + y * 17) and 0xFF, (x * x + y) and 0xFF, (x xor (y * 7)) and 0xFF) }

        assertThrows(CancellationException::class.java) {
            ImageExporter.render(document, ExportSettings(JPEG, 90, PixelSize(500, 150))) { throw CancellationException() }
        }
        ImageExporter.render(document, ExportSettings(PNG, 90, PixelSize(1000, 300))).use { image ->
            var writes = 0
            // 쓰는 도중 취소하면 실패(IOException)가 아니라 취소로 끝난다
            assertThrows(CancellationException::class.java) {
                image.writeTo(ByteArrayOutputStream()) { if (++writes > 1) throw CancellationException() }
            }
        }
    }
}
