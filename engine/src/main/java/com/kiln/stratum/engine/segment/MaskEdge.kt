package com.kiln.stratum.engine.segment

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AI 선택 가장자리를 매끈하고 또렷하게(결정 기록 'AI 제거·AI 선택'의 '선택 가장자리'). 분할 모델의 마스크는 문서보다 2~8배 작아서,
 * 쌍선형으로 키우기만 하면 경계가 마스크 칸의 계단을 따라 물결치고(오돌토돌) 2~8px 넓게 번진다.
 * 마스크를 σ [SIGMA]칸 흐린 뒤 키우고, 0.5 둘레를 문서 약 [EDGE_PIXELS]px 폭으로 펴면 경계가 매끈한 곡선이 되고 또렷하다.
 * 흐리기는 Kotlin으로 긴 변 1024px 이하 마스크에서만 하고, 키우기·펴기는 안드로이드 그리기(쌍선형 + 색 행렬)다.
 * 상수는 맥에서 실제 사진 3장(자동차·말·사람)을 50MP 흉내로 키워 견주어 골랐다.
 */
object MaskEdge {

    /** 키우기 전에 마스크를 흐리는 가우스 σ(마스크 칸). 0.6은 말 입 안쪽처럼 어두운 곳이 잘렸고, 1.0이 매끈하고 모양을 지켰다. */
    const val SIGMA = 1.0f

    /** 키운 경계의 폭(문서 px). 이 안에서 0에서 1로 바뀐다. */
    const val EDGE_PIXELS = 1.5f

    /** [alphas]([width]×[height], 0..255)를 가우스 σ [sigma]칸으로 흐린 것. 마스크 밖은 가장자리 값을 늘인 것으로 본다. */
    fun blur(alphas: ByteArray, width: Int, height: Int, sigma: Float = SIGMA): ByteArray {
        require(width > 0 && height > 0 && alphas.size == width * height) { "Mask must be ${width}x$height" }
        require(sigma > 0f) { "Sigma must be positive: $sigma" }
        val radius = ceil(3f * sigma).toInt()
        val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2f * sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        // 가로
        val row = FloatArray(width * height)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * (alphas[base + (x + k - radius).coerceIn(0, width - 1)].toInt() and 0xFF)
                row[base + x] = sum
            }
        }
        // 세로
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * row[(y + k - radius).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = sum.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /**
     * 마스크 한 칸이 문서 [cell]px일 때, 흐린 마스크를 키운 값(0..1)에서 0.5 둘레를 펼 반폭.
     * 또렷한 경계를 σ로 흐리면 경계에서 마스크 1칸당 1 / (√(2π)·σ)만큼 바뀌므로, 문서 [EDGE_PIXELS]px만큼 바뀌는 양의 반이다.
     */
    fun halfWidth(cell: Int, sigma: Float = SIGMA): Float {
        require(cell > 0) { "Cell must be positive: $cell" }
        val perPixel = 1f / (sqrt(2f * PI.toFloat()) * sigma * cell)
        return (EDGE_PIXELS * perPixel / 2f).coerceAtMost(0.5f)
    }
}
