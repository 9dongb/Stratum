package com.kiln.stratum.engine.retouch

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotSearchTest {

    private val gray = 0xFF808080.toInt()
    private val red = 0xFFFF0000.toInt()
    private val dark = 0xFF101010.toInt()

    /** 가운데 ([cx], [cy]), 반지름 [radius]의 둥근 칠한 곳 */
    private fun disc(width: Int, height: Int, cx: Float, cy: Float, radius: Float) =
        BooleanArray(width * height) { i -> hypot(i % width + 0.5f - cx, i / width + 0.5f - cy) <= radius }

    @Test
    fun nearAColorEdge_takesFromTheSameSide() {
        // 왼쪽은 회색, x 60부터 빨강. 경계 가까운 회색 쪽 얼룩은 회색 쪽에서 가져와야 한다(둘레가 빨강이 섞이지 않은 곳).
        val width = 160
        val height = 120
        val pixels = IntArray(width * height) { i -> if (i % width < 60) gray else red }
        val painted = disc(width, height, 45f, 60f, 6f)

        val shift = checkNotNull(SpotSearch.find(pixels, painted, width, height, radius = 6f))

        // 옮긴 칠한 곳(오른쪽 끝 51)과 띠가 회색 쪽 안에 있다
        assertTrue("$shift", 51 + shift.dx + 3 <= 60)
    }

    @Test
    fun stripes_lineUpWithTheSurroundings() {
        // 세로 줄무늬(주기 6)라 위·아래로 옮긴 곳이 둘레와 똑같다
        val width = 120
        val height = 120
        val pixels = IntArray(width * height) { i -> if ((i % width) % 6 < 3) gray else dark }
        val painted = disc(width, height, 60f, 60f, 5f)

        val shift = checkNotNull(SpotSearch.find(pixels, painted, width, height, radius = 5f))

        assertEquals("$shift", 0, Math.floorMod(shift.dx, 6))
    }

    @Test
    fun avoidsAnotherBlemishNearby() {
        // 회색 바탕에 어두운 얼룩 둘. 칠한 얼룩 A의 오른쪽 가까이에 얼룩 B가 있으면 그쪽에서 가져오지 않는다.
        val width = 160
        val height = 160
        val blemishB = disc(width, height, 100f, 80f, 6f)
        val painted = disc(width, height, 80f, 80f, 6f)
        val pixels = IntArray(width * height) { i -> if (painted[i] || blemishB[i]) dark else gray }

        val shift = checkNotNull(SpotSearch.find(pixels, painted, width, height, radius = 6f))

        // 가져올 안쪽에 B가 들어가지 않는다
        for (i in painted.indices) {
            if (!painted[i]) continue
            val x = i % width + shift.dx
            val y = i / width + shift.dy
            assertTrue("$shift takes B", !blemishB[y * width + x])
        }
    }

    @Test
    fun aLongThinStroke_takesFromBesideIt() {
        // 가로로 긴 획(전선 같은 것)은 위·아래에서 가져온다(옆으로 옮기면 획과 겹친다)
        val width = 300
        val height = 100
        val pixels = IntArray(width * height) { gray }
        val painted = BooleanArray(width * height) { i -> i % width in 20 until 280 && i / width in 47 until 53 }

        val shift = checkNotNull(SpotSearch.find(pixels, painted, width, height, radius = 3f))

        assertTrue("$shift", kotlin.math.abs(shift.dy) >= 6)
    }

    @Test
    fun reach_followsTheWidestExtent_andLimitsTheCandidates() {
        // 작은 점은 반지름의 5배까지, 긴 획·큰 덩어리는 그 길이를 비켜 가는 거리(1.5배 + 띠)까지 간다
        assertEquals(15f, SpotSearch.maxReach(radius = 3f, extent = 6f), 0.001f)
        assertEquals(151.5f, SpotSearch.maxReach(radius = 3f, extent = 100f), 0.001f)

        // 가장 먼 거리가 가장 가까운 후보(반지름의 2.5배)보다 짧으면 찾지 못한다
        val pixels = IntArray(100 * 100) { gray }
        val painted = disc(100, 100, 50f, 50f, 6f)
        assertNull(SpotSearch.find(pixels, painted, 100, 100, radius = 6f, reach = 10f))
        assertNotNull(SpotSearch.find(pixels, painted, 100, 100, radius = 6f, reach = SpotSearch.maxReach(6f, 12f)))
    }

    @Test
    fun alongASlantedEdge_findsTheAlignedPlace() {
        // 비스듬한(약 27°) 어두운 띠 바로 옆을 띠를 따라 길게 칠했다. 띠를 따라 옮긴 곳(띠가 같은 자리에 오는 곳)을 찾아야 한다.
        val width = 400
        val height = 300
        fun onBand(x: Int, y: Int) = abs((y - 50) - (x - 20) / 2.0) < 8
        val pixels = IntArray(width * height) { i -> if (onBand(i % width, i / width)) dark else gray }
        // 띠의 아래 가장자리를 따라 지름 16의 자국들(가운데 x 150..250)
        val painted = BooleanArray(width * height) { i ->
            val x = i % width
            val y = i / width
            (150..250 step 4).any { cx -> hypot(x - cx.toDouble(), y - (50 + (cx - 20) / 2.0 + 12)) <= 8.0 }
        }

        val shift = checkNotNull(SpotSearch.find(pixels, painted, width, height, radius = 8f, reach = SpotSearch.maxReach(8f, 130f)))

        // 띠를 따라 옮긴 곳: 세로로 옮긴 양이 가로로 옮긴 양의 절반(±2픽셀)
        assertTrue("$shift", abs(shift.dy - shift.dx / 2.0) <= 2.0)
    }

    @Test
    fun noRoomAroundTheSpot_meansNoSource() {
        val pixels = IntArray(20 * 20) { gray }
        val painted = BooleanArray(20 * 20) { i -> i % 20 in 2 until 18 && i / 20 in 2 until 18 }

        assertNull(SpotSearch.find(pixels, painted, 20, 20, radius = 8f))
    }

    @Test
    fun nothingPainted_meansNoSource() {
        assertNull(SpotSearch.find(IntArray(100) { gray }, BooleanArray(100), 10, 10, radius = 2f))
    }

    @Test
    fun ring_isTheBandAroundThePaintedPlace() {
        val painted = BooleanArray(30 * 30) { i -> i % 30 in 10 until 12 && i / 30 in 10 until 12 }

        val ring = SpotSearch.ringOf(painted, 30, 30, band = 1)

        // 4×4 사각형에서 칠한 2×2를 뺀 12개
        assertEquals(12, ring.size)
        assertNotNull(ring.firstOrNull())
    }
}
