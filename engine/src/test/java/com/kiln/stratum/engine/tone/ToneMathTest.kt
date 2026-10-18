package com.kiln.stratum.engine.tone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMathTest {

    @Test
    fun midtoneDodge_brightensMidGray_mostAndLeavesBlackAndWhite() {
        // 중간 회색(밝기 0.5)은 무게 1: 0.5 + 0.5 × 0.5 × 0.5 = 0.625
        assertEquals(0.625f, ToneMath.tone(0.5f, 0.5f, ToneRange.MIDTONES, exposure = 0.5f, dodge = true), 1e-5f)
        // 검정·흰색은 중간 영역이 아니라 그대로다
        assertEquals(0f, ToneMath.tone(0f, 0f, ToneRange.MIDTONES, 1f, dodge = true), 1e-5f)
        assertEquals(1f, ToneMath.tone(1f, 1f, ToneRange.MIDTONES, 1f, dodge = false), 1e-5f)
    }

    @Test
    fun burn_darkens_andShadowsRangeHitsDarkPixelsHarder() {
        val darkBurn = ToneMath.tone(0.2f, 0.2f, ToneRange.SHADOWS, 1f, dodge = false)
        val lightBurn = ToneMath.tone(0.8f, 0.8f, ToneRange.SHADOWS, 1f, dodge = false)

        assertTrue(darkBurn < 0.2f)
        // 밝은 픽셀은 어두운 영역 무게가 작아 덜 바뀐다
        assertTrue(0.2f - darkBurn > 0.8f - lightBurn)
    }

    @Test
    fun highlightsDodge_affectsLightPixels() {
        val light = ToneMath.tone(0.8f, 0.8f, ToneRange.HIGHLIGHTS, 1f, dodge = true)
        val dark = ToneMath.tone(0.2f, 0.2f, ToneRange.HIGHLIGHTS, 1f, dodge = true)

        assertTrue(light - 0.8f > dark - 0.2f)
    }

    @Test
    fun zeroExposure_changesNothing_andResultsStayInRange() {
        assertEquals(0.4f, ToneMath.tone(0.4f, 0.4f, ToneRange.MIDTONES, 0f, dodge = true), 0f)
        for (c in listOf(0f, 0.3f, 1f)) {
            for (dodge in listOf(true, false)) {
                val v = ToneMath.tone(c, c, ToneRange.MIDTONES, 1f, dodge)
                assertTrue(v in 0f..1f)
            }
        }
    }
}
