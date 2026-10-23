package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.CompositePlan
import com.kiln.stratum.engine.document.CompositeStep
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.importing.ImportResult
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.PhotoImporter
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.TranslateLayer
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.skin.OkLab
import com.kiln.stratum.engine.skin.SkinColor
import com.kiln.stratum.engine.skin.SkinColorMath
import com.kiln.stratum.engine.skin.SkinFaceArea
import com.kiln.stratum.engine.skin.SkinFaceTone
import com.kiln.stratum.engine.skin.SkinTone
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 피부 색 보정 셰이더(결정 기록 '피부 보정'의 3단계)를 에뮬레이터에서 확인한다: 순수 Kotlin 계산([SkinColorMath])과 같은 색,
 * 밝기는 그대로, 얼굴마다 다른 기준, 레이어 축소본(다른 단계)과 옮기는 동안의 얼굴 자리, 슬라이더가 0이면 그리지 않음.
 */
@RunWith(AndroidJUnit4::class)
class SkinColorTest {

    private val width = 600
    private val height = 300

    private fun degrees(value: Double): Float = Math.toRadians(value).toFloat()

    /** 색상 [hueDegrees]·채도 [chroma]·밝기 [lightness]인 sRGB 색 */
    private fun skin(hueDegrees: Double, chroma: Float, lightness: Float = 0.72f): Int {
        val h = degrees(hueDegrees)
        val rgb = FloatArray(3)
        OkLab.toRgb(lightness, chroma * cos(h), chroma * sin(h), rgb)
        return Color.rgb((rgb[0] * 255).roundToInt(), (rgb[1] * 255).roundToInt(), (rgb[2] * 255).roundToInt())
    }

    private fun raster(color: (x: Int, y: Int) -> Int): Raster {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        val result = PhotoImporter.import(ImageDecoder.createSource(ByteBuffer.wrap(png)), "Layer")
        val document = (result as? ImportResult.Success)?.document ?: throw AssertionError("Import failed: $result")
        return (document.layers[0].content as RasterContent).raster
    }

    /** 피부(색상 50°) 바탕에 붉은 점(색상 32°, 진함)이 왼쪽 (150, 150)·오른쪽 (450, 150)에 하나씩 */
    private val photo: Raster by lazy {
        raster { x, y ->
            val spot = (x - 150) * (x - 150) + (y - 150) * (y - 150) < 30 * 30 || (x - 450) * (x - 450) + (y - 150) * (y - 150) < 30 * 30
            if (spot) skin(32.0, 0.085f) else skin(50.0, 0.055f)
        }
    }

    private val tone = SkinTone(0.72f, degrees(50.0), 0.055f, 0.055f, 0f, 0.04f, 0.006f)
    private val leftFace = SkinFaceTone(SkinFaceArea(150f, 150f, 120f, 80f), tone)

