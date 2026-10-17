package com.kiln.stratum.engine.brush

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeDabsTest {

    private fun xs(dabs: List<StrokePoint>) = dabs.map { it.x }

    @Test
    fun dabsAreEvenlySpaced_fromTheFirstPoint() {
        val dabs = StrokeDabs.place(listOf(StrokePoint(0f, 5f), StrokePoint(10f, 5f)), spacing = 2f)

        assertEquals(listOf(0f, 2f, 4f, 6f, 8f, 10f), xs(dabs))
        assertTrue(dabs.all { it.y == 5f })
    }

    @Test
    fun addingPointByPoint_givesTheSameDabs() {
        val points = listOf(StrokePoint(0f, 0f), StrokePoint(3f, 0f), StrokePoint(3f, 0f), StrokePoint(3f, 4f), StrokePoint(10f, 4f))
        val whole = StrokeDabs.place(points, spacing = 2.5f)

        val live = StrokeDabs(2.5f)
        val added = points.flatMap { live.add(it) }

        assertEquals(whole, added)
        assertEquals(whole, live.dabs)
        // 앞 구간에서 남은 거리를 이어받아, 꺾인 곳에서도 간격이 같다(3 + 4 + 7 = 14 → 0, 2.5, …, 12.5)
        assertEquals(6, whole.size)
    }

    @Test
    fun aTap_placesOneDab() {
        assertEquals(listOf(StrokePoint(4f, 4f)), StrokeDabs.place(listOf(StrokePoint(4f, 4f)), spacing = 3f))
    }

    @Test
    fun bounds_coverTheRadius_andAreClippedToTheDocument() {
        val dabs = listOf(StrokePoint(10f, 10f), StrokePoint(90f, 30f))

        assertEquals(PixelRect(4, 4, 96, 36), StrokeDabs.bounds(dabs, radius = 5f, document = PixelSize(200, 200)))
        assertEquals(PixelRect(4, 4, 50, 20), StrokeDabs.bounds(dabs, radius = 5f, document = PixelSize(50, 20)))
        assertNull(StrokeDabs.bounds(listOf(StrokePoint(-50f, -50f)), radius = 5f, document = PixelSize(50, 20)))
        assertNull(StrokeDabs.bounds(emptyList(), radius = 5f, document = PixelSize(50, 20)))
    }

    @Test
    fun settings_checkRanges_andSpacingFollowsTheSize() {
        assertEquals(10f, BrushSettings(size = 100f).spacing, 0f)
        // 아주 작은 브러시도 1픽셀보다 촘촘하게 찍지 않는다
        assertEquals(1f, BrushSettings(size = 2f).spacing, 0f)
        assertThrows(IllegalArgumentException::class.java) { BrushSettings(size = 0f) }
        assertThrows(IllegalArgumentException::class.java) { BrushSettings(opacity = 1.5f) }
        assertEquals(0xFF123456.toInt(), BrushSettings(color = 0x00123456).opaqueColor)
    }
}
