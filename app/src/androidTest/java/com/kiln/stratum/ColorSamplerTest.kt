package com.kiln.stratum

import android.graphics.Color
import android.graphics.ColorSpace
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.DocumentColorSpace
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.ColorSampler
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 스포이드가 문서 한 점에 보이는 색을 고르는지(결정 기록 '스포이드') 에뮬레이터 GPU로 확인한다. */
@RunWith(AndroidJUnit4::class)
class ColorSamplerTest {

    private val size = 300

    /** 한 가지 색으로 채운 레이어 */
    private fun solid(id: Long, color: Int, opacity: Float = 1f, mode: BlendMode = BlendMode.NORMAL, adjustments: Adjustments = Adjustments.NONE) =
        Layer(LayerId(id), "L$id", RasterContent(Raster.filled(TileGrid(size, size), Tile.of(IntArray(TILE_PIXEL_COUNT) { color }))), opacity, mode, adjustments)

    /** 왼쪽 위 타일(256px)만 [color]이고 나머지는 투명한 레이어 */
    private fun corner(id: Long, color: Int) =
        Layer(LayerId(id), "L$id", RasterContent(Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.of(IntArray(TILE_PIXEL_COUNT) { color })))))

    private fun document(vararg layers: Layer) = Document(size, size, layers.toList(), nextLayerId = layers.size.toLong())

    private fun near(expected: Int, actual: Int?, tolerance: Int = 2): Boolean =
        actual != null && Color.alpha(actual) == 255 &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun hex(color: Int?) = color?.let { Integer.toHexString(it) } ?: "null"

    @Test
    fun picksTheTopVisibleColor() {
        val doc = document(solid(0, Color.BLUE), corner(1, Color.RED))

        assertEquals(Color.RED, ColorSampler.sample(doc, 10f, 10f))
        // 위 레이어가 없는 곳은 아래 레이어 색
        assertEquals(Color.BLUE, ColorSampler.sample(doc, TILE_SIZE + 10f, 10f))
    }

    @Test
    fun picksTheExactPixel_insideAndAcrossTiles() {
        // 타일 안 자리마다 다른 색: (x, y) → rgb(x, y, 타일 번호 × 100)
        fun pattern(index: Int) = Tile.of(IntArray(TILE_PIXEL_COUNT) { Color.rgb(it % TILE_SIZE, it / TILE_SIZE, index * 100) })
        val raster = Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to pattern(0), TileKey(1, 0) to pattern(1), TileKey(0, 1) to pattern(2)))
        val doc = document(Layer(LayerId(0), "L0", RasterContent(raster)))

        assertEquals(Color.rgb(37, 201, 0), ColorSampler.sample(doc, 37.6f, 201.2f))
        assertEquals(Color.rgb(5, 3, 100), ColorSampler.sample(doc, TILE_SIZE + 5f, 3f))
        assertEquals(Color.rgb(40, 20, 200), ColorSampler.sample(doc, 40f, TILE_SIZE + 20f))
    }

    @Test
    fun picksTheBlendedLook_withOpacityModesAndAdjustments() {
        // 흰 바탕 위 50% 검정 → 회색(128)
        val half = ColorSampler.sample(document(solid(0, Color.WHITE), solid(1, Color.BLACK, opacity = 0.5f)), 50f, 50f)
        assertTrue(hex(half), near(Color.rgb(128, 128, 128), half))
        // 곱하기: 빨강 × 회색(128) → 어두운 빨강
        val multiplied = ColorSampler.sample(document(solid(0, Color.rgb(128, 128, 128)), solid(1, Color.RED, mode = BlendMode.MULTIPLY)), 50f, 50f)
        assertTrue(hex(multiplied), near(Color.rgb(128, 0, 0), multiplied))
        // 보정(채도 -100%)을 건 레이어는 회색으로 보인다
        val gray = ColorSampler.sample(document(solid(0, Color.RED, adjustments = Adjustments(saturation = -1f))), 50f, 50f)
        assertTrue(hex(gray), gray != null && Color.red(gray) == Color.green(gray) && Color.green(gray) == Color.blue(gray))
    }

    @Test
    fun hiddenLayers_areLeftOut() {
        val doc = document(solid(0, Color.BLUE), corner(1, Color.RED).copy(visible = false))

        assertEquals(Color.BLUE, ColorSampler.sample(doc, 10f, 10f))
    }

    @Test
    fun transparentPlacesAndOutside_pickNothing() {
        val doc = document(corner(0, Color.RED))

        assertNull(ColorSampler.sample(doc, TILE_SIZE + 10f, 10f))
        assertNull(ColorSampler.sample(doc, -1f, 10f))
        assertNull(ColorSampler.sample(doc, size.toFloat(), 10f))
    }

    @Test
    fun halfTransparentColor_isPickedWithoutItsAlpha() {
        val doc = document(corner(0, Color.argb(128, 255, 0, 0)))

        assertTrue(hex(ColorSampler.sample(doc, 10f, 10f)), near(Color.RED, ColorSampler.sample(doc, 10f, 10f)))
    }

    @Test
    fun p3Document_picksTheSameLookingSrgbColor() {
        // sRGB 주황과 같아 보이는 P3 값으로 칠한 P3 문서
        val orange = Color.rgb(255, 128, 0)
        val p3 = ColorSpace.connect(ColorSpace.get(ColorSpace.Named.SRGB), ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
            .transform(1f, 128 / 255f, 0f)
        val inP3 = Color.rgb((p3[0] * 255).toInt().coerceIn(0, 255), (p3[1] * 255).toInt().coerceIn(0, 255), (p3[2] * 255).toInt().coerceIn(0, 255))
        val doc = document(solid(0, inP3)).copy(colorSpace = DocumentColorSpace.DISPLAY_P3)

        val picked = ColorSampler.sample(doc, 50f, 50f)

        assertTrue("${hex(picked)} vs ${hex(orange)}", near(orange, picked, tolerance = 3))
    }
}
