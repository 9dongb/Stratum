package com.kiln.stratum.engine.inpaint

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintNoiseTest {

    /** [size]² 밝기 그림: 바탕 128에 표준편차 [sigma]인 가우스 잡음 */
    private fun noisy(size: Int, sigma: Float, seed: Long = 1): FloatArray {
        val random = Random(seed)
        return FloatArray(size * size) { 128f + random.nextGaussian().toFloat() * sigma }
    }

    private fun measure(luma: FloatArray, size: Int, valid: BooleanArray = BooleanArray(size * size) { true }): Float? {
        val responses = ArrayList<Float>()
        InpaintNoise.collect(luma, valid, size, size, responses)
        return InpaintNoise.sigma(responses)
    }

    @Test
    fun sigma_ofGaussianNoise_isItsStandardDeviation() {
        for (sigma in listOf(1f, 3f, 8f)) {
            val measured = measure(noisy(128, sigma), 128)!!
            assertEquals("σ $sigma", sigma, measured, sigma * 0.06f)
        }
    }

    @Test
    fun aFlatPicture_hasNoNoise() {
        assertEquals(0f, measure(FloatArray(64 * 64) { 90f }, 64)!!, 0f)
    }

    @Test
    fun smoothGradients_andAFewStrongEdges_doNotInflateTheNoise() {
        val size = 128
        val base = noisy(size, 2f)
        // 매끈한 기울기(필터 값 0)와 세로 경계 셋(밝기 차이 120)
        val luma = FloatArray(size * size) { i ->
            val x = i % size
            val step = if (x % 40 < 20) 0f else 120f
            base[i] + x * 0.5f + step
        }

        assertEquals(2f, measure(luma, size)!!, 0.25f)
    }

    @Test
    fun collect_usesOnlyPixelsWhoseNeighborsAreAllValid() {
        val size = 64
        val luma = noisy(size, 4f)
        // 왼쪽 반만 쓸 수 있다: 오른쪽 반을 아주 거칠게 해도 잰 값은 왼쪽 그대로다
        val valid = BooleanArray(size * size) { it % size < size / 2 }
        for (i in luma.indices) if (i % size >= size / 2) luma[i] = if (i % 2 == 0) 0f else 255f
        val responses = ArrayList<Float>()

        InpaintNoise.collect(luma, valid, size, size, responses)

        // 테두리 한 줄과 경계 열을 빼면 (64 - 2) 줄 × (32 - 2) 열
        assertEquals(62 * 30, responses.size)
        assertEquals(4f, InpaintNoise.sigma(responses)!!, 0.4f)
    }

    @Test
    fun tooFewSamples_meanUnknown() {
        assertNull(InpaintNoise.sigma(List(InpaintNoise.MIN_SAMPLES - 1) { 1f }))
    }

    @Test
    fun added_fillsTheMissingNoise_andIsCapped() {
        assertEquals(4f, InpaintNoise.added(5f, 3f), 1e-4f)
        assertEquals(0f, InpaintNoise.added(2f, 3f), 0f)
        assertEquals(InpaintNoise.MAX_SIGMA, InpaintNoise.added(40f, 0f), 0f)
    }

    @Test
    fun pick_spreadsEvenly_andKeepsFewCandidates() {
        val many = IntArray(100) { it * 3 }

        assertArrayEquals(intArrayOf(15, 45, 75, 105, 135, 165, 195, 225, 255, 285), InpaintNoise.pick(many, 10))
        assertArrayEquals(intArrayOf(7, 9), InpaintNoise.pick(intArrayOf(7, 9), 8))
    }

    @Test
    fun upscaledLuma_atScaleOne_readsThePixelsAsTheyAre() {
        val size = 4
        // 알파를 곱한 RGBA: 왼쪽 위 2×2만 반투명, 나머지는 불투명한 회색 단계
        val fill = FloatArray(size * size * 4)
        for (p in 0 until size * size) {
            val alpha = if (p % size < 2 && p / size < 2) 0.5f else 1f
            val gray = (p + 1) / 20f
            fill[p * 4] = gray * alpha
            fill[p * 4 + 1] = gray * alpha
            fill[p * 4 + 2] = gray * alpha
            fill[p * 4 + 3] = alpha
        }
        val opaque = BooleanArray(size * size)

        val luma = InpaintNoise.upscaledLuma(fill, size, left = 10, top = 20, scale = 1f, x0 = 10, y0 = 20, width = size, height = size, opaque = opaque)

        // Catmull-Rom은 픽셀 가운데에서 그 값이다(알파를 푼 밝기, 0..255)
        assertArrayEquals(FloatArray(size * size) { (it + 1) / 20f * 255f }, luma, 1e-3f)
        assertArrayEquals(BooleanArray(size * size) { !(it % size < 2 && it / size < 2) }, opaque)
    }

    @Test
    fun upscaledLuma_ofAnEnlargedFill_isSmooth() {
        // 잡음 섞인 모델 그림을 4배로 키우면 원본 해상도에서 잰 잡음이 크게 준다(그래서 모자란 만큼 더한다)
        val size = 64
        val random = Random(3)
        val fill = FloatArray(size * size * 4)
        for (p in 0 until size * size) {
            val gray = (0.5f + random.nextGaussian().toFloat() * 6f / 255f).coerceIn(0f, 1f)
            for (c in 0..2) fill[p * 4 + c] = gray
            fill[p * 4 + 3] = 1f
        }
        val width = 128
        val opaque = BooleanArray(width * width)

        val luma = InpaintNoise.upscaledLuma(fill, size, left = 0, top = 0, scale = 0.25f, x0 = 64, y0 = 64, width = width, height = width, opaque = opaque)

        assertTrue(opaque.all { it })
        val measured = measure(luma, width)!!
        assertTrue("$measured", measured < 1f)
    }
}
