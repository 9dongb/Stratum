package com.kiln.stratum.engine.document

import org.junit.Assert.assertEquals
import org.junit.Test

class HdrInfoTest {

    private fun info(min: Float, max: Float, gamma: Float) =
        HdrInfo(GainRgb(min), GainRgb(max), GainRgb(gamma), GainRgb(0.015625f), GainRgb(0.015625f), 4f, 1f, singleChannel = true)

    @Test
    fun neutral_isZeroWhenTheGainmapOnlyBrightens() {
        assertEquals(GainRgb(0f), info(1f, 4f, 1f).neutral)
    }

    @Test
    fun neutral_isWhereTheLogRatioCrossesOne() {
        // ln 0.5 ~ ln 4 사이에서 ln 1의 자리는 1/3, 감마 2면 그 제곱
        assertEquals(1f / 3f, info(0.5f, 4f, 1f).neutral.r, 1e-5f)
        assertEquals(1f / 9f, info(0.5f, 4f, 2f).neutral.r, 1e-5f)
    }

    @Test
    fun neutral_withASingleRatio_isZero() {
        assertEquals(0f, info(2f, 2f, 1f).neutral.g, 0f)
    }
}
