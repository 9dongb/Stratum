package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.CompositeKey
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.GroupContent
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.LayerTree
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.document.SkinRecord
import com.kiln.stratum.engine.raster.Raster
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 피부 정돈의 계산(결정 기록 '피부 보정'의 4단계): 얼룩은 매끈하게, 뚜렷한 경계는 지키고, 마스크 밖 색은 번지지 않음. 정돈이 낡았는지. */
class SkinSmoothMathTest {

    private val size = 64

    /** [lightness]·[a]·[b](픽셀마다)와 [mask]로 정돈한 결과의 L·a·b(원본 해상도에서 계수를 바로 구함, 고운 결 σ 1, 질감 유지 [texture]) */
    private fun smoothed(lightness: FloatArray, a: FloatArray, b: FloatArray, mask: FloatArray, radius: Float, texture: Float = 0f): Array<FloatArray> {
        val blurred = SkinSmoothMath.maskedBlur(arrayOf(lightness, a, b), mask, size, size, 1f)
        val coefficients = SkinSmoothMath.coefficients(blurred[0], blurred[1], blurred[2], mask, size, size, radius)
        val out = Array(3) { FloatArray(size * size) }
        val result = FloatArray(3)
        for (i in 0 until size * size) {
            SkinSmoothMath.smooth(
                floatArrayOf(lightness[i], a[i], b[i]), floatArrayOf(blurred[0][i], blurred[1][i], blurred[2][i]),
                floatArrayOf(coefficients[0][i], coefficients[1][i], coefficients[2][i]),
                floatArrayOf(coefficients[3][i], coefficients[4][i], coefficients[5][i]),
                texture, result
            )
            for (c in 0..2) out[c][i] = result[c]
        }
        return out
    }

    private fun std(values: FloatArray, indices: List<Int>): Float {
        val mean = indices.map { values[it] }.average().toFloat()
        return sqrt(indices.map { (values[it] - mean) * (values[it] - mean) }.average().toFloat())
    }

    @Test
    fun faintBlotches_areSmoothed_andTheMeanStays() {
        // 고른 피부(L 0.7)에 옅은 얼룩(±0.01, 8칸 주기)
        val lightness = FloatArray(size * size) { 0.7f + 0.01f * kotlin.math.sin(it % size * 0.8f) * kotlin.math.sin(it / size * 0.8f) }
        val zero = FloatArray(size * size)
        val mask = FloatArray(size * size) { 1f }

        val out = smoothed(lightness, zero, zero, mask, radius = 8f)

        val middle = (16 until 48).flatMap { y -> (16 until 48).map { x -> y * size + x } }
        assertTrue("std ${std(lightness, middle)} -> ${std(out[0], middle)}", std(out[0], middle) < std(lightness, middle) * 0.3f)
        assertEquals(middle.map { lightness[it] }.average(), middle.map { out[0][it] }.average(), 0.002)
    }

    @Test
    fun aStrongEdge_suchAsAShadow_isKept() {
        // 왼쪽 반 그림자(L 0.35), 오른쪽 반 밝음(0.75)
        val lightness = FloatArray(size * size) { if (it % size < size / 2) 0.35f else 0.75f }
        val zero = FloatArray(size * size)

        val out = smoothed(lightness, zero, zero, FloatArray(size * size) { 1f }, radius = 8f)

        // 경계에서 4칸 떨어진 곳은 거의 그대로다(번짐 없음)
        assertEquals(0.35f, out[0][32 * size + 27], 0.03f)
        assertEquals(0.75f, out[0][32 * size + 36], 0.03f)
    }

    @Test
    fun colorsOutsideTheMask_doNotBleedIntoTheSkin() {
        // 아래쪽은 마스크 밖의 진한 빨강(머리카락·옷), 위쪽은 피부
        val lightness = FloatArray(size * size) { 0.65f }
        val a = FloatArray(size * size) { if (it / size >= 40) 0.2f else 0.03f }
        val zero = FloatArray(size * size)
        val mask = FloatArray(size * size) { if (it / size >= 40) 0f else 1f }

        val out = smoothed(lightness, a, zero, mask, radius = 8f)

        // 마스크 끝에 붙은 피부도 피부 색 그대로다(바깥 색이 흐림·평균에 섞이지 않음)
        assertEquals(0.03f, out[1][39 * size + 32], 0.002f)
        assertEquals(0.03f, out[1][38 * size + 32], 0.002f)
    }

    @Test
    fun aDarkHairline_doesNotLeaveABrightRim() {
        // 아래쪽은 마스크 밖의 어두운 머리카락(L 0.25), 위쪽은 피부(L 0.75). 질감을 다 남겨도 경계 안쪽이 밝아지지 않는다
        val lightness = FloatArray(size * size) { if (it / size >= 40) 0.25f else 0.75f }
        val zero = FloatArray(size * size)
        val mask = FloatArray(size * size) { if (it / size >= 40) 0f else 1f }

        val out = smoothed(lightness, zero, zero, mask, radius = 8f, texture = 1f)

        for (row in 36..39) assertEquals("row $row", 0.75f, out[0][row * size + 32], 0.003f)
    }

