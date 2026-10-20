package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.raster.PixelRect
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintMaskTest {

    /** '#'은 구멍, '.'은 빈 칸인 마스크 */
    private class Grid(rows: List<String>) {
        val width = rows.first().length
        val height = rows.size
        val cells = BooleanArray(width * height) { rows[it / width][it % width] == '#' }
        fun at(x: Int, y: Int) = y * width + x
    }

    private fun grid(vararg rows: String) = Grid(rows.toList())

    @Test
    fun anyCoverageCountsAsAHole() {
        val coverage = byteArrayOf(0, 1, 127, 255.toByte(), 0)

        assertArrayEquals(booleanArrayOf(false, true, true, true, false), InpaintMask.holes(coverage))
    }

    @Test
    fun aClosedRing_isFilledInside_butAnOpenU_isNot() {
        val ring = grid(
            ".......",
            ".#####.",
            ".#...#.",
            ".#...#.",
            ".#####.",
            "......."
        )
        val filledRing = InpaintMask.fillEnclosed(ring.cells, ring.width, ring.height)
        for (y in 2..3) for (x in 2..4) assertTrue("inside ($x, $y)", filledRing[ring.at(x, y)])
        assertFalse(filledRing[ring.at(0, 0)])
        assertFalse(filledRing[ring.at(6, 3)])

        val u = grid(
            ".......",
            ".#...#.",
            ".#...#.",
            ".#####.",
            "......."
        )
        val filledU = InpaintMask.fillEnclosed(u.cells, u.width, u.height)
        for (y in 1..2) for (x in 2..4) assertFalse("inside the U ($x, $y)", filledU[u.at(x, y)])
    }

    @Test
    fun aRingJoinedOnlyAtCorners_doesNotLeak() {
        val diamond = grid(
            "..#..",
            ".#.#.",
            "#...#",
            ".#.#.",
            "..#.."
        )
        val filled = InpaintMask.fillEnclosed(diamond.cells, diamond.width, diamond.height)

        for ((x, y) in listOf(2 to 1, 1 to 2, 2 to 2, 3 to 2, 2 to 3)) assertTrue("inside ($x, $y)", filled[diamond.at(x, y)])
        assertFalse(filled[diamond.at(0, 0)])
    }

    @Test
    fun distance_isEuclidean_andZeroInTheHole() {
        val size = 11
        val hole = BooleanArray(size * size).apply { this[5 * size + 5] = true }

        val distance = InpaintMask.distanceToHole(hole, size, size)

        assertEquals(0f, distance[5 * size + 5], 0f)
        assertEquals(1f, distance[5 * size + 6], 1e-6f)
        assertEquals(sqrt(2f), distance[6 * size + 6], 1e-6f)
        assertEquals(5f, distance[5 * size + 10], 1e-6f)
        assertEquals(sqrt(50f), distance[0], 1e-5f)
    }

    @Test
    fun distance_takesTheNearestOfSeveralHoles() {
        val width = 20
        val height = 3
        val hole = BooleanArray(width * height).apply {
            this[1 * width + 2] = true
            this[1 * width + 15] = true
        }

        val distance = InpaintMask.distanceToHole(hole, width, height)

        assertEquals(6f, distance[1 * width + 8], 1e-6f)
        assertEquals(6f, distance[1 * width + 9], 1e-6f)
        assertEquals(hypot(4f, 1f), distance[0 * width + 19], 1e-5f)
    }

    @Test
    fun withoutAHole_everythingIsInfinitelyFar() {
        val distance = InpaintMask.distanceToHole(BooleanArray(12), 4, 3)

        assertTrue(distance.all { it == Float.POSITIVE_INFINITY })
    }

    @Test
    fun dilatingAPoint_makesADiscOfThatRadius() {
        val size = 21
        val center = size / 2
        val hole = BooleanArray(size * size).apply { this[center * size + center] = true }

        val dilated = InpaintMask.dilate(InpaintMask.distanceToHole(hole, size, size), 4f)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = hypot((x - center).toFloat(), (y - center).toFloat()) <= 4f
                assertEquals("($x, $y)", inside, dilated[y * size + x])
            }
        }
        assertEquals(49, dilated.count { it })
    }

    @Test
    fun componentsJoinDiagonalNeighbours() {
        val mask = grid(
            "##....",
            "##..#.",
            "...#..",
            "......",
            "#....."
        )

        val regions = InpaintMask.components(mask.cells, mask.width, mask.height)

        assertEquals(
            listOf(
                HoleRegion(PixelRect(0, 0, 2, 2), 4),
                HoleRegion(PixelRect(3, 1, 5, 3), 2),
                HoleRegion(PixelRect(0, 4, 1, 5), 1)
            ),
            regions
        )
    }

    @Test
    fun nearbyRegionsAreGrouped_andFarOnesStayApart() {
        val a = HoleRegion(PixelRect(0, 0, 10, 10), 100)
        val b = HoleRegion(PixelRect(14, 0, 20, 10), 60)
        val far = HoleRegion(PixelRect(100, 100, 110, 110), 100)

        assertEquals(listOf(HoleRegion(PixelRect(0, 0, 20, 10), 160), far), InpaintMask.group(listOf(a, b, far), 5))
        assertEquals(listOf(a, b, far), InpaintMask.group(listOf(a, b, far), 3))
    }

    @Test
    fun groupingFollowsAChainOfNearRegions() {
        val first = HoleRegion(PixelRect(0, 0, 10, 10), 100)
        val second = HoleRegion(PixelRect(12, 0, 22, 10), 100)
        val third = HoleRegion(PixelRect(24, 0, 34, 10), 100)

        assertEquals(listOf(HoleRegion(PixelRect(0, 0, 34, 10), 300)), InpaintMask.group(listOf(first, third, second), 2))
    }
}
