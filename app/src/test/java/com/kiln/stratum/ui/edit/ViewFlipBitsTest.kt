package com.kiln.stratum.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 반전 값: 저장용 값으로 오가고, 그림 층 배율은 뒤집은 축만 -1이다 */
class ViewFlipBitsTest {

    @Test
    fun bits_roundTrip_forEveryCombination() {
        for (horizontal in listOf(false, true)) {
            for (vertical in listOf(false, true)) {
                val flip = ViewFlip(horizontal, vertical)
                assertEquals(flip, ViewFlip.fromBits(flip.bits))
            }
        }
        assertEquals(0, ViewFlip.None.bits)
    }

    @Test
    fun scales_areMinusOneOnlyOnTheFlippedAxis() {
        val horizontal = ViewFlip(horizontal = true)
        assertEquals(-1f, horizontal.scaleX)
        assertEquals(1f, horizontal.scaleY)
        assertTrue(horizontal.isFlipped)
        assertFalse(ViewFlip.None.isFlipped)
        assertEquals(-1f, ViewFlip(vertical = true).scaleY)
    }
}
