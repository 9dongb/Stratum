package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustItemTest {

    @Test
    fun eachSlider_readsAndWritesItsOwnValue() {
        for ((index, item) in AdjustItem.sliders.withIndex()) {
            val value = (index + 1) / 10f
            val changed = item.applyTo(Adjustments.NONE, value)

            assertEquals(value, item.valueIn(changed), 0f)
            // 다른 항목은 그대로다
            for (other in AdjustItem.sliders - item) assertEquals(0f, other.valueIn(changed), 0f)
        }
    }

    @Test
    fun values_areClampedAndRoundedToHundredths() {
        assertEquals(2f, AdjustItem.EXPOSURE.applyTo(Adjustments.NONE, 3.7f).exposure, 0f)
        assertEquals(-1f, AdjustItem.CONTRAST.applyTo(Adjustments.NONE, -1.5f).contrast, 0f)
        assertEquals(0.35f, AdjustItem.SATURATION.applyTo(Adjustments.NONE, 0.3512f).saturation, 0f)
        // 0에 아주 가까운 음수는 0이 되어 '보정 없음'과 같다
        assertEquals(Adjustments.NONE, AdjustItem.SHADOWS.applyTo(Adjustments.NONE, -0.001f))
    }

    @Test
    fun format_showsSignedNumbers_withoutSignForZero() {
        val locale = Locale.ROOT
        assertEquals("+0.35", AdjustItem.EXPOSURE.format(0.35f, locale))
        assertEquals("-1.20", AdjustItem.EXPOSURE.format(-1.2f, locale))
        assertEquals("0.00", AdjustItem.EXPOSURE.format(0f, locale))
        assertEquals("+35", AdjustItem.CONTRAST.format(0.35f, locale))
        assertEquals("-100", AdjustItem.CONTRAST.format(-1f, locale))
        assertEquals("0", AdjustItem.CONTRAST.format(-0.001f, locale))
    }

    @Test
    fun changedAndReset_coverTheCurveToo() {
        val curve = ToneCurve(listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 1f)))
        val adjusted = Adjustments(contrast = 0.2f, curve = curve)

        assertTrue(AdjustItem.CONTRAST.isChanged(adjusted))
        assertTrue(AdjustItem.CURVE.isChanged(adjusted))
        assertFalse(AdjustItem.EXPOSURE.isChanged(adjusted))
        assertEquals(Adjustments(curve = curve), AdjustItem.CONTRAST.resetIn(adjusted))
        assertEquals(Adjustments(contrast = 0.2f), AdjustItem.CURVE.resetIn(adjusted))
    }
}