    @Test
    fun maskedBlur_keepsTheOriginalFarOutsideTheMask() {
        val values = FloatArray(size * size) { if (it / size >= 40) 0.2f else 0.03f }
        val mask = FloatArray(size * size) { if (it / size >= 40) 0f else 1f }

        val blurred = SkinSmoothMath.maskedBlur(arrayOf(values), mask, size, size, 1f)[0]

        // 안쪽은 피부만의 평균, 바깥 먼 곳은 원래 값
        assertEquals(0.03f, blurred[39 * size + 5], 1e-5f)
        assertEquals(0.2f, blurred[50 * size + 5], 1e-5f)
        assertEquals(0f, SkinSmoothMath.blurShare(SkinSmoothMath.BLUR_WEIGHT_LOW), 0f)
        assertEquals(1f, SkinSmoothMath.blurShare(SkinSmoothMath.BLUR_WEIGHT_HIGH), 0f)
    }

    @Test
    fun texture_bringsBackTheFineGrain() {
        // 모공 같은 고운 결(한 칸마다 ±0.03)
        val lightness = FloatArray(size * size) { 0.7f + if ((it % size + it / size) % 2 == 0) 0.03f else -0.03f }
        val zero = FloatArray(size * size)
        val mask = FloatArray(size * size) { 1f }
        val middle = (16 until 48).flatMap { y -> (16 until 48).map { x -> y * size + x } }

        val none = std(smoothed(lightness, zero, zero, mask, 8f, texture = 0f)[0], middle)
        val half = std(smoothed(lightness, zero, zero, mask, 8f, texture = 0.5f)[0], middle)
        val full = std(smoothed(lightness, zero, zero, mask, 8f, texture = 1f)[0], middle)

        assertTrue("$none < $half < $full", none < half && half < full)
        assertEquals(std(lightness, middle) * 0.5f, half, std(lightness, middle) * 0.1f)
    }

    @Test
    fun sizes_followTheFace() {
        assertEquals(0.8f, SkinSmoothMath.fineSigma(30f), 0f)
        assertEquals(5f, SkinSmoothMath.fineSigma(450f), 1e-5f)
        assertEquals(3f, SkinSmoothMath.boxRadius(18f), 0f)
        assertEquals(50f, SkinSmoothMath.boxRadius(450f), 1e-4f)
        // 상자 반지름이 줄인 단계에서 4~8칸
        for (radius in listOf(3f, 6f, 9f, 50f, 97f)) {
            val cells = radius / (1 shl SkinSmoothMath.level(radius))
            assertTrue("$radius → $cells", cells < 8f && (cells >= 4f || SkinSmoothMath.level(radius) == 0))
        }
    }

    @Test
    fun smoothing_isStale_whenWhatIsBelowChanges_butNotAfterWholeImageChanges() {
        // 아래부터: 원본 0, 잡티 제거 4, 피부 정돈 3, 색 보정 2, 그룹 1
        val record = SkinRecord(LayerId(0), color = LayerId(2), smooth = LayerId(3), spots = LayerId(4), faces = emptyList(), segmented = true)
        val layers = listOf(
            Layer(LayerId(0), "Photo", RasterContent(Raster.EMPTY)),
            Layer(LayerId(4), "Spots", RasterContent(Raster.EMPTY), parent = LayerId(1)),
            Layer(LayerId(3), "Smooth", RasterContent(Raster.EMPTY), opacity = 0.5f, parent = LayerId(1)),
            Layer(LayerId(2), "Color", AdjustmentContent(skin = SkinColor()), parent = LayerId(1)),
            Layer(LayerId(1), "Skin", GroupContent, skin = record)
        )
        val document = Document(100, 100, layers, nextLayerId = 5)
        val built = document.copy(layers = layers.map {
            if (it.id == LayerId(1)) it.copy(skin = record.copy(smoothBasis = CompositeKey.of(document.copy(layers = LayerTree.below(layers, LayerId(3)))))) else it
        })
        fun recordOf(d: Document) = checkNotNull(d.layers.last().skin)

        assertFalse(recordOf(built).isStale(built))
        // 위의 색 보정·정돈 세기를 바꿔도 낡지 않는다(정돈의 바탕이 아님)
        val stronger = built.copy(layers = built.layers.map { if (it.id == LayerId(3)) it.copy(opacity = 1f) else it })
        assertFalse(recordOf(stronger).isStale(stronger))
        // 원본의 보정을 바꾸면 낡는다
        val warmer = built.copy(layers = built.layers.map { if (it.id == LayerId(0)) it.copy(adjustments = Adjustments(temperature = 0.2f)) else it })
        assertTrue(recordOf(warmer).isStale(warmer))
        // 문서 전체를 함께 바꾼 뒤에는 새 그림을 바탕으로 다시 정한다(낡았던 것은 그대로 낡음)
        assertFalse(recordOf(SkinRecord.rebased(built, warmer)).isStale(warmer))
        assertTrue(recordOf(SkinRecord.rebased(warmer, warmer)).isStale(warmer))
        // 연 문서는 지금 그림이 바탕이다
        val opened = SkinRecord.withBases(warmer.copy(layers = warmer.layers.map { if (it.id == LayerId(1)) it.copy(skin = record) else it }))
        assertFalse(recordOf(opened).isStale(opened))
    }
}
