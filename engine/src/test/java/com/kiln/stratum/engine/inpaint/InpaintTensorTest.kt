package com.kiln.stratum.engine.inpaint

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InpaintTensorTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun unpremultiply_restoresTheColorOfEachPixel() {
        val rgba = bytes(64, 32, 0, 128, 0, 0, 0, 0, 200, 100, 50, 255)

        val straight = InpaintTensor.unpremultiply(rgba)

        assertArrayEquals(floatArrayOf(127.5f, 63.75f, 0f, 0f, 0f, 0f, 200f, 100f, 50f), straight.rgb, 1e-4f)
        assertArrayEquals(floatArrayOf(128f, 0f, 255f), straight.alpha, 0f)
    }

    @Test
    fun known_leavesOutTheDilatedHole_andNearlyTransparentPixels() {
        val straight = InpaintTensor.Straight(FloatArray(12), floatArrayOf(255f, 255f, 7f, 8f))
        val distance = floatArrayOf(0f, 5f, 5f, 5f)

        val known = InpaintTensor.known(straight, distance, 3f)

        assertArrayEquals(booleanArrayOf(false, true, false, true), known)
    }

    @Test
    fun input_isChannelFirst_withTheMaskMinusAHalf_andColorsTimesTheMask() {
        // 2×2: 픽셀마다 R·G·B
        val rgb = floatArrayOf(
            0f, 127.5f, 255f,
            10f, 20f, 30f,
            255f, 0f, 0f,
            51f, 102f, 204f
        )
        val straight = InpaintTensor.Straight(rgb, FloatArray(4) { 255f })
        val known = booleanArrayOf(true, false, true, true)

        val input = InpaintTensor.input(straight, known, 2)

        assertEquals(16, input.size)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.5f, 0.5f), input.copyOfRange(0, 4), 0f)
        assertArrayEquals(floatArrayOf(-1f, 0f, 1f, 51f / 127.5f - 1f), input.copyOfRange(4, 8), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f, 102f / 127.5f - 1f), input.copyOfRange(8, 12), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, -1f, 204f / 127.5f - 1f), input.copyOfRange(12, 16), 1e-6f)
    }

    @Test
    fun output_mapsMinusOneToZero_andOneTo255_andClampsTheRest() {
        // 1×1: R·G·B 평면이 한 칸씩
        val output = InpaintTensor.output(floatArrayOf(-1f, 0f, 1.5f), 1)

        assertArrayEquals(floatArrayOf(0f, 127.5f, 255f), output, 1e-4f)
    }

    @Test
    fun writeWeights_coverTheDilatedHole_andFadeOnlyInsideIt() {
        val distance = floatArrayOf(0f, 1f, 2f, 2.5f, 3f, 3.5f, 4f)

        val weights = InpaintTensor.writeWeights(distance, radius = 3f, feather = 2f)

        assertArrayEquals(floatArrayOf(1f, 1f, 0.75f, 0.5f, 0.25f, 0f, 0f), weights, 1e-6f)
    }
}
