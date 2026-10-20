package com.kiln.stratum.engine.retouch

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoleFillTest {

    /** 가운데에 반지름 [radius]의 구멍(무게 0)이 난 [size]×[size] 무게 */
    private fun holeWeights(size: Int, radius: Float): FloatArray =
        FloatArray(size * size) { i ->
            val x = i % size + 0.5f - size / 2f
            val y = i / size + 0.5f - size / 2f
            if (hypot(x, y) < radius) 0f else 1f
        }

    @Test
    fun knownValuesStayExactly() {
        val size = 40
        val weights = holeWeights(size, 8f)
        val values = FloatArray(size * size) { i -> (i % size) / 40f }

        val filled = HoleFill.fill(values, weights, size, size, 1)

        for (i in weights.indices) if (weights[i] == 1f) assertEquals(values[i], filled[i], 0f)
    }

    @Test
    fun constantSurroundings_fillTheHoleWithTheSameValue() {
        val size = 64
        val weights = holeWeights(size, 20f)
        // 구멍 안의 값(얼룩)은 쓰지 않는다
        val values = FloatArray(size * size * 2) { i -> if (weights[i / 2] == 0f) 9f else if (i % 2 == 0) 0.25f else -0.5f }

        val filled = HoleFill.fill(values, weights, size, size, 2)

        val center = (size / 2) * size + size / 2
        assertEquals(0.25f, filled[center * 2], 1e-4f)
        assertEquals(-0.5f, filled[center * 2 + 1], 1e-4f)
    }

    @Test
    fun aHoleBetweenTwoValues_isFilledInBetween_andRisesSmoothly() {
        // 왼쪽 끝은 0, 오른쪽 끝은 1, 가운데 넓은 띠가 구멍이다
        val width = 96
        val height = 32
        val weights = FloatArray(width * height) { i -> if (i % width in 24 until 72) 0f else 1f }
        val values = FloatArray(width * height) { i -> if (i % width < 48) 0f else 1f }

        val filled = HoleFill.fill(values, weights, width, height, 1)

        val row = (height / 2) * width
        var previous = -1f
        for (x in 24 until 72) {
            val value = filled[row + x]
            assertTrue("x $x: $value", value in 0f..1f && value >= previous - 1e-4f)
            previous = value
        }
        // 가운데는 양쪽의 중간쯤이다
        assertEquals(0.5f, filled[row + 48], 0.15f)
    }

    @Test
    fun lowInfluence_keepsAnOutlierFromSpreadingIntoTheHole() {
        // 둘레는 0인데 위쪽 띠 하나만 1(어긋난 경계). 몫을 줄이면 구멍 안이 0에 가깝고, 띠 자신은 그대로 1이다.
        val size = 64
        val weights = holeWeights(size, 16f)
        val values = FloatArray(size * size) { i -> if (weights[i] == 1f && i % size in 28 until 36 && i / size < 32) 1f else 0f }
        val influence = FloatArray(size * size) { i -> if (values[i] == 1f) 0.01f else 1f }
        val center = (size / 2) * size + size / 2

        val spread = HoleFill.fill(values, weights, size, size, 1)
        val kept = HoleFill.fill(values, weights, size, size, 1, influence)

        assertTrue("${spread[center]}", spread[center] > 0.05f)
        assertTrue("${kept[center]}", kept[center] < 0.02f)
        val stripe = 5 * size + 30
        assertEquals(1f, kept[stripe], 0f)
    }

    @Test
    fun nothingKnown_meansZero() {
        val filled = HoleFill.fill(FloatArray(10 * 6 * 4) { 3f }, FloatArray(10 * 6), 10, 6, 4)

        assertTrue(filled.all { it == 0f })
    }

    @Test
    fun partlyKnownPixels_blendWithTheirSurroundings() {
        // 반쯤 아는 가운데 픽셀은 제 값(1)과 둘레(0)의 사이다
        val size = 9
        val weights = FloatArray(size * size) { 1f }
        val values = FloatArray(size * size)
        val center = 4 * size + 4
        weights[center] = 0.5f
        values[center] = 1f

        val filled = HoleFill.fill(values, weights, size, size, 1)

        assertTrue("${filled[center]}", filled[center] > 0.4f && filled[center] < 0.6f)
    }

    @Test
    fun onePixel_isItsValueTimesItsWeight() {
        val filled = HoleFill.fill(floatArrayOf(0.8f, 0.4f), floatArrayOf(0.5f), 1, 1, 2)

        assertTrue(abs(filled[0] - 0.4f) < 1e-6f && abs(filled[1] - 0.2f) < 1e-6f)
    }

    @Test
    fun influence_isSmallOnlyForAValueFarFromTheOthers_withAnyNumberOfChannels() {
        for (channels in listOf(1, 3, 4)) {
            val count = 100
            val values = FloatArray(count * channels) { 0.2f }
            values[10 * channels] = 0.7f
            val weights = FloatArray(count) { if (it == 50) 0f else 1f }

            val influence = HoleFill.influence(values, weights, channels)

            assertEquals("channels $channels", 1f, influence[0], 1e-6f)
            assertTrue("channels $channels: ${influence[10]}", influence[10] < 0.1f)
            assertEquals("channels $channels", 0f, influence[50], 0f)
        }
    }

    @Test
    fun influence_withACutoff_isZeroBeyondIt_andTheSameBelowIt() {
        val values = FloatArray(100) { 0.2f }
        values[10] = 0.7f
        values[20] = 0.3f
        val weights = FloatArray(100) { 1f }

        val plain = HoleFill.influence(values, weights, 1)
        val cut = HoleFill.influence(values, weights, 1, cutoff = 0.15f)

        assertTrue(plain[10] > 0f)
        assertEquals(0f, cut[10], 0f)
        assertEquals(plain[20], cut[20], 0f)
        assertEquals(1f, cut[0], 1e-6f)
    }

    @Test
    fun influence_withNothingKnown_isAllZero() {
        val influence = HoleFill.influence(FloatArray(6), FloatArray(3), 2)

        assertTrue(influence.all { it == 0f })
    }
}
