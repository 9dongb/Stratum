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
 * - 후보는 16방향이고, 방향마다 브러시 반지름의 몇 배와 칠한 곳을 비켜 가는 거리 몇 가지다.
 * - 옮긴 칠한 곳이 그림 밖으로 나가거나 칠한 곳과 겹치는(2%보다 많이) 후보는 뺀다. 얼룩을 다시 가져오지 않게 한다.
 * - 견주는 값: 둘레 띠끼리의 색 차이, 가져올 안쪽이 그 둘레 평균과 다른 정도(다른 얼룩·경계를 피함), 거리(가까운 곳을 조금 더).
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

    /**
     * 가장 알맞은 옮길 양. 후보가 하나도 없으면 null이다.
     *
     * @param pixels 그림 픽셀(ARGB, 줄마다 이어 붙임). 문서 밖은 들어 있지 않다(그림 = 문서의 한 영역).
     * @param painted 칠한 곳(픽셀마다)
     * @param radius 브러시 반지름(이 그림의 픽셀)
     */
    fun find(pixels: IntArray, painted: BooleanArray, width: Int, height: Int, radius: Float): Shift? {
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
        val reach = radius.coerceAtLeast(1f)
        val band = (reach * BAND).roundToInt().coerceAtLeast(1)
        val ring = ringOf(painted, width, height, band)
        if (ring.isEmpty()) return null
        val mean = meanOf(pixels, ring)
        val extentX = maxX - minX + 1
        val extentY = maxY - minY + 1

        var best: Shift? = null
        var bestScore = Double.MAX_VALUE
        val tried = HashSet<Shift>()
        for (direction in 0 until DIRECTIONS) {
            val angle = direction * 2.0 * PI / DIRECTIONS
            val cos = cos(angle)
            val sin = sin(angle)
            // 이 방향으로 칠한 곳의 사각형을 비켜 가는 거리
            val clear = abs(cos) * extentX + abs(sin) * extentY + band
            val distances = RADIUS_STEPS.map { it * reach.toDouble() } + CLEAR_STEPS.map { it * clear }
            for (distance in distances) {
                val shift = Shift((cos * distance).roundToInt(), (sin * distance).roundToInt())
                if ((shift.dx == 0 && shift.dy == 0) || !tried.add(shift)) continue
                val score = score(pixels, painted, width, height, inside, ring, mean, shift) ?: continue
                val weighted = score * (1.0 + DISTANCE_WEIGHT * hypot(shift.dx.toDouble(), shift.dy.toDouble()) / reach)
                if (weighted < bestScore) {
                    bestScore = weighted
                    best = shift
                }
            }
        }
        return best
    }

    /** [shift] 후보의 점수(작을수록 좋다). 쓸 수 없는 후보면 null. */
    private fun score(
        pixels: IntArray,
        painted: BooleanArray,
        width: Int,
        height: Int,
        inside: List<Int>,
        ring: List<Int>,
        mean: IntArray,
        shift: Shift
    ): Double? {
        var overlap = 0
        var insideSum = 0L
        for (p in inside) {
            val x = p % width + shift.dx
            val y = p / width + shift.dy
            if (x < 0 || y < 0 || x >= width || y >= height) return null
            val q = y * width + x
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

    /** [points] 픽셀의 채널(A·R·G·B)마다 평균 */
    private fun meanOf(pixels: IntArray, points: List<Int>): IntArray {
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
