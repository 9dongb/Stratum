package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.raster.PixelRect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * 질감 향상의 짝 찾기(결정 기록 'AI 제거·AI 선택'의 '질감 향상'). 큰 사진의 큰 구멍은 512px로 채운 그림을 몇 배 키워 넣어 고운 결이 없다.
 * 모델 크기에서, 채운 곳에 닿는 조각([CELL]칸, [STRIDE]칸 간격으로 반씩 겹침)마다 둘레의 다 아는 조각 가운데 채운 색이 가장 비슷한 것을 고른다.
 * 원본 해상도에서 그 조각의 결(원본 − 모델 크기에서 키운 원본)을 옮겨 붙이는 일은 GPU가 한다(`TextureTransfer`).
 *
 * 짝은 두 번에 고른다: 2×2 평균으로 줄인 4×4×3 값으로 가까운 후보 [TOP]개를 고른 뒤, 그 가운데 8×8×3 값이 가장 가까운 것이다.
 * 맥에서 실제 사진 5곳으로 견주어, 모두 8×8로 견준 것과 84~99% 같은 조각을 골랐다(거리 차이 2% 안).
 * Kotlin 반복은 모델 크기 배열(512²)에서만 한다.
 *
 * 조각의 자리는 그물 칸 번호로 다룬다. 칸 n의 가운데는 모델 px 경계 ([n] + 1) × [STRIDE]이고, 조각은 가운데에서 양쪽으로 [CELL] / 2칸이다.
 */
object InpaintTexture {

    /** 결을 붙이는 창의 최소 배율(모델 1px이 덮는 문서 px). 이보다 작으면 채운 그림이 원본 해상도와 거의 같아 더할 결이 적다. */
    const val MIN_SCALE = 1.5

    /** 조각 한 변(모델 px) */
    const val CELL = 8

    /** 조각 사이 간격(모델 px). 반씩 겹쳐서 픽셀마다 조각 넷이 덮는다. */
    const val STRIDE = CELL / 2

    /** 후보 조각의 가운데가 넓힌 구멍에서 떨어질 수 있는 거리(모델 px) */
    const val NEAR = 96f

    /** 후보 조각과 둘레 1칸의 모든 칸이 넓힌 구멍에서 이보다 멀어야 한다(모델 px). 지운 물체의 번진 가장자리를 옮기지 않게 한다. */
    const val KNOWN_GAP = 2f

    /** 후보 수의 상한. 많으면 같은 간격으로 고른다. */
    const val MAX_CANDIDATES = 512

    /** 거친 값으로 먼저 고르는 후보 수 */
    const val TOP = 4

    /** 가져올 조각 판(원본 픽셀, 8888)의 최대 크기(바이트). 배율이 아주 크면 후보를 줄여 맞춘다. */
    const val ATLAS_BYTES = 32L * 1024 * 1024

    /** 조각 판에서 조각마다 두르는 여백(문서 px). 반올림으로 한 픽셀 어긋나도 옆 조각을 읽지 않는다. */
    const val BLOCK_MARGIN = 2

    /** 후보 조각으로 쓰는 불투명도(알파 0..255) */
    private const val OPAQUE = 255f

    /** 거친 값의 한 변(칸): 조각을 2×2 평균으로 줄인 것 */
    private const val COARSE = CELL / 2

    /** 짝 표 한 칸에 담을 수 있는 그물 자리와 조각 번호의 범위([pack]) */
    private const val MAX_GRID = 128
    private const val MAX_SLOTS = 512

    /** 배율 [scale]인 창에 결을 붙이는지 */
    fun applies(scale: Double): Boolean = scale >= MIN_SCALE

    /** 배율 [scale]에서 조각 판에 담는 조각 한 변(문서 px): 조각이 덮는 ⌈[CELL] × scale⌉에 양쪽 여백 */
    fun blockSize(scale: Double): Int = ceil(CELL * scale).toInt() + 2 * BLOCK_MARGIN

