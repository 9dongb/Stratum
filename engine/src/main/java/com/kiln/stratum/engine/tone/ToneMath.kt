package com.kiln.stratum.engine.tone

/** 명암 도구가 다룰 밝기 범위 */
enum class ToneRange {
    /** 어두운 영역 */
    SHADOWS,

    /** 중간 영역(기본) */
    MIDTONES,

    /** 밝은 영역 */
    HIGHLIGHTS
}

/**
 * 닷지·번 한 획의 설정: 밝게([dodge], 아니면 어둡게), 바꿀 밝기 범위([range]), 노출([exposure], 0..1).
 * 포토샵의 닷지·번 옵션(범위·노출)과 같다.
 */
data class ToneFill(val dodge: Boolean, val range: ToneRange = ToneRange.MIDTONES, val exposure: Float = 0.5f) {
    init {
        require(exposure in 0f..1f) { "Exposure out of range: $exposure" }
    }
}

/**
 * 닷지(밝게)·번(어둡게)의 계산 기준. GPU 셰이더가 같은 식을 쓰고, 단위 테스트가 이 식을 확인한다.
 *
 * 색은 알파를 미리 곱하지 않은 sRGB 값(0..1)이다. 밝기 L = 0.2126 R + 0.7152 G + 0.0722 B(sRGB 값 그대로).
 * 범위의 무게: 어두운 영역 (1 - L)², 중간 영역 4L(1 - L), 밝은 영역 L².
 * 닷지: c + (1 - c) × 노출 × 무게 × 0.5, 번: c × (1 - 노출 × 무게 × 0.5). 노출 100%여도 한 번에 반까지만 바뀐다(여러 번 칠하면 더 바뀐다).
 * 채널마다 같은 비율로 바꿔 색조가 크게 틀어지지 않는다.
 */
object ToneMath {

    /** 노출 100%에서 한 번에 바꾸는 최대 비율 */
    const val STRENGTH = 0.5f

    fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun weight(range: ToneRange, luminance: Float): Float {
        val l = luminance.coerceIn(0f, 1f)
        return when (range) {
            ToneRange.SHADOWS -> (1 - l) * (1 - l)
            ToneRange.MIDTONES -> 4 * l * (1 - l)
            ToneRange.HIGHLIGHTS -> l * l
        }
    }

    /** 채널 값 [c](0..1)를 밝기 [luminance]의 픽셀에서 [exposure](0..1)만큼 닷지([dodge]) 또는 번한 값 */
    fun tone(c: Float, luminance: Float, range: ToneRange, exposure: Float, dodge: Boolean): Float {
        val k = exposure.coerceIn(0f, 1f) * weight(range, luminance) * STRENGTH
        return if (dodge) c + (1 - c) * k else c * (1 - k)
    }
}
