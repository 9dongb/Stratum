package com.kiln.stratum.engine.retouch

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * 구멍 메우기(pull-push). 아는 곳(무게 1)의 값은 그대로 두고, 모르는 곳(무게 0)은 둘레의 아는 값을 매끈하게 이어 채운다.
 *
 * 반씩 줄이며(pull) 아는 값을 모아 거친 단계를 만들고, 다시 키우며(push) 모르는 곳을 거친 단계의 값으로 채운다.
 * 줄일 때는 가로·세로 [1, 3, 3, 1] 무게로, 키울 때는 쌍선형으로 이어 보아 계단이 지지 않는다. 무게가 0과 1 사이면 그만큼 섞는다.
 * 복구 브러시가 칠한 곳 둘레의 '그 자리 - 가져온 곳' 차이를 안쪽으로 이어, 가져온 무늬의 밝기·색을 둘레에 맞추는 데 쓴다.
 *
 * 줄인 그림(긴 변 [MAX_EDGE] 이하)에서만 부른다. 원본 크기에서 픽셀을 하나씩 돌지 않는다(결정 기록 '리터칭').
 */
object HoleFill {

    /** 메울 그림의 긴 변 최대 픽셀. 이 크기면 Kotlin으로 돌아도 수십 밀리초 안이다. */
    const val MAX_EDGE = 512

    /** 아는 값이 아는 값들의 가운데값과 이만큼 다르면 안쪽으로 번지는 몫이 반이 된다(0..1 눈금의 값) */
    const val OUTLIER_SCALE = 0.1f

    /** 가운데값을 셀 때 보는 최대 픽셀 수(고르게 건너뛰어 본다) */
    private const val MEDIAN_SAMPLES = 4096

    /**
     * [fill]의 `influence`로 쓸 몫. 아는 픽셀마다 1 / (1 + (가운데값과 다른 정도 / [scale])²)이고 모르는 곳(무게 0)은 0이다.
     * 가운데값은 채널마다 아는 곳 값의 중앙값이고, 다른 정도는 채널 가운데 가장 큰 것이다.
     * 고르게 다른 곳(어두운 곳에 밝은 무늬를 가져옴)은 모두 1에 가깝고, 줄·경계가 어긋난 곳만 작아진다
     * (복구 브러시의 둘레 맞추기와 AI 제거의 색 맞추기, 결정 기록 '리터칭'·'AI 제거·AI 선택').
     * 다른 정도가 [cutoff]를 넘는 픽셀은 0이다(둘레로 전혀 번지지 않음). 작은 몫도 거친 단계에서 쌓여 번지기 때문이다.
     */
    fun influence(
        values: FloatArray,
        weights: FloatArray,
        channels: Int,
        scale: Float = OUTLIER_SCALE,
        cutoff: Float = Float.POSITIVE_INFINITY
    ): FloatArray {
        require(channels > 0 && values.size == weights.size * channels) { "Bad buffers: ${values.size} values, ${weights.size} weights" }
        require(scale > 0f) { "Scale must be positive: $scale" }
        var count = 0
        for (w in weights) if (w > 0f) count++
        if (count == 0) return FloatArray(weights.size)
        val step = maxOf(1, count / MEDIAN_SAMPLES)
        val samples = Array(channels) { FloatArray((count + step - 1) / step) }
        var known = 0
        var taken = 0
        for (p in weights.indices) {
            if (weights[p] == 0f) continue
            if (known++ % step == 0 && taken < samples[0].size) {
                for (c in 0 until channels) samples[c][taken] = values[p * channels + c]
                taken++
            }
        }
        val medians = FloatArray(channels) { c -> samples[c].copyOf(taken).also { it.sort() }[taken / 2] }
        return FloatArray(weights.size) { p ->
            if (weights[p] == 0f) {
                0f
            } else {
                var far = 0f
                for (c in 0 until channels) far = maxOf(far, abs(values[p * channels + c] - medians[c]))
                val r = far / scale
                if (far > cutoff) 0f else 1f / (1f + r * r)
            }
        }
    }