    /** 배율 [scale]에서 조각 판이 [ATLAS_BYTES] 안에 들도록 한 후보 수의 상한 */
    fun maxCandidates(scale: Double): Int {
        val block = blockSize(scale).toLong()
        return (ATLAS_BYTES / (block * block * Int.SIZE_BYTES)).toInt().coerceIn(1, MAX_CANDIDATES)
    }

    /** 모델 크기 [size]의 그물 칸 수(한 변) */
    fun gridSize(size: Int): Int = size / STRIDE - 1

    /** 그물 칸 [n]의 가운데(모델 px 경계) */
    fun center(n: Int): Int = (n + 1) * STRIDE

    /**
     * 그물 칸 [source]의 조각을 조각 판에 담을 때 왼쪽(위) 끝의 문서 자리. 창 왼쪽 [windowLeft], 배율 [scale]이다.
     * 조각 가운데(문서) − [CELL] × scale / 2를 내림하고 여백을 뺀다. 셰이더가 같은 식으로 읽을 자리를 구한다
     * (창 한 변 / 512라 배율이 2의 거듭제곱 분모여서 GPU의 float도 같은 값이 나온다).
     */
    fun blockStart(windowLeft: Int, source: Int, scale: Double): Int =
        floor(windowLeft + center(source) * scale - CELL * scale / 2).toInt() - BLOCK_MARGIN

    /** 조각 [count]개를 담는 조각 판의 열 수 */
    fun columns(count: Int): Int {
        require(count > 0) { "Count must be positive: $count" }
        return ceil(sqrt(count.toDouble())).toInt()
    }

    /**
     * 후보 조각의 그물 칸(줄 순서 번호). 조각과 둘레 1칸의 모든 칸이 넓힌 구멍에서 [KNOWN_GAP]보다 멀고 불투명하며([alpha] 255),
     * 조각이 [inside](모델 px, 문서 안) 안에 있고, 가운데 칸이 넓힌 구멍에서 [NEAR] 안인 조각이다. [max]개보다 많으면 같은 간격으로 고른다.
     */
    fun candidates(holeDistance: FloatArray, alpha: FloatArray, size: Int, inside: PixelRect, max: Int): IntArray {
        require(holeDistance.size == size * size && alpha.size == size * size) { "Maps must be ${size}x$size" }
        require(max > 0) { "Max must be positive: $max" }
        val grid = gridSize(size)
        val half = CELL / 2
        val found = ArrayList<Int>()
        for (gy in 0 until grid) {
            val cy = center(gy)
            if (cy - half < inside.top || cy + half > inside.bottom) continue
            for (gx in 0 until grid) {
                val cx = center(gx)
                if (cx - half < inside.left || cx + half > inside.right) continue
                if (holeDistance[cy * size + cx] > NEAR) continue
                if (known(holeDistance, alpha, size, cx - half - 1, cy - half - 1, CELL + 2)) found += gy * grid + gx
            }
        }
        return spread(found.toIntArray(), max)
    }

    /** ([x0], [y0])부터 한 변 [side]칸이 모두 그림 안이고, 구멍에서 [KNOWN_GAP]보다 멀고, 불투명한지 */
    private fun known(holeDistance: FloatArray, alpha: FloatArray, size: Int, x0: Int, y0: Int, side: Int): Boolean {
        if (x0 < 0 || y0 < 0 || x0 + side > size || y0 + side > size) return false
        for (y in y0 until y0 + side) {
            for (x in x0 until x0 + side) {
                val p = y * size + x
                if (holeDistance[p] <= KNOWN_GAP || alpha[p] < OPAQUE) return false
            }
        }
        return true
    }

    /** 채운 곳에 닿는 조각의 그물 칸(줄 순서 번호): 조각 [CELL]×[CELL] 안에 [hole](넓힌 구멍) 칸이 하나라도 있으면 */
    fun targets(hole: BooleanArray, size: Int): IntArray {
        require(hole.size == size * size) { "Hole must be ${size}x$size" }
        val grid = gridSize(size)
        val half = CELL / 2
        val found = ArrayList<Int>()
        for (gy in 0 until grid) {
            val cy = center(gy)
            for (gx in 0 until grid) {
                val cx = center(gx)
                var touches = false
                for (y in cy - half until cy + half) {
                    for (x in cx - half until cx + half) {
                        if (hole[y * size + x]) {
                            touches = true
                            break
                        }
                    }
                    if (touches) break
                }
                if (touches) found += gy * grid + gx
            }
        }
        return found.toIntArray()
    }

