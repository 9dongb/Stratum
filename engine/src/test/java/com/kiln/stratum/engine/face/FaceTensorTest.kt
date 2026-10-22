package com.kiln.stratum.engine.face

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceTensorTest {

    // 2×2: 빨강, 초록 / 파랑, 회색 51(알파는 보지 않음)
    private val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0x80333333.toInt())

    @Test
    fun input_isChannelLast_RGB_stretchedToTheRange() {
        val input = FaceTensor.input(pixels, size = 2, low = -1f, high = 1f)

        assertEquals(12, input.size)
        val gray = 51 / 255f * 2f - 1f
        assertArrayEquals(floatArrayOf(1f, -1f, -1f, -1f, 1f, -1f, -1f, -1f, 1f, gray, gray, gray), input, 1e-5f)
    }

    @Test
    fun input_zeroToOne_forTheLandmarkModel() {
        val input = FaceTensor.input(pixels, size = 2, low = 0f, high = 1f)

        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0.2f, 0.2f, 0.2f), input, 1e-5f)
    }

    @Test
    fun modelInputs_haveTheModelSizes() {
        assertEquals(192 * 192 * 3, FaceTensor.detectorInput(IntArray(192 * 192)).size)
        assertEquals(256 * 256 * 3, FaceTensor.landmarkInput(IntArray(256 * 256)).size)
        // 검정은 얼굴 찾기에서 -1, 특징점에서 0
        assertEquals(-1f, FaceTensor.detectorInput(IntArray(192 * 192) { 0xFF000000.toInt() })[0], 0f)
        assertEquals(0f, FaceTensor.landmarkInput(IntArray(256 * 256) { 0xFF000000.toInt() })[0], 0f)
    }
}
