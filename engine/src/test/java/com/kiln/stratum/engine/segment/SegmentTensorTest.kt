package com.kiln.stratum.engine.segment

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentTensorTest {

    @Test
    fun input_isChannelFirst_andNormalizedLikeImageNet() {
        // 2×2: 빨강, 초록 / 파랑, 회색 128
        val rgba = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -128, -128, -128, -1)

        val input = SegmentTensor.input(rgba, size = 2)

        assertEquals(12, input.size)
        val gray = 128 / 255f
        assertArrayEquals(floatArrayOf((1f - 0.485f) / 0.229f, -0.485f / 0.229f, -0.485f / 0.229f, (gray - 0.485f) / 0.229f), input.copyOfRange(0, 4), 1e-5f)
        assertArrayEquals(floatArrayOf(-0.456f / 0.224f, (1f - 0.456f) / 0.224f, -0.456f / 0.224f, (gray - 0.456f) / 0.224f), input.copyOfRange(4, 8), 1e-5f)
        assertArrayEquals(floatArrayOf(-0.406f / 0.225f, -0.406f / 0.225f, (1f - 0.406f) / 0.225f, (gray - 0.406f) / 0.225f), input.copyOfRange(8, 12), 1e-5f)
    }

    @Test
    fun points_withoutAPositionPattern_areSinZeroCosOne_plusThePointEmbeddings() {
        // 위치 무늬 행렬이 0이면 어디를 눌러도 sin 0, cos 1이다
        val positive = FloatArray(256) { it / 1000f }
        val notAPoint = FloatArray(256) { -it / 1000f }
        val constants = FloatArray(256) + positive + notAPoint

        val points = SegmentTensor.points(constants, 300f, 700f)

        assertEquals(512, points.size)
        for (k in 0 until 128) {
            assertEquals(positive[k], points[k], 1e-6f)
            assertEquals(1f + positive[128 + k], points[128 + k], 1e-6f)
        }
        assertArrayEquals(notAPoint, points.copyOfRange(256, 512), 0f)
    }

    @Test
    fun points_withTheRealConstants_matchTheMacReference() {
        // 앱에 넣은 sam2_prompt.bin으로, 탭 (100, 900)을 맥(numpy, float64)에서 계산한 값
        val bytes = File("src/main/assets/models/sam2_prompt.bin").readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val constants = FloatArray(buffer.remaining()).also { buffer.get(it) }

        val points = SegmentTensor.points(constants, 100f, 900f)

        val expected = mapOf(
            0 to 0.947020956372003f,
            1 to 0.40425128596640536f,
            127 to -0.9675946965700732f,
            128 to 0.2868043846446248f,
            255 to 0.24375152242512693f,
            256 to 0.013688902370631695f,
            511 to 0.0032402570359408855f
        )
        for ((index, value) in expected) assertEquals("point value $index", value, points[index], 1e-5f)
    }

    @Test
    fun toInput_stretchesTheWholeDocumentOverTheInput() {
        assertEquals(512f, SegmentTensor.toInput(1000f, 2000), 0f)
        assertEquals(1024f, SegmentTensor.toInput(750f, 750), 0f)
    }
}