    /**
     * [values](픽셀마다 [channels]개, 줄마다 이어 붙인 값)의 모르는 곳을 채운 새 배열.
     * [weights]는 픽셀마다 아는 정도(0..1)다. 아는 곳이 하나도 없으면 모두 0이다.
     * [influence]가 있으면 아는 픽셀이 둘레(거친 단계)에 주는 몫을 그만큼(0..1) 줄인다. 그 픽셀 자신의 값은 그대로 남는다.
     * 다른 값들과 크게 다른 픽셀(어긋난 경계)이 안쪽으로 번지지 않게 할 때 쓴다.
     */
    fun fill(values: FloatArray, weights: FloatArray, width: Int, height: Int, channels: Int, influence: FloatArray? = null): FloatArray {
        require(width > 0 && height > 0 && channels > 0) { "Bad size: ${width}x$height×$channels" }
        require(values.size == width * height * channels && weights.size == width * height) { "Bad buffers for ${width}x$height×$channels" }
        require(influence == null || influence.size == weights.size) { "Bad influence for ${width}x$height" }
        val first = Level(values, FloatArray(weights.size) { weights[it].coerceIn(0f, 1f) }, width, height)
        if (width == 1 && height == 1) return FloatArray(channels) { values[it] * first.weights[0] }
        val levels = arrayListOf(first)
        // 첫 단계에서 줄일 때만 몫을 줄인다(키울 때는 원래 무게로 제 값을 지킨다)
        val pulled = if (influence == null) first else Level(values, FloatArray(weights.size) { first.weights[it] * influence[it].coerceIn(0f, 1f) }, width, height)
        levels += pull(pulled, channels)
        while (levels.last().width > 1 || levels.last().height > 1) levels += pull(levels.last(), channels)
        // 가장 거친 단계(1×1)는 아는 값 전체의 평균이다(아는 곳이 없으면 0)
        var filled = levels.last().values
        for (index in levels.size - 2 downTo 0) {
            val coarse = levels[index + 1]
            filled = push(levels[index], filled, coarse.width, coarse.height, channels)
        }
        return filled
    }

    /** 한 단계: 픽셀마다의 값(무게를 곱하지 않은 것)과 무게 */
    private class Level(val values: FloatArray, val weights: FloatArray, val width: Int, val height: Int)

    /**
     * 반 크기 단계. 거친 픽셀 하나는 고운 픽셀 4×4를 [KERNEL] 무게로 모은다. 값은 아는 정도를 곱해 모은 뒤 그 합으로 나누고,
     * 무게는 모은 합의 4배(고운 픽셀 넷 몫)를 1로 자른다. 그래서 둘레에 아는 곳이 조금만 걸쳐도 그 값을 믿는다.
     * 그림 밖은 모르는 곳으로 본다.
     */
    private fun pull(fine: Level, channels: Int): Level {
        val width = (fine.width + 1) / 2
        val height = (fine.height + 1) / 2
        val values = FloatArray(width * height * channels)
        val weights = FloatArray(width * height)
        val sum = FloatArray(channels)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var total = 0f
                sum.fill(0f)
                for (j in 0 until 4) {
                    val fy = 2 * y - 1 + j
                    if (fy < 0 || fy >= fine.height) continue
                    for (i in 0 until 4) {
                        val fx = 2 * x - 1 + i
                        if (fx < 0 || fx >= fine.width) continue
                        val p = fy * fine.width + fx
                        val k = KERNEL[j] * KERNEL[i] * fine.weights[p]
                        if (k == 0f) continue
                        total += k
                        for (c in 0 until channels) sum[c] += k * fine.values[p * channels + c]
                    }
                }
                if (total > 0f) {
                    val q = y * width + x
                    for (c in 0 until channels) values[q * channels + c] = sum[c] / total
                    weights[q] = min(1f, total * 4f)
                }
            }
        }
        return Level(values, weights, width, height)
    }

    /** [coarse](크기 [coarseWidth]×[coarseHeight], 다 채운 값)를 쌍선형으로 키워 [fine]의 모르는 곳을 채운다(아는 정도만큼 섞음). */
    private fun push(fine: Level, coarse: FloatArray, coarseWidth: Int, coarseHeight: Int, channels: Int): FloatArray {
        val result = FloatArray(fine.width * fine.height * channels)
        for (y in 0 until fine.height) {
            // 고운 픽셀 가운데가 거친 단계에서 놓이는 자리(픽셀 가운데 기준)
            val v = (y + 0.5f) / 2f - 0.5f
            val y0 = floor(v).toInt()
            val ty = v - y0
            val top = y0.coerceIn(0, coarseHeight - 1) * coarseWidth
            val bottom = (y0 + 1).coerceIn(0, coarseHeight - 1) * coarseWidth
            for (x in 0 until fine.width) {
                val u = (x + 0.5f) / 2f - 0.5f
                val x0 = floor(u).toInt()
                val tx = u - x0
                val left = x0.coerceIn(0, coarseWidth - 1)
                val right = (x0 + 1).coerceIn(0, coarseWidth - 1)
                val p = y * fine.width + x
                val known = fine.weights[p]
                for (c in 0 until channels) {
                    val upper = coarse[(top + left) * channels + c] * (1f - tx) + coarse[(top + right) * channels + c] * tx
                    val lower = coarse[(bottom + left) * channels + c] * (1f - tx) + coarse[(bottom + right) * channels + c] * tx
                    val interpolated = upper * (1f - ty) + lower * ty
                    result[p * channels + c] = if (known == 0f) interpolated else known * fine.values[p * channels + c] + (1f - known) * interpolated
                }
            }
        }
        return result
    }

    /** 반으로 줄일 때의 한 방향 무게(합 1). 쌍선형 키우기와 짝이 맞아 매끈하다. */
    private val KERNEL = floatArrayOf(1f / 8f, 3f / 8f, 3f / 8f, 1f / 8f)
}