    /**
     * [targets](그물 칸)마다 [candidates](그물 칸) 가운데 가장 비슷한 것의 자리(후보 목록의 번호).
     * 대상은 채운 그림 [filled], 후보는 원래 그림 [known]에서 읽는다(둘 다 알파를 푼 R·G·B 0..255, 픽셀마다 이어 붙임, [size]²).
     * 거친 값(4×4×3)으로 가까운 [TOP]개를 고르고, 그 가운데 8×8×3 값의 제곱 거리가 가장 작은 것이다(같으면 앞 번호).
     */
    fun match(known: FloatArray, filled: FloatArray, size: Int, candidates: IntArray, targets: IntArray): IntArray {
        require(known.size == size * size * 3 && filled.size == size * size * 3) { "Pictures must be ${size}x$size RGB" }
        require(candidates.isNotEmpty()) { "No candidates" }
        val grid = gridSize(size)
        val fine = CELL * CELL * 3
        val coarse = COARSE * COARSE * 3
        val candidateFine = FloatArray(candidates.size * fine)
        val candidateCoarse = FloatArray(candidates.size * coarse)
        for (i in candidates.indices) {
            describe(known, size, grid, candidates[i], candidateFine, i * fine, candidateCoarse, i * coarse)
        }
        val targetFine = FloatArray(fine)
        val targetCoarse = FloatArray(coarse)
        val top = minOf(TOP, candidates.size)
        val shortDistance = FloatArray(top)
        val shortIndex = IntArray(top)
        return IntArray(targets.size) { t ->
            describe(filled, size, grid, targets[t], targetFine, 0, targetCoarse, 0)
            shortDistance.fill(Float.POSITIVE_INFINITY)
            shortIndex.fill(-1)
            for (i in candidates.indices) {
                val d = distance(targetCoarse, 0, candidateCoarse, i * coarse, coarse)
                if (d >= shortDistance[top - 1]) continue
                // 짧은 목록에 거리 순으로 끼운다(같은 거리면 앞 번호가 앞)
                var k = top - 1
                while (k > 0 && shortDistance[k - 1] > d) {
                    shortDistance[k] = shortDistance[k - 1]
                    shortIndex[k] = shortIndex[k - 1]
                    k--
                }
                shortDistance[k] = d
                shortIndex[k] = i
            }
            var best = shortIndex[0]
            var bestDistance = Float.POSITIVE_INFINITY
            for (k in 0 until top) {
                val i = shortIndex[k]
                if (i < 0) continue
                val d = distance(targetFine, 0, candidateFine, i * fine, fine)
                if (d < bestDistance || (d == bestDistance && i < best)) {
                    best = i
                    bestDistance = d
                }
            }
            best
        }
    }

    /** 그물 칸 [cell]의 조각 값: 8×8×3([fine]의 [fineAt]부터)과 2×2 평균 4×4×3([coarse]의 [coarseAt]부터) */
    private fun describe(picture: FloatArray, size: Int, grid: Int, cell: Int, fine: FloatArray, fineAt: Int, coarse: FloatArray, coarseAt: Int) {
        val x0 = center(cell % grid) - CELL / 2
        val y0 = center(cell / grid) - CELL / 2
        var f = fineAt
        for (y in 0 until CELL) {
            val row = (y0 + y) * size
            for (x in 0 until CELL) {
                val p = (row + x0 + x) * 3
                fine[f] = picture[p]
                fine[f + 1] = picture[p + 1]
                fine[f + 2] = picture[p + 2]
                f += 3
            }
        }
        for (cy in 0 until COARSE) {
            for (cx in 0 until COARSE) {
                val c = coarseAt + (cy * COARSE + cx) * 3
                for (channel in 0..2) {
                    var sum = 0f
                    for (dy in 0..1) for (dx in 0..1) sum += fine[fineAt + ((cy * 2 + dy) * CELL + cx * 2 + dx) * 3 + channel]
                    coarse[c + channel] = sum / 4f
                }
            }
        }
    }

