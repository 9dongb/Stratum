package com.kiln.stratum.engine.segment

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 분할 모델의 결과(후보 로짓)를 고른 모양으로 바꾼다(결정 기록 'AI 제거·AI 선택').
 * Kotlin 반복은 결과(256²)와 긴 변 1024px 이하 마스크에서만 한다. 상수는 폴드8에서 써 보고 고칠 수 있게 여기에 모은다.
 */
object SegmentMask {

    /** 그림 넓이에서 이만큼 넘게 덮은 후보는 물체가 아니라 그림 전체로 본다 */
    const val EVERYTHING_FRACTION = 0.9

    /** 안정도가 이 이상이면 경계가 또렷한 후보로 본다 */
    const val STABLE = 0.6

    /** 안정도를 잴 때 0에서 위아래로 벗어나는 로짓(SAM의 stability score와 같다) */
    const val STABILITY_OFFSET = 1f

    /** 마스크 넓이에서 이보다 작은 몫의 떨어진 조각은 지우고, 구멍은 메운다 */
    const val SPECK_FRACTION = 1.0 / 8192

    /**
     * 후보 로짓 [logits](후보마다 [size]², [SegmentModel.MASK_COUNT]개)에서 고른 모양. [width]×[height] 마스크(0 또는 255)이고,
     * 로짓은 마스크 전체를 고르게 덮는다(문서 전체를 늘여 맞춘 입력과 같다). 탭한 곳은 마스크 픽셀 ([tapX], [tapY])다.
     * 고를 곳이 하나도 없으면 null.
     */
    fun mask(logits: FloatArray, width: Int, height: Int, tapX: Int, tapY: Int, size: Int = SegmentModel.MASK_SIZE): ByteArray? {
        require(width > 0 && height > 0) { "Bad mask size: ${width}x$height" }
        val count = logits.size / (size * size)
        require(count > 0 && logits.size == count * size * size) { "Logits must be whole ${size}x$size planes: ${logits.size}" }
        val cellX = ((tapX + 0.5f) * size / width).toInt().coerceIn(0, size - 1)
        val cellY = ((tapY + 0.5f) * size / height).toInt().coerceIn(0, size - 1)
        val chosen = choose(logits, count, size, cellX, cellY)
        val inside = threshold(logits, chosen * size * size, size, width, height)
        val minArea = maxOf(1, (width.toLong() * height * SPECK_FRACTION).roundToInt())
        val cleaned = clean(inside, width, height, tapX.coerceIn(0, width - 1), tapY.coerceIn(0, height - 1), minArea)
        if (cleaned.none { it }) return null
        return ByteArray(cleaned.size) { if (cleaned[it]) 0xFF.toByte() else 0 }
    }

    /**
     * 후보 [count]개(각 [size]², 이어 붙인 [logits]) 가운데 쓸 것의 번호. 탭한 칸은 ([cellX], [cellY])다.
     * 1. 탭한 칸이 물체(로짓 > 0)인 후보만 본다(하나도 없으면 모두).
     * 2. 그림의 [EVERYTHING_FRACTION]보다 넓은 후보는 뺀다(모두 그렇다면 그대로 둔다).
     * 3. 안정도가 [STABLE] 이상인 후보 가운데 가장 넓은 것, 없으면 안정도가 가장 높은 것이다.
     *
     * 이유: 변환본은 후보의 점수(IoU)를 내지 않는다. 0번은 보통 물체 전체지만, 간판·탑에서는 더 큰 덩어리를,
     * 단순한 그림에서는 그림 전체를 골랐고, 그때는 경계가 흐리거나 그림 대부분을 덮었다(맥에서 실제 사진 탭 11곳·원 그림 5장으로 고름).
     */
    fun choose(logits: FloatArray, count: Int, size: Int, cellX: Int, cellY: Int): Int {
        val plane = size * size
        val tap = cellY * size + cellX
        val areas = IntArray(count) { k -> (0 until plane).count { logits[k * plane + it] > 0f } }
        var candidates = (0 until count).filter { logits[it * plane + tap] > 0f }.ifEmpty { (0 until count).toList() }
        candidates = candidates.filter { areas[it] <= plane * EVERYTHING_FRACTION }.ifEmpty { candidates }
        val stabilities = candidates.associateWith { stability(logits, it * plane, plane) }
        val stable = candidates.filter { stabilities.getValue(it) >= STABLE }
        return if (stable.isNotEmpty()) stable.maxBy { areas[it] } else candidates.maxBy { stabilities.getValue(it) }
    }

