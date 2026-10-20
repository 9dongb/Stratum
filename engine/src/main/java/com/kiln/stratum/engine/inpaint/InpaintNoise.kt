package com.kiln.stratum.engine.inpaint

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 잡음 맞추기(결정 기록 'AI 제거·AI 선택'의 '10단계 잡음 맞추기'). 모델 그림을 원본 해상도로 키워 붙이면 사진의 고운 잡음이 없어
 * 채운 곳만 매끈해 보인다. 넓힌 구멍 둘레의 원래 픽셀과 키운 채울 그림에서 잡음 세기를 같은 방법으로 재어, 모자란 만큼 밝기 잡음을 더한다.
 *
 * 세기는 Immerkær 필터([[1,-2,1],[-2,4,-2],[1,-2,1]], 밝기 0..255) 절대값의 가운데값으로 잰다. 가우스 잡음이면 필터 값의 표준편차가 6σ라
 * σ = 가운데값 / (6 × 0.6745)다. 평균 대신 가운데값을 써서, 경계·무늬(필터 값이 큰 곳)가 반을 넘지 않으면 세기를 부풀리지 않는다.
 * 재기는 원본 해상도의 작은 조각 몇 개([PATCH]px, [PATCHES]개)에서만 해서 Kotlin 반복이 작다.
 */
object InpaintNoise {

    /** 재는 조각의 한 변(문서 px) */
    const val PATCH = 32

    /** 둘레와 채운 곳에서 각각 재는 조각 수 */
    const val PATCHES = 8

    /** 둘레 조각의 가운데를 고르는 띠(모델 px): 넓힌 구멍 밖 [EDGE_GAP]부터 이만큼까지 */
    const val RING = 16f

    /** 둘레로 보는 픽셀이 넓힌 구멍에서 떨어져야 하는 거리(모델 px). 지운 물체의 번진 가장자리를 재지 않게 한다. */
    const val EDGE_GAP = 2f

    /** 잰 값이 이보다 적으면 재지 못한 것으로 보고 잡음을 더하지 않는다 */
    const val MIN_SAMPLES = 256

    /** 더할 잡음의 최대 세기(밝기 0..255). 무늬가 센 둘레(풀밭 등)에서 채운 곳이 지나치게 거칠어지지 않게 한다. */
    const val MAX_SIGMA = 10f

    /** 가우스 잡음에서 필터 절대값의 가운데값을 σ로: 1 / (6 × 0.6745) */
    private const val MEDIAN_TO_SIGMA = 1f / (6f * 0.6745f)

    /** 밝기 계수(Rec. 709, 앱의 다른 밝기 계산과 같다) */
    private const val RED = 0.2126f
    private const val GREEN = 0.7152f
    private const val BLUE = 0.0722f

    /** 알파를 곱한 R·G·B([red], [green], [blue])와 알파 [alpha](모두 같은 눈금)의 밝기. 알파를 푼 값이고, 알파가 0이면 0이다. */
    fun luma(red: Float, green: Float, blue: Float, alpha: Float): Float =
        if (alpha <= 0f) 0f else (RED * red + GREEN * green + BLUE * blue) / alpha