    private fun distance(a: FloatArray, aAt: Int, b: FloatArray, bAt: Int, length: Int): Float {
        var sum = 0f
        for (i in 0 until length) {
            val d = a[aAt + i] - b[bAt + i]
            sum += d * d
        }
        return sum
    }

    /**
     * 창 하나의 짝 찾기([candidates] → [targets] → [match]). [hole]은 넓힌 구멍, [holeDistance]는 그 구멍까지 거리,
     * [alpha]는 원래 그림의 알파(0..255), [known]·[filled]는 [match]와 같다. 대상이나 후보가 없으면 null.
     */
    fun plan(
        known: FloatArray,
        filled: FloatArray,
        alpha: FloatArray,
        holeDistance: FloatArray,
        hole: BooleanArray,
        size: Int,
        inside: PixelRect,
        maxCandidates: Int
    ): TextureMatch? {
        val targets = targets(hole, size)
        if (targets.isEmpty()) return null
        val candidates = candidates(holeDistance, alpha, size, inside, maxCandidates)
        if (candidates.isEmpty()) return null
        val picks = match(known, filled, size, candidates, targets)
        val grid = gridSize(size)
        val slots = IntArray(grid * grid) { -1 }
        val slotOf = HashMap<Int, Int>()
        val sources = ArrayList<Int>()
        for (t in targets.indices) {
            val candidate = candidates[picks[t]]
            slots[targets[t]] = slotOf.getOrPut(candidate) {
                sources += candidate
                sources.size - 1
            }
        }
        return TextureMatch(grid, slots, sources.toIntArray())
    }

    /**
     * 짝 표 한 칸의 색(ARGB, 알파 255). R = 가져올 칸 x(7비트) + 조각 번호의 9번째 비트, G = 가져올 칸 y, B = 조각 번호 아래 8비트.
     * 셰이더가 같은 규칙으로 푼다([unpack]). 알파가 0인 칸(색 0)은 짝이 없는 칸이다.
     */
    fun pack(sourceX: Int, sourceY: Int, slot: Int): Int {
        require(sourceX in 0 until MAX_GRID && sourceY in 0 until MAX_GRID) { "Source out of range: $sourceX, $sourceY" }
        require(slot in 0 until MAX_SLOTS) { "Slot out of range: $slot" }
        val red = sourceX or ((slot shr 8) shl 7)
        return (0xFF shl 24) or (red shl 16) or (sourceY shl 8) or (slot and 0xFF)
    }

    /** [pack]의 반대: (가져올 칸 x, y, 조각 번호). 셰이더의 풀기와 같은 계산이다(테스트용). */
    fun unpack(color: Int): Triple<Int, Int, Int> {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return Triple(red % 128, green, (red / 128) * 256 + blue)
    }

    /** [values] 가운데 [count]개를 같은 간격으로 고른다(줄 순서라 위아래로 고르게 퍼진다). 적으면 모두다. */
    private fun spread(values: IntArray, count: Int): IntArray {
        val n = values.size
        if (n <= count) return values
        return IntArray(count) { values[((it + 0.5) * n / count).toInt()] }
    }
}

/**
 * 창 하나의 짝 찾기 결과([InpaintTexture.plan]). [slots]는 그물 칸(줄 순서, [grid]²)마다 쓸 조각 번호이고 짝이 없으면 -1이다.
 * [sources]는 조각 번호마다 가져올 그물 칸(줄 순서 번호)이다.
 */
class TextureMatch(val grid: Int, val slots: IntArray, val sources: IntArray) {
    init {
        require(slots.size == grid * grid) { "Slots must be ${grid}x$grid" }
    }
}