    /** 후보 하나([offset]부터 [length]칸)의 안정도: 로짓이 +[STABILITY_OFFSET]보다 큰 넓이 / -[STABILITY_OFFSET]보다 큰 넓이 */
    fun stability(logits: FloatArray, offset: Int, length: Int): Double {
        var high = 0
        var low = 0
        for (i in offset until offset + length) {
            if (logits[i] > STABILITY_OFFSET) high++
            if (logits[i] > -STABILITY_OFFSET) low++
        }
        return if (low == 0) 0.0 else high.toDouble() / low
    }

    /**
     * 후보 하나의 로짓([offset]부터 [size]²)을 [width]×[height]로 키워(쌍선형, 픽셀 가운데 기준) 0보다 큰 곳.
     * 로짓 칸은 마스크 전체를 고르게 덮는다.
     */
    fun threshold(logits: FloatArray, offset: Int, size: Int, width: Int, height: Int): BooleanArray {
        val inside = BooleanArray(width * height)
        for (y in 0 until height) {
            val v = (y + 0.5f) * size / height - 0.5f
            val y0 = floor(v).toInt()
            val ty = v - y0
            val top = offset + y0.coerceIn(0, size - 1) * size
            val bottom = offset + (y0 + 1).coerceIn(0, size - 1) * size
            for (x in 0 until width) {
                val u = (x + 0.5f) * size / width - 0.5f
                val x0 = floor(u).toInt()
                val tx = u - x0
                val left = x0.coerceIn(0, size - 1)
                val right = (x0 + 1).coerceIn(0, size - 1)
                val upper = logits[top + left] * (1f - tx) + logits[top + right] * tx
                val lower = logits[bottom + left] * (1f - tx) + logits[bottom + right] * tx
                inside[y * width + x] = upper * (1f - ty) + lower * ty > 0f
            }
        }
        return inside
    }

    /**
     * [mask]에서 떨어진 작은 조각(8방향으로 이어 봄)은 지우고, 가장자리에 닿지 않은 작은 구멍(4방향)은 메운 새 마스크.
     * 작다는 것은 [minArea] 픽셀보다 작은 것이다. ([keepX], [keepY])가 든 조각은 작아도 남긴다(탭한 물체가 작을 때).
     */
    fun clean(mask: BooleanArray, width: Int, height: Int, keepX: Int, keepY: Int, minArea: Int): BooleanArray {
        require(mask.size == width * height) { "Mask must be ${width}x$height" }
        val result = mask.copyOf()
        val queue = IntArray(width * height)
        val keep = keepY * width + keepX
        // 작은 조각 지우기
        var visited = BooleanArray(result.size)
        for (start in result.indices) {
            if (!result[start] || visited[start]) continue
            val end = flood(result, true, start, width, height, eightWay = true, visited, queue)
            val containsKeep = (0 until end).any { queue[it] == keep }
            if (end < minArea && !containsKeep) for (i in 0 until end) result[queue[i]] = false
        }
        // 작은 구멍 메우기
        visited = BooleanArray(result.size)
        for (start in result.indices) {
            if (result[start] || visited[start]) continue
            val end = flood(result, false, start, width, height, eightWay = false, visited, queue)
            if (end >= minArea) continue
            val touchesEdge = (0 until end).any {
                val p = queue[it]
                val x = p % width
                val y = p / width
                x == 0 || y == 0 || x == width - 1 || y == height - 1
            }
            if (!touchesEdge) for (i in 0 until end) result[queue[i]] = true
        }
        return result
    }

    /** [start]부터 값이 [value]인 이어진 픽셀을 [queue] 앞에 모으고 그 수를 돌려준다(본 픽셀은 [visited]에 적는다) */
    private fun flood(
        mask: BooleanArray,
        value: Boolean,
        start: Int,
        width: Int,
        height: Int,
        eightWay: Boolean,
        visited: BooleanArray,
        queue: IntArray
    ): Int {
        var head = 0
        var end = 0
        queue[end++] = start
        visited[start] = true
        while (head < end) {
            val p = queue[head++]
            val x = p % width
            val y = p / width
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (!eightWay && dx != 0 && dy != 0) continue
                    val nx = x + dx
                    if (nx < 0 || nx >= width) continue
                    val q = ny * width + nx
                    if (visited[q] || mask[q] != value) continue
                    visited[q] = true
                    queue[end++] = q
                }
            }
        }
        return end
    }
}
