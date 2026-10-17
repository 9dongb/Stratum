package com.kiln.stratum.ui.edit

import com.kiln.stratum.engine.adjust.CurvePoint
import com.kiln.stratum.engine.adjust.ToneCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveEditingTest {

    private val sCurve = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.2f), CurvePoint(0.75f, 0.8f), CurvePoint(1f, 1f)))

    @Test
    fun hitTest_findsTheNearestPointWithinTheRadius() {
        // 200×100 픽셀 그래프에서 (0.25, 0.2) 점은 (50, 80) 픽셀이다
        assertEquals(1, CurveEditing.hitTest(sCurve, 55f, 78f, 200f, 100f, radius = 10f))
        assertEquals(-1, CurveEditing.hitTest(sCurve, 100f, 50f, 200f, 100f, radius = 10f))
        // 오른쪽 위 끝 점
        assertEquals(3, CurveEditing.hitTest(sCurve, 198f, 2f, 200f, 100f, radius = 10f))
    }

    @Test
    fun addPoint_putsAPointOnTheCurve_inOrder() {
        val (added, index) = checkNotNull(CurveEditing.addPoint(ToneCurve.IDENTITY, 0.4f))

        assertEquals(1, index)
        assertEquals(3, added.points.size)
        // 대각선 위의 점이다(계산 오차만큼 다를 수 있다)
        assertEquals(0.4f, added.points[1].x, 0f)
        assertEquals(0.4f, added.points[1].y, 1e-6f)
    }

    @Test
    fun addPoint_refusesWhenTooCloseOrFull() {
        assertNull(CurveEditing.addPoint(sCurve, 0.26f))
        // 점이 가득하면 이웃과 충분히 떨어진 곳(두 점의 가운데)에도 더하지 않는다
        val full = ToneCurve(List(ToneCurve.MAX_POINTS) { CurvePoint(it / (ToneCurve.MAX_POINTS - 1f), 0.5f) })
        assertNull(CurveEditing.addPoint(full, 0.5f))
    }

    @Test
    fun movePoint_staysBetweenNeighbours_andInsideTheGraph() {
        val moved = CurveEditing.movePoint(sCurve, 1, x = 0.9f, y = 1.4f)

        assertEquals(CurvePoint(0.75f - CurveEditing.MIN_GAP, 1f), moved.points[1])
        // 양 끝 점도 안쪽으로 옮길 수 있지만 이웃을 넘지 않는다
        assertEquals(CurvePoint(0f, 0.3f), CurveEditing.movePoint(sCurve, 0, x = -0.2f, y = 0.3f).points[0])
        assertEquals(0.75f + CurveEditing.MIN_GAP, CurveEditing.movePoint(sCurve, 3, x = 0.1f, y = 1f).points[3].x, 1e-6f)
    }

    @Test
    fun nearestByInput_picksThePointWithTheClosestInput() {
        assertEquals(1, CurveEditing.nearestByInput(sCurve, 0.3f))
        assertEquals(3, CurveEditing.nearestByInput(sCurve, 0.95f))
    }

    @Test
    fun removePoint_keepsTheEnds() {
        assertEquals(listOf(CurvePoint(0f, 0f), CurvePoint(0.75f, 0.8f), CurvePoint(1f, 1f)), CurveEditing.removePoint(sCurve, 1)?.points)
        assertNull(CurveEditing.removePoint(sCurve, 0))
        assertNull(CurveEditing.removePoint(sCurve, 3))
        assertTrue(CurveEditing.canRemove(sCurve, 2))
        assertFalse(CurveEditing.canRemove(ToneCurve.IDENTITY, 1))
    }
}
