package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.document.BlendMath
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 혼합 모드(곱하기·스크린·오버레이)를 GPU로 그린 결과가 기준 계산([BlendMath])과 같은지 에뮬레이터에서 확인한다.
 * 축소본, 레이어별 축소본, 내보내기가 모두 같은 계산을 쓴다. GPU 반올림 때문에 색은 ±2까지 같다고 본다.
 */
@RunWith(AndroidJUnit4::class)
class BlendModeTest {

    private val size = 64

    /** [color]로 칠한 사진을 PNG로 만들어 앱과 같은 방법으로 불러온 래스터 */
    private fun raster(color: (x: Int, y: Int) -> Int): Raster {
        val pixels = IntArray(size * size) { color(it % size, it / size) }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Layer")
        val document = (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
        return (document.layers[0].content as RasterContent).raster
    }

    /** 아래: 불투명한 색 그러데이션 */
    private fun bottomColor(x: Int, y: Int) = Color.rgb(x * 4, y * 4, 255 - x * 2)

    /** 위: 다른 방향의 그러데이션. 왼쪽 위 사분면은 반투명, 오른쪽 아래 사분면은 투명이다. */
    private fun topColor(x: Int, y: Int): Int =
        when {
            x >= size / 2 && y >= size / 2 -> Color.TRANSPARENT
            x < size / 2 && y < size / 2 -> Color.argb(128, 255 - y * 4, 128, x * 4)
            else -> Color.rgb(255 - y * 4, 128, x * 4)
        }

    private fun document(mode: BlendMode, opacity: Float, bottom: Raster = raster(::bottomColor)): Document =
        Document(
            width = size,
            height = size,
            layers = listOf(
                Layer(LayerId(0), "Bottom", RasterContent(bottom)),
                Layer(LayerId(1), "Top", RasterContent(raster(::topColor)), opacity = opacity, blendMode = mode)
            ),
            nextLayerId = 2
        )

    /** 알파를 미리 곱한 0..1 색으로 */
    private fun premultiplied(color: Int): FloatArray {
        val alpha = Color.alpha(color) / 255f
        return floatArrayOf(Color.red(color) / 255f * alpha, Color.green(color) / 255f * alpha, Color.blue(color) / 255f * alpha, alpha)
    }

    /** 기준 계산: 아래 색에 위 색을 불투명도·혼합 모드로 얹은 색(알파를 곱하지 않은 ARGB) */
    private fun reference(mode: BlendMode, opacity: Float, x: Int, y: Int, bottom: Int = bottomColor(x, y)): Int {
        val result = BlendMath.blend(mode, BlendMath.withOpacity(premultiplied(topColor(x, y)), opacity), premultiplied(bottom))
        val alpha = result[3]
        if (alpha == 0f) return Color.TRANSPARENT
        return Color.argb(
            (alpha * 255).roundToInt(),
            (result[0] / alpha * 255).roundToInt(),
            (result[1] / alpha * 255).roundToInt(),
            (result[2] / alpha * 255).roundToInt()
        )
    }

    private fun near(expected: Int, actual: Int, tolerance: Int = 2): Boolean =
        abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun hex(color: Int) = Integer.toHexString(color)

    private fun software(bitmap: Bitmap?): Bitmap = checkNotNull(bitmap).copy(Bitmap.Config.ARGB_8888, false)

    private fun assertMatchesReference(what: String, mode: BlendMode, opacity: Float, rendered: Bitmap) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val expected = reference(mode, opacity, x, y)
                val actual = rendered.getPixel(x, y)
                if (!near(expected, actual)) fail("$what $mode $opacity ($x, $y): ${hex(actual)} vs ${hex(expected)}")
            }
        }
    }

    @Test
    fun preview_matchesTheReference_forEveryMode() {
        for (mode in BlendMode.entries) {
            for (opacity in listOf(1f, 0.5f)) {
                assertMatchesReference("preview", mode, opacity, software(PreviewRenderer.render(document(mode, opacity), 0)))
            }
        }
    }

    @Test
    fun layerCache_matchesTheReference_forEveryMode() {
        val cache = LayerPreviewCache()
        for (mode in BlendMode.entries) {
            for (opacity in listOf(1f, 0.5f)) {
                assertMatchesReference("cache", mode, opacity, software(cache.render(document(mode, opacity), 0)))
            }
        }
    }

    @Test
    fun export_matchesTheReference() {
        for (mode in listOf(BlendMode.MULTIPLY, BlendMode.OVERLAY)) {
            val png = ImageExporter.render(document(mode, 0.7f), ExportSettings(ExportFormat.PNG, 90, PixelSize(size, size))).use { image ->
                ByteArrayOutputStream().also { image.writeTo(it) }.toByteArray()
            }
            assertMatchesReference("export", mode, 0.7f, checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)))
        }
    }

    @Test
    fun jpeg_mattesWhiteAfterBlending() {
        // 아래가 비어 있으면 스크린 레이어는 그 레이어 그대로 보인다. 흰 바탕은 섞은 뒤에 깔아야 화면과 같다
        // (흰 바탕 위에서 섞으면 스크린은 하얗게 된다).
        val document = document(BlendMode.SCREEN, 1f, bottom = Raster.EMPTY)
        val jpeg = ImageExporter.render(document, ExportSettings(ExportFormat.JPEG, 100, PixelSize(size, size))).use { image ->
            ByteArrayOutputStream().also { image.writeTo(it) }.toByteArray()
        }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))

        // 불투명한 위 레이어 자리(오른쪽 위 사분면)는 그 색 그대로다(JPEG 손실만큼 여유를 둔다)
        val x = size * 3 / 4
        val y = size / 4
        assertTrue(hex(decoded.getPixel(x, y)), near(topColor(x, y), decoded.getPixel(x, y), tolerance = 8))
    }
}
