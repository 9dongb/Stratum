package com.kiln.stratum.engine.skin

import com.kiln.stratum.engine.inpaint.InpaintMask
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 피부 보정이 작은 배열(한 변 [SkinRegion.MASK_SIZE] 이하)에서 쓰는 계산(결정 기록 '피부 보정'의 2b).
 * 배열은 줄마다 이어 붙인 [width]×[height]다. 큰 그림에는 쓰지 않는다(CLAUDE.md 2장).
 */
internal object SkinMaps {

    /**
     * 4방향 이웃으로 이어진 덩어리 번호(0은 바탕, 1부터). 0단계 맥 시험과 같은 이웃이다.
     * 돌려주는 값: 칸마다 번호와 덩어리 수.
     */
    fun labels(mask: BooleanArray, width: Int, height: Int): Pair<IntArray, Int> {
        val labels = IntArray(mask.size)
        val stack = IntArray(mask.size)
        var count = 0
        for (start in mask.indices) {
            if (!mask[start] || labels[start] != 0) continue
            count++
            var size = 0
            labels[start] = count
            stack[size++] = start
            while (size > 0) {
                val index = stack[--size]
                val x = index % width
                val y = index / width
                if (x > 0) size = visit(index - 1, mask, labels, count, stack, size)
                if (x + 1 < width) size = visit(index + 1, mask, labels, count, stack, size)
                if (y > 0) size = visit(index - width, mask, labels, count, stack, size)
                if (y + 1 < height) size = visit(index + width, mask, labels, count, stack, size)
            }
        }
        return labels to count
    }

    private fun visit(next: Int, mask: BooleanArray, labels: IntArray, label: Int, stack: IntArray, size: Int): Int {
        if (!mask[next] || labels[next] != 0) return size
        labels[next] = label
        stack[size] = next
        return size + 1
    }

    /**
     * 점들([points]: 점마다 x·y를 이어 붙임) 가운데 [indices]가 이루는 다각형의 안(칸 가운데가 안에 있는 칸, 홀짝 규칙).
     * 좌표는 칸 좌표(칸 (i, j)는 i..i+1 × j..j+1)다.
     */
    fun polygon(points: FloatArray, indices: IntArray, width: Int, height: Int): BooleanArray {
        val inside = BooleanArray(width * height)
        fill(points, indices, 0, 0, width, height, inside)
        return inside
    }

