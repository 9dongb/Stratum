package com.kiln.stratum.engine.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskContourTest {

    /** '#'은 다 고름, '.'은 고르지 않음 */
    private fun mask(vararg rows: String): ByteArray =
        rows.flatMap { row -> row.map { if (it == '#') (-1).toByte() else 0.toByte() } }.toByteArray()

    private fun FloatArray.points(): List<Pair<Float, Float>> = toList().chunked(2).map { it[0] to it[1] }

    @Test
    fun emptyMask_hasNoLines() {
        assertTrue(MaskContour.trace(mask("...", "..."), 3, 2).isEmpty())
    }

    @Test
    fun onePixel_isOneClosedLoopAroundIt() {
        val lines = MaskContour.trace(mask("...", ".#.", "..."), 3, 3)

        assertEquals(1, lines.size)
        val points = lines.single().points()
        // 닫힌 선: 끝 점이 첫 점과 같고, 네 변의 가운데(반 픽셀 밖)를 지난다
        assertEquals(points.first(), points.last())
        assertEquals(setOf(1f to 1.5f, 1.5f to 1f, 2f to 1.5f, 1.5f to 2f), points.toSet())
    }

    @Test
    fun selectionTouchingTheEdge_isStillClosed() {
        val lines = MaskContour.trace(mask("##", "##"), 2, 2)

        assertEquals(1, lines.size)
        val points = lines.single().points()
        assertEquals(points.first(), points.last())
        // 마스크 밖 반 픽셀(0과 2)에서 닫힌다
        assertEquals(0f, points.minOf { it.first }, 0.001f)
        assertEquals(2f, points.maxOf { it.first }, 0.001f)
    }

    @Test
    fun twoSeparateAreas_makeTwoLines() {
        val lines = MaskContour.trace(mask("#..#", "#..#"), 4, 2)

        assertEquals(2, lines.size)
    }

    @Test
    fun hole_makesAnInnerLine() {
        val lines = MaskContour.trace(mask("###", "#.#", "###"), 3, 3)

        assertEquals(2, lines.size)
    }
}
