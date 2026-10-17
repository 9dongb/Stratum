package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.AdjustmentMath
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.SetLayerAdjustments
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.export.ExportFormat
import com.kiln.stratum.engine.export.ExportSettings
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.ImageExporter
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.raster.PixelSize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 기본 보정의 GPU 셰이더와 레이어별 축소본을 에뮬레이터에서 확인한다. GPU 반올림 때문에 색은 ±2까지 같다고 본다. */
@RunWith(AndroidJUnit4::class)
class AdjustShaderTest {

    /** 모든 보정을 조금씩 건 값 */
    private val all = Adjustments(
        exposure = 0.4f,
        temperature = 0.3f,
        shadows = 0.5f,
        highlights = -0.4f,
        brightness = 0.2f,
        contrast = 0.3f,
        saturation = -0.2f,
        curve = ToneCurve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.6f), CurvePoint(1f, 0.95f)))
    )

    /** [color]로 칠한 사진을 PNG로 만들어 앱과 같은 방법으로 불러온다. */
    private fun photo(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Document {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Background")
        return (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
    }

    /** 부드럽게 바뀌는 색(이웃 픽셀 차이가 작다) */
    private fun gradient(): Document = photo(256, 256) { x, y -> Color.rgb(x, y, (x + y) / 2) }

    private fun near(expected: Int, actual: Int, tolerance: Int = 2): Boolean =
        abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun hex(color: Int) = Integer.toHexString(color)

    /** [adjustments]를 기준 계산으로 [color]에 건 색 */
    private fun reference(adjustments: Adjustments, color: Int): Int {
        val table = adjustments.curve.takeUnless { it.isIdentity }?.table()
        val c = AdjustmentMath.apply(adjustments, Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, table)
        return Color.argb(Color.alpha(color), (c[0] * 255).roundToInt(), (c[1] * 255).roundToInt(), (c[2] * 255).roundToInt())
    }

    private fun software(bitmap: Bitmap?): Bitmap = checkNotNull(bitmap).copy(Bitmap.Config.ARGB_8888, false)

    @Test
    fun gpu_matchesTheReference() {
        val document = gradient()
        val adjusted = SetLayerAdjustments(LayerId(0), all).applyTo(document)

        val rendered = software(PreviewRenderer.render(adjusted, 0))

        for (y in 0 until 256 step 5) {
            for (x in 0 until 256 step 5) {
                val expected = reference(all, Color.rgb(x, y, (x + y) / 2))
                if (!near(expected, rendered.getPixel(x, y))) fail("($x, $y): ${hex(rendered.getPixel(x, y))} vs ${hex(expected)}")
            }
        }
    }

    @Test
    fun none_drawsTheSamePixels() {
        val document = gradient()
        val plain = software(PreviewRenderer.render(document, 0))
        val none = software(PreviewRenderer.render(SetLayerAdjustments(LayerId(0), Adjustments.NONE).applyTo(document), 0))

        for (y in 0 until 256 step 7) for (x in 0 until 256 step 7) assertEquals(plain.getPixel(x, y), none.getPixel(x, y))
    }

    @Test
    fun semiTransparent_keepsAlpha() {
        val document = photo(64, 64) { _, _ -> Color.argb(128, 200, 40, 40) }
        val gray = SetLayerAdjustments(LayerId(0), Adjustments(saturation = -1f)).applyTo(document)

        val pixel = software(PreviewRenderer.render(gray, 0)).getPixel(30, 30)

        assertTrue(hex(pixel), abs(Color.alpha(pixel) - 128) <= 1)
        assertTrue(hex(pixel), abs(Color.red(pixel) - Color.green(pixel)) <= 2 && abs(Color.green(pixel) - Color.blue(pixel)) <= 2)
    }

    @Test
    fun export_appliesTheAdjustments() {
        val adjusted = SetLayerAdjustments(LayerId(0), all).applyTo(gradient())

        val png = ImageExporter.render(adjusted, ExportSettings(ExportFormat.PNG, 90, PixelSize(256, 256))).use { image ->
            ByteArrayOutputStream().also { image.writeTo(it) }.toByteArray()
        }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))

        for ((x, y) in listOf(10 to 10, 128 to 64, 250 to 200)) {
            val expected = reference(all, Color.rgb(x, y, (x + y) / 2))
            assertTrue("($x, $y)", near(expected, decoded.getPixel(x, y)))
        }
    }

    @Test
    fun layerCache_matchesTileRendering_andRedrawsOnlyChangedLayers() {
        val base = SetLayerAdjustments(LayerId(0), all).applyTo(gradient())
        val top = photo(256, 256) { x, _ -> if (x < 128) Color.argb(200, 30, 200, 90) else Color.TRANSPARENT }
        val withLayer = AddLayer("Layer 1", 1).applyTo(base)
        val document = SetLayerOpacity(LayerId(1), 0.6f).applyTo(
            withLayer.copy(layers = listOf(withLayer.layers[0], withLayer.layers[1].copy(content = top.layers[0].content)))
        )
        val cache = LayerPreviewCache()

        val viaCache = software(cache.render(document, 1))
        val direct = software(PreviewRenderer.render(document, 1))

        assertEquals(PixelSize(direct.width, direct.height), PixelSize(viaCache.width, viaCache.height))
        for (y in 0 until direct.height step 4) {
            for (x in 0 until direct.width step 4) {
                // 레이어마다 줄인 뒤 합치므로 가장자리가 아니면 거의 같다
                if (x in 62..66) continue
                if (!near(direct.getPixel(x, y), viaCache.getPixel(x, y), tolerance = 3)) {
                    fail("($x, $y): ${hex(viaCache.getPixel(x, y))} vs direct ${hex(direct.getPixel(x, y))}")
                }
            }
        }
        assertEquals(2, cache.layerRenders)

        // 보정·불투명도만 바꾸면 레이어 축소본을 다시 그리지 않는다
        val adjusted = SetLayerAdjustments(LayerId(0), Adjustments(contrast = 0.5f)).applyTo(document)
        checkNotNull(cache.render(adjusted, 1))
        checkNotNull(cache.render(SetLayerOpacity(LayerId(1), 0.2f).applyTo(adjusted), 1))
        assertEquals(2, cache.layerRenders)

        // 픽셀이 바뀐 레이어만 다시 그린다
        val other = photo(256, 256) { _, y -> Color.rgb(0, 0, y) }
        val replaced = adjusted.copy(layers = listOf(adjusted.layers[0], adjusted.layers[1].copy(content = other.layers[0].content)))
        checkNotNull(cache.render(replaced, 1))
        assertEquals(3, cache.layerRenders)
    }
}
