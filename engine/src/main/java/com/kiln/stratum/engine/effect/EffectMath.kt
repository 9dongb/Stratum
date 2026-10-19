package com.kiln.stratum.engine.effect

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/** 흐림 도구·모자이크 도구가 칠한 곳에 거는 효과 */
enum class EffectKind {
    /** 흐림: 가우스 흐림(포토샵의 흐림 도구) */
    BLUR,

    /** 선명: 작은 흐림과의 차이를 더한다(언샵 마스크, 포토샵의 선명 도구) */
    SHARPEN,

    /** 모자이크: 문서 (0, 0)에 맞춘 네모 칸마다 그 칸의 평균 색(포토샵에는 필터로만 있다) */
    MOSAIC
}

/**
 * 흐림·선명·모자이크 한 획의 설정.
 *
 * @property strength 흐림·선명의 강도 0..1(포토샵의 강도). 모자이크는 쓰지 않는다.
 * @property cell 모자이크 칸 한 변(문서 픽셀, [EffectMath.MIN_CELL]..[EffectMath.MAX_CELL]). 흐림·선명은 쓰지 않는다.
 */
data class EffectFill(val kind: EffectKind, val strength: Float = 0.5f, val cell: Int = 16) {
    init {
        require(strength in 0f..1f) { "Strength out of range: $strength" }
        require(cell in EffectMath.MIN_CELL..EffectMath.MAX_CELL) { "Cell out of range: $cell" }
    }
}

/**
 * 흐림·선명·모자이크의 계산 기준. GPU 셰이더(EffectShader)가 같은 값과 식을 쓰고, 단위 테스트가 이것을 확인한다.
 * 강도에 따른 흐림 정도는 폴드8에서 써 보고 고칠 수 있게 여기 한곳에 둔다.
 *
 * - 흐림: 가우스 σ = 브러시 지름 × (1% + 강도 × 9%), [MIN_SIGMA]~[MAX_SIGMA]. 큰 붓일수록 넓게 흐린다.
 * - 선명: σ = 지름의 1%(0.7~2px)로 흐린 색과의 차이를 강도 × 1.5배 더한다. 가는 결만 살린다.
 * - 셰이더는 가로·세로로 나눠 한쪽에 [TAPS]곳씩 3σ까지 고르게 읽는다(가중치는 σ와 상관없이 같다, [tapWeight]).
 * - 모자이크: 칸은 문서 (0, 0)에서 시작하는 격자다. 칸의 한 축이 [CELL_SAMPLES] 픽셀 이하면 픽셀마다 읽어 정확한 평균,
 *   더 크면 고르게 [CELL_SAMPLES]곳을 읽는다(쌍선형이라 이웃 픽셀도 섞인다). 문서 밖은 칸에서 뺀다.
 */
object EffectMath {

    const val MIN_CELL = 2
    const val MAX_CELL = 512

    const val MIN_SIGMA = 0.5f
    const val MAX_SIGMA = 32f

    /** 흐림·선명 셰이더가 가운데 한쪽에 읽는 곳의 수 */
    const val TAPS = 24

    /** 모자이크 셰이더가 칸의 한 축에서 읽는 곳의 최대 수 */
    const val CELL_SAMPLES = 16

    /** 흐림의 가우스 σ(문서 픽셀) */
    fun blurSigma(brushSize: Float, strength: Float): Float =
        (brushSize * (0.01f + 0.09f * strength.coerceIn(0f, 1f))).coerceIn(MIN_SIGMA, MAX_SIGMA)

    /** 선명이 견줄 흐림의 σ(문서 픽셀) */
    fun sharpenSigma(brushSize: Float): Float = (brushSize * 0.01f).coerceIn(0.7f, 2f)

    /** 선명의 양: 결과 = 원래 색 + 양 × (원래 색 - 흐린 색) */
    fun sharpenAmount(strength: Float): Float = 1.5f * strength.coerceIn(0f, 1f)

    /** [fill]을 브러시 지름 [brushSize]로 걸 때의 σ(문서 픽셀). 모자이크는 0이다. */
    fun sigma(fill: EffectFill, brushSize: Float): Float =
        when (fill.kind) {
            EffectKind.BLUR -> blurSigma(brushSize, fill.strength)
            EffectKind.SHARPEN -> sharpenSigma(brushSize)
            EffectKind.MOSAIC -> 0f
        }

    /** 흐림·선명 셰이더가 [i]번째(가운데 0, -[TAPS]..[TAPS]) 곳에 주는 가중치: 3σ까지 고르게 읽으므로 exp(-(3i/TAPS)² / 2) */
    fun tapWeight(i: Int): Float {
        val t = 3f * i / TAPS
        return exp(-0.5f * t * t)
    }

    /** 흐림·선명 셰이더가 읽는 곳 사이 거리(문서 픽셀): 3σ / [TAPS] */
    fun tapStep(sigma: Float): Float = 3f * sigma / TAPS

    /**
     * 칠한 곳 둘레에서 더 읽어야 하는 거리(문서 픽셀). 흐림·선명은 셰이더가 읽는 끝(3σ)보다 한 칸 더(쌍선형),
     * 모자이크는 0이다(칸 경계까지 넓히는 것은 [cellAligned]).
     */
    fun margin(fill: EffectFill, brushSize: Float): Int =
        if (fill.kind == EffectKind.MOSAIC) 0 else ceil(3f * sigma(fill, brushSize)).toInt() + 1

    /**
     * 모자이크: [from]..[to](문서 픽셀, [to]는 빼고)에 걸친 칸들을 모두 담는 범위(칸 경계에 맞춤). 문서 크기 [limit] 밖은 자른다.
     * 돌려주는 값은 (시작, 끝)이고 끝은 빼고 센다.
     */
    fun cellAligned(from: Int, to: Int, cell: Int, limit: Int): Pair<Int, Int> {
        val start = floor(from.toDouble() / cell).toInt() * cell
        val end = ceil(to.toDouble() / cell).toInt() * cell
        return start.coerceIn(0, limit) to end.coerceIn(0, limit)
    }

    /**
     * 문서 자리 [position](픽셀 가운데)이 든 칸의 한 축 범위에서 평균을 낼 때 읽는 곳들(문서 자리). 문서 크기 [limit] 밖은 칸에서 뺀다.
     * 칸이 [CELL_SAMPLES] 픽셀 이하면 픽셀 가운데마다(정확한 평균), 더 크면 고르게 나눈 [CELL_SAMPLES]곳의 가운데다.
     */
    fun cellSamples(position: Float, cell: Int, limit: Int): FloatArray {
        val start = floor(position / cell) * cell
        val low = maxOf(start, 0f)
        val high = minOf(start + cell, limit.toFloat())
        val span = high - low
        val count = ceil(span).toInt().coerceIn(1, CELL_SAMPLES)
        return FloatArray(count) { low + (it + 0.5f) * span / count }
    }
}
