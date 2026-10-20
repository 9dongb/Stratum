package com.kiln.stratum.engine.segment

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentMaskTest {

    private val size = 16
    private val plane = size * size

    /** 칸마다 [inside]면 [high], 아니면 [low]인 후보 하나 */
    private fun candidate(high: Float = 8f, low: Float = -8f, inside: (x: Int, y: Int) -> Boolean) =
        FloatArray(plane) { if (inside(it % size, it / size)) high else low }

    private fun disc(cx: Float, cy: Float, r: Float) = { x: Int, y: Int -> hypot(x + 0.5f - cx, y + 0.5f - cy) <= r }

    @Test
    fun choose_prefersTheLargestStableCandidateThatHoldsTheTap() {
        val part = candidate(inside = disc(8f, 8f, 2f))
        val whole = candidate(inside = disc(8f, 8f, 5f))
        val elsewhere = candidate(inside = { x, _ -> x < 2 || x > 13 })

        assertEquals(1, SegmentMask.choose(part + whole + elsewhere, 3, size, 8, 8))
    }

    @Test
    fun choose_skipsACandidateThatCoversNearlyEverything() {
        // 단순한 그림에서 0번 후보가 그림 전체를 또렷하게 덮은 경우
        val everything = candidate(inside = { _, _ -> true })
        val faint = candidate(high = 0.5f, low = -0.5f, inside = disc(8f, 8f, 4f))
        val object_ = candidate(inside = disc(8f, 8f, 4f))

        assertEquals(2, SegmentMask.choose(everything + faint + object_, 3, size, 8, 8))
    }

    @Test
    fun choose_withNothingStable_takesTheMostStable() {
        // 안쪽 로짓이 1을 넘지 않으면 안정도가 0이다. 하나만 일부가 1을 넘는다.
        val flat = candidate(high = 0.5f, low = -0.5f, inside = disc(8f, 8f, 5f))
        val partly = FloatArray(plane) { i -> if (disc(8f, 8f, 5f)(i % size, i / size)) (if (i % 2 == 0) 2f else 0.5f) else -0.5f }
        val flat2 = candidate(high = 0.8f, low = -0.8f, inside = disc(8f, 8f, 6f))

        assertTrue(SegmentMask.stability(partly, 0, plane) < SegmentMask.STABLE)
        assertEquals(1, SegmentMask.choose(flat + partly + flat2, 3, size, 8, 8))
    }

    @Test
    fun choose_ignoresCandidatesWithoutTheTap_unlessNoneHasIt() {
        val left = candidate(inside = { x, _ -> x < 7 })
        val right = candidate(inside = { x, _ -> x >= 10 })
        val middle = candidate(inside = { x, _ -> x == 8 })

        // 탭한 칸을 품은 것은 가운데뿐이다(더 넓은 후보가 있어도)
        assertEquals(2, SegmentMask.choose(left + right + middle, 3, size, 8, 8))
        // 아무 후보도 탭한 칸을 품지 않으면 모두 보고 가장 넓은 것(왼쪽, 7줄)을 고른다
        assertEquals(0, SegmentMask.choose(left + right + middle, 3, size, 9, 8))
    }

    @Test
    fun stability_isTheShareOfTheSureAreaInTheLooseArea() {
        val logits = floatArrayOf(5f, 2f, 0.5f, -0.5f, -2f)

        assertEquals(2.0 / 4.0, SegmentMask.stability(logits, 0, logits.size), 1e-9)
        assertEquals(0.0, SegmentMask.stability(floatArrayOf(-3f, -4f), 0, 2), 0.0)
    }

    @Test
    fun threshold_stretchesTheLogitsOverTheWholeMask() {
        // 4×4 로짓의 왼쪽 반은 물체, 오른쪽 반은 아님. 10×3 마스크로 키우면 왼쪽 다섯 칸이 물체다.
        val logits = FloatArray(16) { if (it % 4 < 2) 1f else -1f }

        val mask = SegmentMask.threshold(logits, 0, 4, 10, 3)

        for (y in 0 until 3) {
            for (x in 0 until 10) assertEquals("($x, $y)", x < 5, mask[y * 10 + x])
        }
    }

    @Test
    fun threshold_readsTheChosenCandidate() {
        val logits = FloatArray(16) { -1f } + FloatArray(16) { 1f }

        assertTrue(SegmentMask.threshold(logits, 16, 4, 6, 6).all { it })
        assertTrue(SegmentMask.threshold(logits, 0, 4, 6, 6).none { it })
    }

    @Test
    fun clean_removesSmallSpecks_butKeepsTheTappedOne() {
        val width = 20
        val mask = BooleanArray(width * width)
        for (y in 5 until 15) for (x in 5 until 15) mask[y * width + x] = true // 큰 덩어리 100px
        mask[1 * width + 1] = true // 떨어진 점 하나
        mask[18 * width + 18] = true // 탭한 작은 점
        mask[18 * width + 17] = true

        val cleaned = SegmentMask.clean(mask, width, width, keepX = 18, keepY = 18, minArea = 5)

        assertFalse(cleaned[1 * width + 1])
        assertTrue(cleaned[18 * width + 18] && cleaned[18 * width + 17])
        assertTrue(cleaned[10 * width + 10])
    }

    @Test
    fun clean_fillsSmallHoles_butNotOnesTouchingTheEdge_orBigOnes() {
        val width = 20
        val mask = BooleanArray(width * width) { true }
        mask[10 * width + 10] = false // 작은 구멍
        for (y in 2 until 6) for (x in 2 until 6) mask[y * width + x] = false // 큰 구멍 16px
        mask[0 * width + 15] = false // 가장자리에 닿은 작은 틈

        val cleaned = SegmentMask.clean(mask, width, width, keepX = 10, keepY = 15, minArea = 5)

        assertTrue(cleaned[10 * width + 10])
        assertFalse(cleaned[3 * width + 3])
        assertFalse(cleaned[0 * width + 15])
    }

    @Test
    fun mask_givesFullAlphaInsideTheChosenShape_andNullWhenNothingIsFound() {
        val size = SegmentModel.MASK_SIZE
        val everything = FloatArray(size * size) { 8f }
        val disc = FloatArray(size * size) { i -> if (hypot(i % size + 0.5f - 128f, i / size + 0.5f - 128f) <= 40f) 8f else -8f }

        val alphas = checkNotNull(SegmentMask.mask(everything + disc + disc, 100, 50, tapX = 50, tapY = 25))

        assertEquals(100 * 50, alphas.size)
        assertEquals(0xFF, alphas[25 * 100 + 50].toInt() and 0xFF)
        assertEquals(0, alphas[2 * 100 + 2].toInt())
        assertNull(SegmentMask.mask(FloatArray(3 * size * size) { -5f }, 100, 50, tapX = 50, tapY = 25))
    }
}
