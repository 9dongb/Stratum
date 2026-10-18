package com.kiln.stratum.engine.retouch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 칠한 곳에서 가져올 곳으로 옮기는 양(픽셀). 가져올 곳 = 칠한 곳 + ([dx], [dy]). */
data class Shift(val dx: Int, val dy: Int)

/**
 * 스팟 복구 브러시가 가져올 곳 찾기. 칠한 곳 둘레의 띠가 가장 비슷한 이웃 자리를 고른다(포토샵 스팟 복구의 '근접 일치'와 같은 생각).
 *
 * - 후보는 16방향이고, 방향마다 브러시 반지름의 몇 배와 칠한 곳을 비켜 가는 거리 몇 가지다([maxReach]보다 먼 후보는 보지 않는다).
 * - 방향마다 가장 좋은 후보를 둘레로 조금씩(4·2·1픽셀) 옮겨 보며 다듬는다. 비스듬한 줄·경계는 방향 후보와 조금 어긋나
 *   처음에는 점수가 나쁘다가, 다듬으면 픽셀 단위로 맞아 가장 좋아진다.
 * - 옮긴 칠한 곳이 그림 밖으로 나가거나 칠한 곳과 겹치는(2%보다 많이) 후보는 뺀다. 얼룩을 다시 가져오지 않게 한다.
 * - 견주는 값: 둘레 띠끼리의 색 차이, 가져올 안쪽이 그 둘레 평균과 다른 정도(다른 얼룩·경계를 피함), 거리(가까운 곳을 조금 더).
 *   칠한 곳·둘레가 크면 고르게 건너뛴 픽셀([MAX_SAMPLES]개까지)로 견준다.
 *
 * 줄인 그림(긴 변 [MAX_EDGE] 이하)에서만 돈다. 원본 크기에서 픽셀을 하나씩 돌지 않는다(결정 기록 '리터칭').
 */
object SpotSearch {

    /** 후보를 견주는 그림의 긴 변 최대 픽셀 */
    const val MAX_EDGE = 1024

    /** 후보 방향 수 */
    private const val DIRECTIONS = 16

    /** 견주는 둘레 띠의 폭(브러시 반지름에 대한 몫) */
    private const val BAND = 0.5f

    /** 반지름의 몇 배 떨어진 곳을 볼지 */
    private val RADIUS_STEPS = floatArrayOf(2.5f, 3.5f, 5f)

    /** 칠한 곳을 비켜 가는 거리의 몇 배를 볼지 */
    private val CLEAR_STEPS = floatArrayOf(1f, 1.5f)

    /** 칠한 곳과 이만큼(몫)보다 많이 겹치는 후보는 뺀다 */
    private const val MAX_OVERLAP = 0.02f

    /** 가져올 안쪽이 둘레 평균과 다른 정도의 무게 */
    private const val INSIDE_WEIGHT = 0.5f

    /** 거리 무게: 반지름만큼 멀어질 때마다 점수가 이만큼(몫) 나빠진다 */
    private const val DISTANCE_WEIGHT = 0.02f

    /** 후보를 다듬을 때 옮겨 보는 걸음(픽셀). 큰 걸음부터 줄인다. */
    private val REFINE_STEPS = intArrayOf(4, 2, 1)

    /** 후보 하나를 다듬을 때 견줄 자리 수의 한도 */
    private const val MAX_REFINEMENTS = 32

    /** 견줄 때 쓰는 칠한 곳·둘레 픽셀 수의 한도(넘으면 고르게 건너뛴다) */
    const val MAX_SAMPLES = 4096

    /**
     * 후보가 갈 수 있는 가장 먼 거리: 반지름의 5배, 또는 칠한 곳의 가장 긴 폭([extent])을 비켜 가는 거리(1.5배 + 띠) 중 큰 것.
     * 경계를 따라 길게 칠한 획은 그 경계를 따라 획 길이만큼 옮긴 곳이 가장 잘 맞으므로 그만큼 멀리 본다.
     */
    fun maxReach(radius: Float, extent: Float): Float =
        maxOf(RADIUS_STEPS.last() * radius, CLEAR_STEPS.last() * extent + BAND * radius)

