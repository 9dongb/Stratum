package com.kiln.stratum.engine.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelRectTest {

    @Test
    fun intersect_returnsOverlapOrNull() {
        val a = PixelRect(0, 0, 100, 50)

        assertEquals(PixelRect(40, 10, 100, 50), a.intersect(PixelRect(40, 10, 200, 60)))
        // 변이 닿기만 하면 겹치지 않는다
        assertNull(a.intersect(PixelRect(100, 0, 200, 50)))
    }

    @Test
    fun contains_checksWholeRect() {
        val a = PixelRect(0, 0, 100, 50)

        assertTrue(PixelRect(10, 10, 100, 50) in a)
        assertFalse(PixelRect(10, 10, 101, 50) in a)
    }

    @Test
    fun offset_movesBothCorners() {
        assertEquals(PixelRect(-5, 13, 95, 63), PixelRect(0, 0, 100, 50).offset(-5, 13))
    }

    @Test
    fun rejectsInvertedRect() {
        assertThrows(IllegalArgumentException::class.java) { PixelRect(10, 0, 5, 10) }
    }
}