    /** [polygon]을 ([left], [top])에서 시작하는 [width]×[height] 창 안에서만 채운다(창 좌표로 [out]에 쓴다) */
    fun fill(points: FloatArray, indices: IntArray, left: Int, top: Int, width: Int, height: Int, out: BooleanArray) {
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in indices) {
            minY = min(minY, points[2 * i + 1])
            maxY = max(maxY, points[2 * i + 1])
        }
        val firstRow = max(0, floor(minY - top - 0.5f).toInt())
        val lastRow = min(height - 1, ceil(maxY - top - 0.5f).toInt())
        val crossings = FloatArray(indices.size)
        for (row in firstRow..lastRow) {
            val y = top + row + 0.5f
            var count = 0
            var j = indices.last()
            for (i in indices) {
                val yi = points[2 * i + 1]
                val yj = points[2 * j + 1]
                if ((yi > y) != (yj > y)) {
                    val xi = points[2 * i]
                    val xj = points[2 * j]
                    crossings[count++] = xi + (y - yi) * (xj - xi) / (yj - yi)
                }
                j = i
            }
            crossings.sort(0, count)
            var k = 0
            while (k + 1 < count) {
                // 칸 가운데 left + c + 0.5가 [crossings[k], crossings[k+1]) 안인 칸
                val from = max(0, ceil(crossings[k] - left - 0.5f).toInt())
                val to = min(width - 1, ceil(crossings[k + 1] - left - 0.5f).toInt() - 1)
                for (c in from..to) out[row * width + c] = true
                k += 2
            }
        }
    }

    /** [mask]를 [radius] 칸만큼 넓힌 것(둥글게, 정확한 거리 변환). [radius]가 0.5보다 작으면 그대로다. */
    fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Float): BooleanArray {
        if (radius < 0.5f || mask.none { it }) return mask
        val distance = InpaintMask.distanceToHole(mask, width, height)
        return BooleanArray(mask.size) { distance[it] <= radius }
    }

    /** 가우스 흐림(표준 편차 [sigma] 칸, 가장자리 값을 늘임). [sigma]가 0.3보다 작으면 그대로다. */
    fun gaussian(values: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        if (sigma < 0.3f) return values
        val radius = ceil(sigma * 3f).toInt()
        val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2f * sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val across = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * values[row + (x + k - radius).coerceIn(0, width - 1)]
                across[row + x] = sum
            }
        }
        val out = FloatArray(values.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * across[(y + k - radius).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = sum
            }
        }
        return out
    }

    /** 반지름 [radius]의 상자 평균(가장자리 값을 늘임, 0단계 맥 시험의 box와 같다) */
    fun box(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius < 1) return values
        val size = 2 * radius + 1
        val across = FloatArray(values.size)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0.0
            for (k in -radius..radius) sum += values[row + k.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                across[row + x] = (sum / size).toFloat()
                sum += values[row + (x + radius + 1).coerceAtMost(width - 1)] - values[row + (x - radius).coerceAtLeast(0)]
            }
        }
        val out = FloatArray(values.size)
        for (x in 0 until width) {
            var sum = 0.0
            for (k in -radius..radius) sum += across[k.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                out[y * width + x] = (sum / size).toFloat()
                sum += across[(y + radius + 1).coerceAtMost(height - 1) * width + x] - across[(y - radius).coerceAtLeast(0) * width + x]
            }
        }
        return out
    }

    /** 회색 길잡이 [guide]로 [source]를 거르는 가이드 필터(He et al., 반지름 [radius], [epsilon]) */
    fun guided(guide: FloatArray, source: FloatArray, width: Int, height: Int, radius: Int, epsilon: Float): FloatArray {
        val meanGuide = box(guide, width, height, radius)
        val meanSource = box(source, width, height, radius)
        val meanProduct = box(FloatArray(guide.size) { guide[it] * source[it] }, width, height, radius)
        val meanSquare = box(FloatArray(guide.size) { guide[it] * guide[it] }, width, height, radius)
        val a = FloatArray(guide.size)
        val b = FloatArray(guide.size)
        for (i in guide.indices) {
            val variance = meanSquare[i] - meanGuide[i] * meanGuide[i]
            val covariance = meanProduct[i] - meanGuide[i] * meanSource[i]
            a[i] = covariance / (variance + epsilon)
            b[i] = meanSource[i] - a[i] * meanGuide[i]
        }
        val meanA = box(a, width, height, radius)
        val meanB = box(b, width, height, radius)
        return FloatArray(guide.size) { meanA[it] * guide[it] + meanB[it] }
    }

    /** [values]를 [from]²에서 [to]²로 쌍선형으로 바꾼다(칸 가운데끼리 맞춤) */
    fun resize(values: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return values.copyOf()
        val scale = from / to.toFloat()
        val out = FloatArray(to * to)
        for (y in 0 until to) {
            val sy = ((y + 0.5f) * scale - 0.5f).coerceIn(0f, (from - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, from - 1)
            val fy = sy - y0
            for (x in 0 until to) {
                val sx = ((x + 0.5f) * scale - 0.5f).coerceIn(0f, (from - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, from - 1)
                val fx = sx - x0
                val top = values[y0 * from + x0] * (1 - fx) + values[y0 * from + x1] * fx
                val bottom = values[y1 * from + x0] * (1 - fx) + values[y1 * from + x1] * fx
                out[y * to + x] = top * (1 - fy) + bottom * fy
            }
        }
        return out
    }

    /** [values] 가운데 [chosen]인 것들의 가운데값. 하나도 없으면 [fallback]이다. */
    fun median(values: FloatArray, chosen: BooleanArray, fallback: Float = 0f): Float {
        var count = 0
        for (i in values.indices) if (chosen[i]) count++
        if (count == 0) return fallback
        val picked = FloatArray(count)
        var k = 0
        for (i in values.indices) if (chosen[i]) picked[k++] = values[i]
        picked.sort()
        return if (count % 2 == 1) picked[count / 2] else (picked[count / 2 - 1] + picked[count / 2]) / 2f
    }
}
