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
    fun aModelThatRepaintsTheSurroundingsBrighter_doesNotDarkenTheFill() {
        val rgba = solid(100, 100, 100)
        val distance = InpaintMask.distanceToHole(disc(10f), size, size)
        // MI-GAN처럼 아는 곳은 30만큼 밝게 다시 그렸지만, 구멍은 둘레 밝기 그대로 채웠다(폴드8 시험에서 채운 곳이 어두워진 경우)
        val filled = FloatArray(size * size * 3) { if (distance[it / 3] <= 4f) 100f else 130f }

        val result = InpaintTensor.fillImage(rgba, InpaintTensor.unpremultiply(rgba), distance, 4f, filled, size)

        for (p in 0 until size * size) {
            if (distance[p] > 4f) continue
            for (c in 0 until 3) assertEquals("pixel $p", 100f, result[p * 4 + c] * 255f, 2f)
        }
    }

    @Test
    fun aFillBrighterThanTheSurroundings_isMatched_evenWhenTheModelRepaintsThemExactly() {
        val rgba = solid(100, 100, 100)
        val distance = InpaintMask.distanceToHole(disc(10f), size, size)
        // 아는 곳은 원래 그대로 다시 그렸고, 구멍만 20만큼 밝게 채웠다
        val filled = FloatArray(size * size * 3) { if (distance[it / 3] <= 4f) 120f else 100f }

        val result = InpaintTensor.fillImage(rgba, InpaintTensor.unpremultiply(rgba), distance, 4f, filled, size)

        for (p in 0 until size * size) {
            if (distance[p] > 4f) continue
            for (c in 0 until 3) assertEquals("pixel $p", 100f, result[p * 4 + c] * 255f, 2f)
        }
    }

    @Test
    fun anObjectCutByTheEdgeOfTheHole_doesNotTintTheFill() {
        // 회색 바탕 가운데 빨간 원을 왼쪽 반만 채운다(고른 곳이 물체를 자른 경우). 오른쪽 반의 빨강은 띠에 걸친다.
        val red = BooleanArray(size * size) { hypot((it % size - center).toFloat(), (it / size - center).toFloat()) <= 8f }
        val rgba = ByteArray(size * size * 4) { i ->
            when (i % 4) {
                0 -> if (red[i / 4]) 255 else 128
                1, 2 -> if (red[i / 4]) 0 else 128
                else -> 255
            }.toByte()
        }
        val painted = InpaintMask.distanceToHole(disc(12f), size, size)
        val hole = BooleanArray(size * size) { painted[it] <= 3f && it % size < center }
        val distance = InpaintMask.distanceToHole(hole, size, size)
        val straight = InpaintTensor.unpremultiply(rgba)
        // 가짜 모델처럼 아는 곳은 그대로, 구멍은 아는 곳의 평균색(빨강이 조금 섞임)
        val known = (0 until size * size).filter { !hole[it] }
        val mean = FloatArray(3) { c -> known.map { straight.rgb[it * 3 + c] }.average().toFloat() }
        val filled = FloatArray(size * size * 3) { i -> if (hole[i / 3]) mean[i % 3] else straight.rgb[i] }

        val result = InpaintTensor.fillImage(rgba, straight, distance, 0f, filled, size)

        // 잘린 곳 바로 왼쪽도 둘레의 회색이다(빨간 물체 조각의 색이 번지지 않는다)
        for ((x, y) in listOf(28 to 32, 30 to 28, 24 to 32)) {
            val p = y * size + x
            for (c in 0 until 3) assertEquals("($x, $y) channel $c", 128f, result[p * 4 + c] * 255f, 6f)
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