    /**
     * 가장 알맞은 옮길 양. 후보가 하나도 없으면 null이다.
     *
     * @param pixels 그림 픽셀(ARGB, 줄마다 이어 붙임). 문서 밖은 들어 있지 않다(그림 = 문서의 한 영역).
     * @param painted 칠한 곳(픽셀마다)
     * @param radius 브러시 반지름(이 그림의 픽셀)
     * @param reach 이보다 먼 후보는 보지 않는다(이 그림의 픽셀, [maxReach])
     */
    fun find(pixels: IntArray, painted: BooleanArray, width: Int, height: Int, radius: Float, reach: Float = Float.MAX_VALUE): Shift? {
        val spot = Spot.of(pixels, painted, width, height, radius) ?: return null
        val unit = spot.unit
        // 후보마다 점수(작을수록 좋다). 같은 자리는 한 번만 잰다(쓸 수 없는 자리는 null).
        val scores = HashMap<Shift, Double?>()
        fun scoreOf(shift: Shift): Double? = scores.getOrPut(shift) {
            val distance = hypot(shift.dx.toDouble(), shift.dy.toDouble())
            if ((shift.dx == 0 && shift.dy == 0) || distance > reach) null
            else spot.score(shift)?.let { it * (1.0 + DISTANCE_WEIGHT * distance / unit) }
        }
        // 방향마다 가장 좋은 후보에서 다듬기를 시작한다
        val starts = ArrayList<Pair<Shift, Double>>()
        for (direction in 0 until DIRECTIONS) {
            val angle = direction * 2.0 * PI / DIRECTIONS
            val cos = cos(angle)
            val sin = sin(angle)
            // 이 방향으로 칠한 곳의 사각형을 비켜 가는 거리
            val clear = abs(cos) * spot.extentX + abs(sin) * spot.extentY + spot.band
            val distances = RADIUS_STEPS.map { it * unit.toDouble() } + CLEAR_STEPS.map { it * clear }
            distances
                .mapNotNull { distance -> Shift((cos * distance).roundToInt(), (sin * distance).roundToInt()).let { shift -> scoreOf(shift)?.let { shift to it } } }
                .minByOrNull { it.second }
                ?.let { starts += it }
        }
        var best: Shift? = null
        var bestScore = Double.MAX_VALUE
        for ((start, startScore) in starts) {
            var current = start
            var currentScore = startScore
            var evaluations = 0
            for (step in REFINE_STEPS) {
                var moved = true
                while (moved && evaluations < MAX_REFINEMENTS) {
                    moved = false
                    val center = current
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            evaluations++
                            val shift = Shift(center.dx + dx * step, center.dy + dy * step)
                            val score = scoreOf(shift) ?: continue
                            if (score < currentScore) {
                                current = shift
                                currentScore = score
                                moved = true
                            }
                        }
                    }
                }
            }
            if (currentScore < bestScore) {
                best = current
                bestScore = currentScore
            }
        }
        return best
    }

    /** 칠한 곳 바깥으로 폭 [band]의 띠(칠한 곳과 가로·세로 [band] 픽셀 안) */
    internal fun ringOf(painted: BooleanArray, width: Int, height: Int, band: Int): List<Int> {
        // 가로로 [band] 안에 칠한 곳이 있는지를 줄마다 구한 뒤, 그것을 세로로 한 번 더 넓힌다(두 번 다 앞 합으로 센다)
        val near = BooleanArray(width * height)
        val prefix = IntArray(maxOf(width, height) + 1)
        for (y in 0 until height) {
            for (x in 0 until width) prefix[x + 1] = prefix[x] + if (painted[y * width + x]) 1 else 0
            for (x in 0 until width) {
                val from = (x - band).coerceAtLeast(0)
                val to = (x + band + 1).coerceAtMost(width)
                near[y * width + x] = prefix[to] - prefix[from] > 0
            }
        }
        val ring = ArrayList<Int>()
        for (x in 0 until width) {
            for (y in 0 until height) prefix[y + 1] = prefix[y] + if (near[y * width + x]) 1 else 0
            for (y in 0 until height) {
                val from = (y - band).coerceAtLeast(0)
                val to = (y + band + 1).coerceAtMost(height)
                val p = y * width + x
                if (!painted[p] && prefix[to] - prefix[from] > 0) ring += p
            }
        }
        return ring
    }

    /** [points]에서 고르게 건너뛰어 [limit]개까지 고른 자리 */
    private fun sample(points: List<Int>, limit: Int): IntArray {
        if (points.size <= limit) return points.toIntArray()
        val step = points.size.toDouble() / limit
        return IntArray(limit) { points[(it * step).toInt()] }
    }

    /**
     * 칠한 곳 하나: 견줄 칠한 곳 픽셀([inside])과 둘레 띠 픽셀([ring], 고르게 건너뛴 것), 둘레 평균, 칠한 곳의 사각형.
     * [score]가 후보 하나를 잰다.
     */
    private class Spot(
        val pixels: IntArray,
        val painted: BooleanArray,
        val width: Int,
        val height: Int,
        val unit: Float,
        val band: Int,
        val inside: IntArray,
        val ring: IntArray,
        val mean: IntArray,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val extentX: Int
            get() = maxX - minX + 1

        val extentY: Int
            get() = maxY - minY + 1

        /** [shift] 후보의 점수(작을수록 좋다). 쓸 수 없는 후보면 null. */
        fun score(shift: Shift): Double? {
            // 옮긴 칠한 곳이 그림 밖으로 나가면 쓸 수 없다(사각형으로 바로 본다)
            if (minX + shift.dx < 0 || minY + shift.dy < 0 || maxX + shift.dx >= width || maxY + shift.dy >= height) return null
            var overlap = 0
            var insideSum = 0L
            for (p in inside) {
                val q = (p / width + shift.dy) * width + p % width + shift.dx
                if (painted[q]) overlap++
                insideSum += distanceTo(pixels[q], mean)
            }
            if (overlap > inside.size * MAX_OVERLAP) return null
            var ringSum = 0L
            var ringCount = 0
            for (p in ring) {
                val x = p % width + shift.dx
                val y = p / width + shift.dy
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                ringSum += difference(pixels[p], pixels[y * width + x])
                ringCount++
            }
            // 둘레를 절반도 견주지 못하면 믿을 수 없다
            if (ringCount * 2 < ring.size) return null
            return ringSum.toDouble() / ringCount + INSIDE_WEIGHT * insideSum.toDouble() / inside.size
        }

        companion object {
            fun of(pixels: IntArray, painted: BooleanArray, width: Int, height: Int, radius: Float): Spot? {
                require(width > 0 && height > 0 && pixels.size == width * height && painted.size == width * height) { "Bad image ${width}x$height" }
                val inside = ArrayList<Int>()
                var minX = width
                var minY = height
                var maxX = -1
                var maxY = -1
                for (i in painted.indices) {
                    if (!painted[i]) continue
                    inside += i
                    val x = i % width
                    val y = i / width
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                if (inside.isEmpty()) return null
                val unit = radius.coerceAtLeast(1f)
                val band = (unit * BAND).roundToInt().coerceAtLeast(1)
                val ring = ringOf(painted, width, height, band)
                if (ring.isEmpty()) return null
                val ringSample = sample(ring, MAX_SAMPLES)
                return Spot(
                    pixels, painted, width, height, unit, band,
                    inside = sample(inside, MAX_SAMPLES),
                    ring = ringSample,
                    mean = meanOf(pixels, ringSample),
                    minX = minX, minY = minY, maxX = maxX, maxY = maxY
                )
            }

            /** [points] 픽셀의 채널(A·R·G·B)마다 평균 */
            private fun meanOf(pixels: IntArray, points: IntArray): IntArray {
                val sums = LongArray(4)
                for (p in points) for (c in 0 until 4) sums[c] += ((pixels[p] ushr (c * 8)) and 0xFF).toLong()
                return IntArray(4) { (sums[it] / points.size).toInt() }
            }

            /** 두 픽셀의 채널 차이 제곱 합 */
            private fun difference(a: Int, b: Int): Int {
                var sum = 0
                for (c in 0 until 4) {
                    val d = ((a ushr (c * 8)) and 0xFF) - ((b ushr (c * 8)) and 0xFF)
                    sum += d * d
                }
                return sum
            }

            /** 픽셀과 채널마다 평균([meanOf])의 차이 제곱 합 */
            private fun distanceTo(pixel: Int, mean: IntArray): Int {
                var sum = 0
                for (c in 0 until 4) {
                    val d = ((pixel ushr (c * 8)) and 0xFF) - mean[c]
                    sum += d * d
                }
                return sum
            }
        }
    }
}