    /**
     * 밝기 [luma]([width]×[height], 0..255)에서 3×3 이웃이 모두 [valid]인 픽셀마다 Immerkær 필터 절대값을 [into]에 더한다.
     */
    fun collect(luma: FloatArray, valid: BooleanArray, width: Int, height: Int, into: MutableList<Float>) {
        require(luma.size >= width * height && valid.size >= width * height) { "Buffers must be ${width}x$height" }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var ok = true
                for (dy in -1..1) for (dx in -1..1) if (!valid[(y + dy) * width + x + dx]) ok = false
                if (!ok) continue
                val i = y * width + x
                val response = luma[i - width - 1] - 2f * luma[i - width] + luma[i - width + 1] -
                    2f * luma[i - 1] + 4f * luma[i] - 2f * luma[i + 1] +
                    luma[i + width - 1] - 2f * luma[i + width] + luma[i + width + 1]
                into += abs(response)
            }
        }
    }

    /** 모은 필터 값([collect])으로 잰 잡음 표준편차(밝기 0..255). 값이 [MIN_SAMPLES]보다 적으면 null */
    fun sigma(responses: List<Float>): Float? {
        if (responses.size < MIN_SAMPLES) return null
        val sorted = responses.toFloatArray()
        sorted.sort()
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        return median * MEDIAN_TO_SIGMA
    }

    /** 둘레 잡음 [known]에 맞추려고 채운 곳(잡음 [filled])에 더할 세기: √(known² − filled²), [MAX_SIGMA] 이하 */
    fun added(known: Float, filled: Float): Float = sqrt(max(0f, known * known - filled * filled)).coerceAtMost(MAX_SIGMA)

    /** [candidates] 가운데 [count]개를 같은 간격으로 고른다(줄 순서라 위아래로 고르게 퍼진다). 적으면 모두다. */
    fun pick(candidates: IntArray, count: Int): IntArray {
        require(count > 0) { "Count must be positive: $count" }
        val n = candidates.size
        if (n <= count) return candidates
        return IntArray(count) { candidates[((it + 0.5) * n / count).toInt()] }
    }

    /**
     * 채울 그림 [fill](알파를 곱한 RGBA 0..1, [size]² 픽셀)을 GPU의 키우기(`ImageResizer.BICUBIC`, Catmull-Rom)와 같은 계산으로 읽은
     * 문서 픽셀들([x0], [y0]부터 [width]×[height])의 밝기(0..255, 알파를 푼 값). 문서 픽셀 (X, Y)의 가운데는 그림의
     * ((X + 0.5 − [left]) × [scale]) 자리다. 알파가 거의 1인 픽셀만 [opaque]에 참으로 적는다.
     */
    fun upscaledLuma(
        fill: FloatArray,
        size: Int,
        left: Int,
        top: Int,
        scale: Float,
        x0: Int,
        y0: Int,
        width: Int,
        height: Int,
        opaque: BooleanArray
    ): FloatArray {
        require(fill.size == size * size * 4) { "Fill must be ${size}x$size RGBA" }
        require(opaque.size >= width * height) { "Opaque flags must be ${width}x$height" }
        val luma = FloatArray(width * height)
        val weightsX = FloatArray(4)
        val weightsY = FloatArray(4)
        val color = FloatArray(4)
        for (y in 0 until height) {
            val sourceY = (y0 + y + 0.5f - top) * scale
            val baseY = floor(sourceY - 0.5f).toInt()
            for (j in 0..3) weightsY[j] = catmullRom(sourceY - (baseY + j - 1 + 0.5f))
            for (x in 0 until width) {
                val sourceX = (x0 + x + 0.5f - left) * scale
                val baseX = floor(sourceX - 0.5f).toInt()
                for (i in 0..3) weightsX[i] = catmullRom(sourceX - (baseX + i - 1 + 0.5f))
                color.fill(0f)
                var total = 0f
                for (j in 0..3) {
                    val row = (baseY + j - 1).coerceIn(0, size - 1) * size
                    for (i in 0..3) {
                        val w = weightsY[j] * weightsX[i]
                        val p = (row + (baseX + i - 1).coerceIn(0, size - 1)) * 4
                        for (c in 0..3) color[c] += fill[p + c] * w
                        total += w
                    }
                }
                // GPU와 같이 0 ≤ 색 ≤ 알파 ≤ 1로 자른다
                val alpha = (color[3] / total).coerceIn(0f, 1f)
                val red = (color[0] / total).coerceIn(0f, alpha)
                val green = (color[1] / total).coerceIn(0f, alpha)
                val blue = (color[2] / total).coerceIn(0f, alpha)
                val index = y * width + x
                luma[index] = luma(red, green, blue, alpha) * 255f
                opaque[index] = alpha >= OPAQUE
            }
        }
        return luma
    }

    /** 이만큼 이상이면 불투명으로 본다(알파 0..1) */
    private const val OPAQUE = 0.999f

    /** Catmull-Rom 가중치(`ImageResizer.BICUBIC`의 weight와 같다) */
    private fun catmullRom(distance: Float): Float {
        val x = abs(distance)
        return when {
            x < 1f -> (1.5f * x - 2.5f) * x * x + 1f
            x < 2f -> ((-0.5f * x + 2.5f) * x - 4f) * x + 2f
            else -> 0f
        }
    }
}
