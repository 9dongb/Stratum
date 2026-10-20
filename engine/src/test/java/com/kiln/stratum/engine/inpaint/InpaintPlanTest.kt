package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.inpaint.InpaintPlan.MIN_MARGIN
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintPlanTest {

    private val document = PixelSize(4000, 3000)

    private fun assertRoomAround(window: PixelRect, hole: PixelRect, margin: Int) {
        assertTrue("$hole in $window", hole in window)
        assertTrue("room left/top in $window", hole.left - window.left >= margin && hole.top - window.top >= margin)
        assertTrue("room right/bottom in $window", window.right - hole.right >= margin && window.bottom - hole.bottom >= margin)
    }

    @Test
    fun planLevel_keepsTheLongSideWithin1024() {
        assertEquals(0, InpaintPlan.planLevel(1024, 10))
        assertEquals(1, InpaintPlan.planLevel(1025, 10))
        assertEquals(2, InpaintPlan.planLevel(4000, 3000))
        assertEquals(3, InpaintPlan.planLevel(8160, 6120))
    }

    @Test
    fun aSmallBlemish_getsOneModelSizedWindow_withRoomAround() {
        val hole = PixelRect(1000, 1000, 1040, 1040)

        val window = InpaintPlan.windows(hole, 1600, document).single()

        assertEquals(512, window.size)
        assertEquals(0, window.level)
        assertEquals(1.0, window.scale(), 0.0)
        assertRoomAround(window.bounds, hole, MIN_MARGIN)
    }

    @Test
    fun aHoleAtTheRightEdge_pushesTheWindowInside_andKeepsTheWholeHole() {
        val hole = PixelRect(3950, 1000, 4000, 1050)

        val window = InpaintPlan.windows(hole, 2500, document).single()

        assertTrue(window.bounds.right <= document.width)
        assertTrue(hole in window.bounds)
    }

    @Test
    fun aLargeHole_takesAboutAThirdOfItsWindow() {
        val hole = PixelRect(1500, 1100, 2500, 1900)

        val window = InpaintPlan.windows(hole, 800_000, document).single()

        val fraction = 800_000.0 / (window.size.toDouble() * window.size)
        assertTrue("fraction $fraction", fraction in 0.25..0.5)
        assertTrue(hole in window.bounds)
        assertTrue(window.levelSize in 512..1024)
    }

    @Test
    fun aLongThinHole_isSplitIntoOverlappingWindowsAlongItsLength() {
        val hole = PixelRect(500, 1500, 3500, 1520)

        val windows = InpaintPlan.windows(hole, 60_000, document)

        assertTrue("${windows.size} windows", windows.size in 2..InpaintPlan.MAX_WINDOWS)
        for (x in hole.left until hole.right) {
            assertTrue("column $x", windows.any { x >= it.bounds.left && x < it.bounds.right && hole.top >= it.bounds.top && hole.bottom <= it.bounds.bottom })
        }
        for ((a, b) in windows.zipWithNext()) assertTrue("overlap of $a and $b", a.bounds.right - b.bounds.left >= 2 * MIN_MARGIN)
        for (window in windows) assertTrue(window.bounds in PixelRect.of(document))
    }

    @Test
    fun aLongObjectInABigPhoto_keepsATenthOfTheWindowAsRoom() {
        // 50MP 사진의 자동차(긴 변 3000px): 여백 128px만 두르면 창(3256px)의 4%라 긴 쪽 둘레를 거의 못 본다
        val photo = PixelSize(8160, 6120)
        val hole = PixelRect(2580, 2600, 5580, 3500)

        val window = InpaintPlan.windows(hole, 2_000_000, photo).single()

        val room = InpaintPlan.margin(window.size)
        assertTrue("room $room of ${window.size}", room >= window.size / 10 && room > MIN_MARGIN)
        assertRoomAround(window.bounds, hole, room)
        assertTrue(window.levelSize in 512..1024)
    }

    @Test
    fun inASmallPhoto_theRoomIsStillAtLeastTheFixedMargin() {
        assertEquals(MIN_MARGIN, InpaintPlan.margin(512))
        assertEquals(MIN_MARGIN, InpaintPlan.margin(1280))
        assertEquals(375, InpaintPlan.margin(3750))
    }

    @Test
    fun aTinyDocument_usesItsShortSide_andIsEnlargedForTheModel() {
        val small = PixelSize(300, 200)

        val window = InpaintPlan.windows(PixelRect(100, 80, 150, 130), 2500, small).single()

        assertEquals(200, window.size)
        assertEquals(0, window.level)
        assertTrue(window.scale() < 1.0)
        assertTrue(window.bounds in PixelRect.of(small))
    }

    @Test
    fun windowsAreAlignedToTheCellsOfTheirLevel() {
        val photo = PixelSize(8160, 6120)
        val hole = PixelRect(3000, 2000, 4500, 3200)

        val window = InpaintPlan.windows(hole, 1_800_000, photo).single()

        val cell = 1 shl window.level
        assertTrue("level ${window.level}", window.level >= 1)
        assertEquals(0, window.left % cell)
        assertEquals(0, window.top % cell)
        assertEquals(0, window.size % cell)
        assertTrue(window.levelSize in 512..1024)
        assertTrue(hole in window.bounds)
    }

    @Test
    fun atTheEdgeOfAnUnalignedDocument_theWindowStillHoldsTheHole_andOvershootsByLessThanACell() {
        val odd = PixelSize(4001, 3001)
        val hole = PixelRect(3200, 1000, 4001, 2000)

        val window = InpaintPlan.windows(hole, 800_000, odd).single()

        val cell = 1 shl window.level
        assertTrue(hole in window.bounds)
        assertTrue(window.bounds.right - odd.width < cell)
        assertEquals(0, window.left % cell)
    }

    @Test
    fun dilation_coversAtLeastFourDocumentPixels() {
        assertEquals(4f, InpaintPlan.dilation(1.0), 0f)
        assertEquals(3f, InpaintPlan.dilation(2.0), 0f)
        assertEquals(10f, InpaintPlan.dilation(0.4), 0f)
        for (scale in listOf(0.3, 0.5, 1.0, 1.5, 2.0, 4.0, 12.0)) assertTrue("scale $scale", InpaintPlan.dilation(scale) * scale >= 4.0 - 1e-9)
    }
}
