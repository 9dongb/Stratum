package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.raster.PixelRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 줄인 그림 위의 흑백 마스크 계산(결정 기록 'AI 제거·AI 선택').
 * 마스크는 줄마다 이어 붙인 칸 배열이다. 긴 변 [InpaintPlan.PLAN_MAX_EDGE] 이하의 계획용 마스크나 모델 크기(512²)에서만 부른다.
 * 원본 크기에서 픽셀을 하나씩 돌지 않는다(CLAUDE.md 2장).
 */
object InpaintMask {

    /** 조금이라도 덮인 칸(덮인 정도 > 0)을 구멍으로 본다. 경계를 넉넉히 잡아 물체 가장자리가 남지 않게 한다. */
    fun holes(coverage: ByteArray): BooleanArray = BooleanArray(coverage.size) { coverage[it].toInt() != 0 }

    /**
     * 구멍에 둘러싸여 그림 가장자리와 이어지지 않는 빈 칸도 구멍으로 메운다(고리처럼 둘러 칠하면 안쪽까지 지운다).
     * 빈 칸끼리는 위아래·좌우로만 잇는다. 그래서 대각선으로 이어 칠한 고리도 새지 않는다.
     */
    fun fillEnclosed(hole: BooleanArray, width: Int, height: Int): BooleanArray {
        requireSize(hole.size, width, height)
        // 가장자리에서 시작해 빈 칸을 따라 닿는 곳을 표시한다
        val outside = BooleanArray(hole.size)
        val stack = IntArray(hole.size)
        var top = 0
        fun visit(index: Int) {
            if (!hole[index] && !outside[index]) {
                outside[index] = true
                stack[top++] = index
            }
        }
        for (x in 0 until width) {
            visit(x)
            visit((height - 1) * width + x)
        }
        for (y in 0 until height) {
            visit(y * width)
            visit(y * width + width - 1)
        }
        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width
            if (x > 0) visit(index - 1)
            if (x < width - 1) visit(index + 1)
            if (y > 0) visit(index - width)
            if (y < height - 1) visit(index + width)
        }
        return BooleanArray(hole.size) { hole[it] || !outside[it] }
    }

    /**
     * 칸마다 가장 가까운 구멍 칸까지의 거리(칸 가운데끼리, 유클리드). 구멍은 0이고, 구멍이 하나도 없으면 모두 무한대다.
     * 정확한 거리 변환(Felzenszwalb·Huttenlocher, 세로 한 번·가로 한 번)이라 칸 수에 비례하는 시간에 끝난다.
     */
    fun distanceToHole(hole: BooleanArray, width: Int, height: Int): FloatArray {
        requireSize(hole.size, width, height)
        val squared = DoubleArray(hole.size) { if (hole[it]) 0.0 else FAR }
        val longest = max(width, height)
        val line = DoubleArray(longest)
        val result = DoubleArray(longest)
        val parabolas = IntArray(longest)
        val bounds = DoubleArray(longest + 1)
        for (x in 0 until width) {
            for (y in 0 until height) line[y] = squared[y * width + x]
            transform(line, height, result, parabolas, bounds)
            for (y in 0 until height) squared[y * width + x] = result[y]
        }
        for (y in 0 until height) {
            System.arraycopy(squared, y * width, line, 0, width)
            transform(line, width, result, parabolas, bounds)
            System.arraycopy(result, 0, squared, y * width, width)
        }
        return FloatArray(hole.size) { if (squared[it] >= FAR) Float.POSITIVE_INFINITY else sqrt(squared[it]).toFloat() }
    }

    /** [distance](구멍까지의 거리)가 [radius] 칸 이하인 곳. 구멍을 [radius]만큼 넓힌 것이다. */
    fun dilate(distance: FloatArray, radius: Float): BooleanArray = BooleanArray(distance.size) { distance[it] <= radius }

    /** 8방향으로 이어진 구멍 덩어리들(범위와 칸 수). 위에서 아래, 왼쪽에서 오른쪽으로 처음 만난 순서다. */
    fun components(hole: BooleanArray, width: Int, height: Int): List<HoleRegion> {
        requireSize(hole.size, width, height)
        val seen = BooleanArray(hole.size)
        val stack = IntArray(hole.size)
        val regions = ArrayList<HoleRegion>()
        for (start in hole.indices) {
            if (!hole[start] || seen[start]) continue
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var cells = 0
            var size = 0
            seen[start] = true
            stack[size++] = start
            while (size > 0) {
                val index = stack[--size]
                val x = index % width
                val y = index / width
                cells++
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val next = ny * width + nx
                        if (hole[next] && !seen[next]) {
                            seen[next] = true
                            stack[size++] = next
                        }
                    }
                }
            }
            regions += HoleRegion(PixelRect(left, top, right, bottom), cells)
        }
        return regions
    }

    /**
     * 범위끼리 [gap] 칸 안으로 가까운 덩어리를 하나로 묶는다(범위는 합치고 칸 수는 더한다).
     * 묶여서 커진 범위가 다른 덩어리에 가까워지면 그것도 묶는다.
     */
    fun group(regions: List<HoleRegion>, gap: Int): List<HoleRegion> {
        require(gap >= 0) { "Gap must not be negative: $gap" }
        val groups = regions.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            search@ for (i in groups.indices) {
                for (j in i + 1 until groups.size) {
                    if (groups[i].isNear(groups[j], gap)) {
                        groups[i] = groups[i].merge(groups[j])
                        groups.removeAt(j)
                        merged = true
                        break@search
                    }
                }
            }
        }
        return groups
    }

    /**
     * 한 줄의 제곱 거리 변환. [values]의 처음 [count]칸(제곱 거리, 멀면 [FAR])을 받아 [out]에 쓴다.
     * 아는 칸(구멍에서 온 값)마다 포물선을 세우고, 그 아래 껍질을 따라 가장 가까운 것을 고른다.
     */
    private fun transform(values: DoubleArray, count: Int, out: DoubleArray, parabolas: IntArray, bounds: DoubleArray) {
        var k = -1
        for (q in 0 until count) {
            if (values[q] >= FAR) continue
            if (k < 0) {
                k = 0
                parabolas[0] = q
                bounds[0] = Double.NEGATIVE_INFINITY
                bounds[1] = Double.POSITIVE_INFINITY
                continue
            }
            var crossing = intersection(values, parabolas[k], q)
            while (crossing <= bounds[k]) {
                k--
                crossing = intersection(values, parabolas[k], q)
            }
            k++
            parabolas[k] = q
            bounds[k] = crossing
            bounds[k + 1] = Double.POSITIVE_INFINITY
        }
        if (k < 0) {
            out.fill(FAR, 0, count)
            return
        }
        var j = 0
        for (q in 0 until count) {
            while (bounds[j + 1] < q) j++
            val p = parabolas[j]
            val d = (q - p).toDouble()
            out[q] = d * d + values[p]
        }
    }

    /** 칸 [p]와 [q]에 세운 포물선이 만나는 자리 */
    private fun intersection(values: DoubleArray, p: Int, q: Int): Double =
        ((values[q] + q.toDouble() * q) - (values[p] + p.toDouble() * p)) / (2.0 * (q - p))

    private fun requireSize(size: Int, width: Int, height: Int) {
        require(width > 0 && height > 0 && size == width * height) { "Mask of $size cells is not ${width}x$height" }
    }

    /** 구멍이 없는 칸의 제곱 거리(무한대 대신) */
    private const val FAR = 1e30
}

/** 구멍 덩어리: 범위(칸)와 칸 수 */
data class HoleRegion(val bounds: PixelRect, val cells: Int) {

    /** 범위끼리 가로·세로 모두 [gap] 칸 안으로 가까운지(겹치면 가깝다) */
    fun isNear(other: HoleRegion, gap: Int): Boolean {
        val apartX = max(bounds.left, other.bounds.left) - min(bounds.right, other.bounds.right)
        val apartY = max(bounds.top, other.bounds.top) - min(bounds.bottom, other.bounds.bottom)
        return apartX <= gap && apartY <= gap
    }

    /** 범위를 합치고 칸 수를 더한 덩어리 */
    fun merge(other: HoleRegion): HoleRegion = HoleRegion(
        PixelRect(
            min(bounds.left, other.bounds.left),
            min(bounds.top, other.bounds.top),
            max(bounds.right, other.bounds.right),
            max(bounds.bottom, other.bounds.bottom)
        ),
        cells + other.cells
    )
}