    private fun document(color: SkinColor) = Document(
        width, height,
        listOf(
            Layer(LayerId(0), "Photo", RasterContent(photo)),
            Layer(LayerId(1), "Skin color", AdjustmentContent(skin = color), mask = Selection.all(PixelSize(width, height)))
        ),
        nextLayerId = 2
    )

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(software.width * software.height).also { software.getPixels(it, 0, software.width, 0, 0, software.width, software.height) }
    }

    private fun look(document: Document): IntArray = pixelsOf(checkNotNull(PreviewRenderer.render(document, level = 0)))

    /** [color]에 [terms]를 순수 Kotlin으로 건 sRGB 색 */
    private fun expected(color: Int, terms: SkinColorMath.Terms, skin: SkinColor): Int {
        val lab = FloatArray(3)
        OkLab.fromRgb(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, lab)
        val ab = FloatArray(2)
        SkinColorMath.apply(lab[0], lab[1], lab[2], terms, skin.local, skin.global, skin.even, ab)
        val rgb = FloatArray(3)
        OkLab.toRgb(lab[0], ab[0], ab[1], rgb)
        return Color.rgb((rgb[0].coerceIn(0f, 1f) * 255).roundToInt(), (rgb[1].coerceIn(0f, 1f) * 255).roundToInt(), (rgb[2].coerceIn(0f, 1f) * 255).roundToInt())
    }

    private fun near(a: Int, b: Int, tolerance: Int = 3): Boolean =
        abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

    private fun hueOf(color: Int): Double {
        val lab = FloatArray(3)
        OkLab.fromRgb(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, lab)
        return Math.toDegrees(atan2(lab[2], lab[1]).toDouble())
    }

    private fun lightnessOf(color: Int): Float {
        val lab = FloatArray(3)
        OkLab.fromRgb(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, lab)
        return lab[0]
    }

    @Test
    fun theShader_matchesTheKotlinMath_andKeepsTheLightness() {
        val color = SkinColor(local = 1f, global = 0.5f, even = 0.6f, faces = listOf(leftFace))
        val pixels = look(document(color))
        val terms = SkinColorMath.terms(leftFace)

        for ((x, y) in listOf(150 to 150, 140 to 160, 300 to 50, 20 to 280)) {
            val before = if ((x - 150) * (x - 150) + (y - 150) * (y - 150) < 900) skin(32.0, 0.085f) else skin(50.0, 0.055f)
            val after = pixels[y * width + x]
            assertTrue("($x, $y): ${Integer.toHexString(after)} vs ${Integer.toHexString(expected(before, terms, color))}", near(after, expected(before, terms, color)))
            assertEquals("lightness at ($x, $y)", lightnessOf(before), lightnessOf(after), 0.01f)
        }
        // 붉은 점은 피부 쪽으로 돌아왔다
        assertTrue("hue ${hueOf(pixels[150 * width + 150])}", hueOf(pixels[150 * width + 150]) > 44.0)
    }

    @Test
    fun allZero_drawsNothing() {
        val zero = document(SkinColor(faces = listOf(leftFace)))

        assertTrue(CompositePlan.of(zero).none { it is CompositeStep.Adjust })
        val pixels = look(zero)
        assertEquals(skin(32.0, 0.085f), pixels[150 * width + 150])
    }

    @Test
    fun eachFace_usesItsOwnSkin_alsoInSmallerPreviewsAndWhileMoving() {
        // 오른쪽 얼굴은 원래 붉은 사람(색상 30°): 같은 붉은 점(32°)이 그 사람에게는 보통 피부라 그대로다
        val rightFace = SkinFaceTone(SkinFaceArea(450f, 150f, 120f, 80f), tone.copy(hue = degrees(30.0), chroma = 0.085f, chromaBase = 0.085f))
        val color = SkinColor(local = 1f, faces = listOf(leftFace, rightFace))
        val document = document(color)

        val pixels = look(document)
        assertTrue("left ${hueOf(pixels[150 * width + 150])}", hueOf(pixels[150 * width + 150]) > 44.0)
        assertEquals(skin(32.0, 0.085f), pixels[150 * width + 450])

        // 레이어 축소본(반 크기)도 문서 자리로 얼굴을 고른다
        val cache = LayerPreviewCache()
        val half = pixelsOf(checkNotNull(cache.render(document, level = 1)))
        assertTrue("half left ${hueOf(half[75 * (width / 2) + 75])}", hueOf(half[75 * (width / 2) + 75]) > 42.0)
        assertTrue("half right ${hueOf(half[75 * (width / 2) + 225])}", hueOf(half[75 * (width / 2) + 225]) < 36.0)

        // 색 보정 레이어를 왼쪽으로 300px 옮기면(이동 도구) 얼굴 자리도 함께 옮겨, 왼쪽 점은 이제 오른쪽 얼굴(30°)의 것이다
        val moved = TranslateLayer(LayerId(1), -300, 0).applyTo(document)
        val movedFaces = checkNotNull((moved.layers[1].content as AdjustmentContent).skin).faces
        assertEquals(150f, movedFaces[1].area.centerX, 0f)
        val shifted = pixelsOf(checkNotNull(PreviewRenderer.render(moved, level = 0)))
        assertEquals(skin(32.0, 0.085f), shifted[150 * width + 150])
    }

    @Test
    fun p3Documents_useTheP3Matrices() {
        val color = SkinColor(local = 1f, faces = listOf(leftFace))
        val p3 = document(color).copy(colorSpace = DocumentColorSpace.DISPLAY_P3)

        val pixels = look(p3)

        // 같은 값을 P3로 읽어 계산한 것과 같다
        val before = skin(32.0, 0.085f)
        val lab = FloatArray(3)
        OkLab.fromRgb(Color.red(before) / 255f, Color.green(before) / 255f, Color.blue(before) / 255f, lab, OkLab.Space.DISPLAY_P3)
        val ab = FloatArray(2)
        SkinColorMath.apply(lab[0], lab[1], lab[2], SkinColorMath.terms(leftFace), 1f, 0f, 0f, ab)
        val rgb = FloatArray(3)
        OkLab.toRgb(lab[0], ab[0], ab[1], rgb, OkLab.Space.DISPLAY_P3)
        val expected = Color.rgb((rgb[0].coerceIn(0f, 1f) * 255).roundToInt(), (rgb[1].coerceIn(0f, 1f) * 255).roundToInt(), (rgb[2].coerceIn(0f, 1f) * 255).roundToInt())
        assertTrue("${Integer.toHexString(pixels[150 * width + 150])} vs ${Integer.toHexString(expected)}", near(pixels[150 * width + 150], expected))
    }
}
