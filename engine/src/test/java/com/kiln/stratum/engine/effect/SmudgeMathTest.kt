package com.kiln.stratum.engine.effect

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmudgeMathTest {

    @Test
    fun strength_setsHowFarTheColorIsDragged() {
        // 강도 50%: 지름 100px의 반(50px)을 지나 반이 남는다
        assertEquals(50f, SmudgeMath.halfDistance(0.5f, 100f), 1e-4f)
        assertTrue(SmudgeMath.halfDistance(0.8f, 100f) > SmudgeMath.halfDistance(0.5f, 100f))
        assertEquals(0f, SmudgeMath.halfDistance(0f, 100f), 0f)
        assertTrue(SmudgeMath.halfDistance(1f, 100f).isInfinite())
    }

    @Test
    fun theSameDistance_leavesTheSameAmount_whateverTheSpacing() {
        // 50px를 10px 간격 다섯 자국으로 가든 1px 간격 쉰 자국으로 가든 반이 남는다
        val coarse = SmudgeMath.retention(0.5f, spacing = 10f, brushSize = 100f).pow(5)
        val fine = SmudgeMath.retention(0.5f, spacing = 1f, brushSize = 100f).pow(50)
        assertEquals(0.5f, coarse, 1e-4f)
        assertEquals(0.5f, fine, 1e-3f)
    }

    @Test
    fun noStrength_dragsNothing_andFullStrength_dragsForever() {
        assertEquals(0f, SmudgeMath.retention(0f, 10f, 100f), 0f)
        assertEquals(1f, SmudgeMath.retention(1f, 10f, 100f), 0f)
        val weak = SmudgeMath.retention(0.2f, 10f, 100f)
        val strong = SmudgeMath.retention(0.8f, 10f, 100f)
        assertTrue("$weak < $strong", weak < strong)
    }
}
