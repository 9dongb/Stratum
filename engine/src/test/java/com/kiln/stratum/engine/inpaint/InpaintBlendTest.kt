package com.kiln.stratum.engine.inpaint

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 채울 그림 계산(밝기·색 맞추기, 알파 잇기)과, 가짜 모델로 처음부터 끝까지 도는 경우 */
class InpaintBlendTest {

    private val size = 64
    private val center = size / 2

    /** 가운데 반지름 [radius]인 원이 구멍인 마스크 */
    private fun disc(radius: Float) = BooleanArray(size * size) { hypot((it % size - center).toFloat(), (it / size - center).toFloat()) <= radius }

    /** 모든 픽셀이 [color](알파를 곱한 R·G·B·A)인 그림 */
    private fun solid(r: Int, g: Int, b: Int, a: Int = 255) = ByteArray(size * size * 4) { i ->
        when (i % 4) {
            0 -> r
            1 -> g
            2 -> b
            else -> a
        }.toByte()
    }

    private fun uniform(value: Float) = FloatArray(size * size * 3) { value }

    @Test
    fun anEvenBrightnessOffsetOfTheFill_isMatchedToTheSurroundings() {
        val rgba = solid(100, 100, 100)
        val distance = InpaintMask.distanceToHole(disc(10f), size, size)

        // 모델이 구멍과 둘레를 고르게 20만큼 밝게 칠했다
        val result = InpaintTensor.fillImage(rgba, InpaintTensor.unpremultiply(rgba), distance, 4f, uniform(120f), size)

        for (p in 0 until size * size) {
            if (distance[p] > 4f) continue
            for (c in 0 until 3) assertEquals("pixel $p", 100f, result[p * 4 + c] * 255f, 2f)
        }
    }

    @Test
    fun outsideTheDilatedHole_theOriginalStaysExactly() {
        val rgba = ByteArray(size * size * 4) { i -> if (i % 4 == 3) 255.toByte() else (i * 7 % 256).toByte() }
        val distance = InpaintMask.distanceToHole(disc(10f), size, size)

        val result = InpaintTensor.fillImage(rgba, InpaintTensor.unpremultiply(rgba), distance, 4f, uniform(30f), size)

        for (p in 0 until size * size) {
            if (distance[p] <= 4f) continue
            for (c in 0 until 4) assertEquals((rgba[p * 4 + c].toInt() and 0xFF) / 255f, result[p * 4 + c], 0f)
        }
    }

    @Test
    fun anOpaqueSurrounding_givesAnOpaqueFill() {
        val rgba = solid(50, 60, 70)
        val distance = InpaintMask.distanceToHole(disc(12f), size, size)

        val result = InpaintTensor.fillImage(rgba, InpaintTensor.unpremultiply(rgba), distance, 4f, uniform(60f), size)

        assertEquals(1f, result[(center * size + center) * 4 + 3], 0f)
    }

    @Test
    fun aTransparentSide_isContinuedIntoTheFill() {
        // 왼쪽 반은 불투명한 회색, 오른쪽 반은 투명
        val rgba = ByteArray(size * size * 4) { i ->
            val p = i / 4
            if (p % size < center) (if (i % 4 == 3) 255 else 128).toByte() else 0
        }
        val distance = InpaintMask.distanceToHole(disc(14f), size, size)

        val result = InpaintTensor.fillImage(rgba, InpaintTensor.unpremultiply(rgba), distance, 4f, uniform(128f), size)

        val leftAlpha = result[(center * size + center - 12) * 4 + 3]
        val rightAlpha = result[(center * size + center + 12) * 4 + 3]
        assertTrue("left $leftAlpha", leftAlpha > 0.8f)
        assertTrue("right $rightAlpha", rightAlpha < 0.2f)
        // 색은 알파를 곱한 값이다
        val p = center * size + center - 12
        assertTrue(result[p * 4] <= result[p * 4 + 3] + 1e-6f)
    }

    @Test
    fun withAFakeModel_aRedBlobIsReplacedByTheGrayAroundIt() {
        // 회색 바탕 가운데 빨간 원(지울 물체), 물체를 칠한 덮개
        val objectMask = disc(9f)
        val rgba = ByteArray(size * size * 4) { i ->
            val red = objectMask[i / 4]
            when (i % 4) {
                0 -> if (red) 220 else 80
                1, 2 -> if (red) 20 else 80
                else -> 255
            }.toByte()
        }
        val coverage = ByteArray(size * size) { if (objectMask[it]) 255.toByte() else 0 }
        val model = FakeInpaintModel(size)

        val hole = InpaintMask.fillEnclosed(InpaintMask.holes(coverage), size, size)
        val distance = InpaintMask.distanceToHole(hole, size, size)
        val radius = InpaintPlan.dilation(1.0)
        val straight = InpaintTensor.unpremultiply(rgba)
        val out = model.fill(InpaintTensor.input(straight, InpaintTensor.known(straight, distance, radius), size))
        val result = InpaintTensor.fillImage(rgba, straight, distance, radius, InpaintTensor.output(out, size), size)
        val weights = InpaintTensor.writeWeights(distance, radius)

        assertEquals(1, model.calls)
        for (p in 0 until size * size) {
            if (objectMask[p]) {
                assertEquals(1f, weights[p], 0f)
                for (c in 0 until 3) assertTrue("pixel $p channel $c: ${result[p * 4 + c] * 255f}", abs(result[p * 4 + c] * 255f - 80f) < 2f)
            }
            if (distance[p] > radius + 0.5f) assertEquals(0f, weights[p], 0f)
        }
    }
}
