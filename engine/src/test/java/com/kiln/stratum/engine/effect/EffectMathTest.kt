package com.kiln.stratum.engine.effect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectMathTest {

    @Test
    fun blurSigma_growsWithTheBrushAndStrength_withinItsLimits() {
        // 지름 100px, 강도 50%: 100 × (1% + 4.5%) = 5.5px
        assertEquals(5.5f, EffectMath.blurSigma(100f, 0.5f), 1e-4f)
        assertTrue(EffectMath.blurSigma(100f, 1f) > EffectMath.blurSigma(100f, 0.5f))
        assertTrue(EffectMath.blurSigma(300f, 0.5f) > EffectMath.blurSigma(100f, 0.5f))
        // 아주 작은 붓·아주 큰 붓은 한계에서 멈춘다
        assertEquals(EffectMath.MIN_SIGMA, EffectMath.blurSigma(1f, 0f), 0f)
        assertEquals(EffectMath.MAX_SIGMA, EffectMath.blurSigma(1000f, 1f), 0f)
    }

    @Test
    fun sharpen_usesASmallFixedRangeBlur_andStrengthSetsTheAmount() {
        assertEquals(1f, EffectMath.sharpenSigma(100f), 1e-5f)
        assertEquals(0.7f, EffectMath.sharpenSigma(10f), 1e-5f)
        assertEquals(2f, EffectMath.sharpenSigma(1000f), 1e-5f)
        assertEquals(0.75f, EffectMath.sharpenAmount(0.5f), 1e-5f)
        assertEquals(0f, EffectMath.sharpenAmount(0f), 0f)
    }

    @Test
    fun taps_reachThreeSigma_withGaussianWeights() {
        // 가운데가 가장 무겁고, 끝(3σ)은 exp(-4.5)
        assertEquals(1f, EffectMath.tapWeight(0), 0f)
        assertEquals(kotlin.math.exp(-4.5f), EffectMath.tapWeight(EffectMath.TAPS), 1e-6f)
        assertEquals(EffectMath.tapWeight(-5), EffectMath.tapWeight(5), 0f)
        assertEquals(3f * 4f, EffectMath.tapStep(4f) * EffectMath.TAPS, 1e-4f)
    }

    @Test
    fun margin_coversWhatTheShaderReads() {
        val blur = EffectFill(EffectKind.BLUR, strength = 0.5f)
        // σ 5.5 → 3σ = 16.5 → 17 + 1
        assertEquals(18, EffectMath.margin(blur, 100f))
        assertEquals(4, EffectMath.margin(EffectFill(EffectKind.SHARPEN), 100f))
        assertEquals(0, EffectMath.margin(EffectFill(EffectKind.MOSAIC, cell = 40), 100f))
    }

    @Test
    fun cellAligned_coversWholeCellsFromTheDocumentOrigin_andStopsAtTheEdge() {
        assertEquals(30 to 90, EffectMath.cellAligned(45, 61, cell = 30, limit = 1000))
        assertEquals(0 to 30, EffectMath.cellAligned(0, 30, cell = 30, limit = 1000))
        // 문서 끝 칸은 문서 안쪽만
        assertEquals(990 to 1000, EffectMath.cellAligned(995, 1000, cell = 30, limit = 1000))
    }

    @Test
    fun smallCells_readEveryPixel_largeCellsReadEvenly() {
        // 칸 4px: 픽셀 가운데 넷(정확한 평균)
        assertArrayEquals(floatArrayOf(8.5f, 9.5f, 10.5f, 11.5f), EffectMath.cellSamples(10.5f, cell = 4, limit = 100), 0f)
        // 칸 64px: 고르게 16곳(4px마다 가운데)
        val samples = EffectMath.cellSamples(70.5f, cell = 64, limit = 1000)
        assertEquals(EffectMath.CELL_SAMPLES, samples.size)
        assertEquals(66f, samples.first(), 1e-4f)
        assertEquals(126f, samples.last(), 1e-4f)
        // 문서 끝에서 잘린 칸은 안쪽만 읽는다(990..1000)
        val edge = EffectMath.cellSamples(995.5f, cell = 30, limit = 1000)
        assertEquals(10, edge.size)
        assertEquals(990.5f, edge.first(), 0f)
        assertEquals(999.5f, edge.last(), 0f)
    }
}
