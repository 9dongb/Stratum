package com.kiln.stratum.engine.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BlendMathTest {

    private fun gray(value: Float, alpha: Float = 1f) = floatArrayOf(value * alpha, value * alpha, value * alpha, alpha)

    private val transparent = floatArrayOf(0f, 0f, 0f, 0f)

    @Test
    fun opaqueGrays_followTheTextbookFormulas() {
        val half = gray(0.5f)

        assertArrayEquals(gray(0.5f), BlendMath.blend(BlendMode.NORMAL, half, gray(0.2f)), 1e-6f)
        assertArrayEquals(gray(0.25f), BlendMath.blend(BlendMode.MULTIPLY, half, half), 1e-6f)
        assertArrayEquals(gray(0.75f), BlendMath.blend(BlendMode.SCREEN, half, half), 1e-6f)
        // 오버레이: 아래가 어두우면 2·위·아래, 밝으면 1 - 2·(1-위)·(1-아래)
        assertArrayEquals(gray(2f * 0.8f * 0.25f), BlendMath.blend(BlendMode.OVERLAY, gray(0.8f), gray(0.25f)), 1e-6f)
        assertArrayEquals(gray(1f - 2f * 0.2f * 0.25f), BlendMath.blend(BlendMode.OVERLAY, gray(0.8f), gray(0.75f)), 1e-6f)
    }

    @Test
    fun transparentSides_leaveTheOtherSideAsIs_inEveryMode() {
        val color = floatArrayOf(0.3f, 0.1f, 0.2f, 0.6f)
        for (mode in BlendMode.entries) {
            assertArrayEquals("$mode over transparent", color, BlendMath.blend(mode, color, transparent), 1e-6f)
            assertArrayEquals("transparent $mode over color", color, BlendMath.blend(mode, transparent, color), 1e-6f)
        }
    }

    @Test
    fun opacity_isTheSameAsMixingTheBlendedResultWithTheBackdrop() {
        // 포토샵 방식: 결과 = 아래 + 불투명도 × (섞은 결과 - 아래)
        val source = floatArrayOf(0.9f * 0.8f, 0.2f * 0.8f, 0.4f * 0.8f, 0.8f)
        val backdrop = gray(0.6f)
        for (mode in BlendMode.entries) {
            val blended = BlendMath.blend(mode, source, backdrop)
            val expected = FloatArray(4) { backdrop[it] + 0.3f * (blended[it] - backdrop[it]) }
            assertArrayEquals("$mode", expected, BlendMath.blend(mode, BlendMath.withOpacity(source, 0.3f), backdrop), 1e-5f)
        }
    }

    @Test
    fun alpha_isTheUsualUnion() {
        val result = BlendMath.blend(BlendMode.MULTIPLY, gray(0.5f, alpha = 0.5f), gray(0.5f, alpha = 0.4f))

        assertEquals(0.5f + 0.4f * 0.5f, result[3], 1e-6f)
    }
}
