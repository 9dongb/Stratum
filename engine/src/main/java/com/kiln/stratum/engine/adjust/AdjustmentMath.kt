package com.kiln.stratum.engine.adjust

import kotlin.math.pow

/**
 * 기본 보정 계산의 기준(순수 계산). GPU 셰이더(엔진 플랫폼의 AdjustShader)가 픽셀마다 똑같이 계산한다.
 * 큰 사진은 GPU가 그리고, 이 코드는 단위 테스트와 GPU 결과를 맞춰 보는 데 쓴다(CLAUDE.md 2장: 큰 이미지를 CPU로 돌지 않는다).
 *
 * 색은 알파를 미리 곱하지 않은 sRGB 0..1이다. 순서는 [Adjustments]에 적은 대로다.
 */
object AdjustmentMath {

    /** 밝기(휘도) 무게(sRGB 원색, Rec. 709) */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** 색온도 1(가장 따뜻함)에서 선형 빛의 빨강을 늘리고 파랑을 줄이는 몫 */
    const val TEMPERATURE_SHIFT = 0.25f

    /** 틴트 1(가장 자홍)에서 선형 빛의 빨강·파랑을 늘리는 몫. 색온도와 같은 세기다. */
    const val TINT_SHIFT = 0.25f

    /** 틴트 1에서 선형 빛의 초록을 줄이는 몫. 빨강·파랑을 늘린 만큼 줄여 회색의 밝기(휘도)는 그대로다. */
    const val TINT_GREEN_SHIFT = TINT_SHIFT * (LUMA_R + LUMA_B) / LUMA_G

    /** 그림자·하이라이트 1에서 밝기를 옮기는 최대 몫 */
    const val TONE_SHIFT = 0.35f

    /** [adjustments]를 색 [r]·[g]·[b]에 적용한 색(0..1). [curveTable]은 곡선 표(없으면 곡선을 쓰지 않는다). */
    fun apply(adjustments: Adjustments, r: Float, g: Float, b: Float, curveTable: FloatArray? = null): FloatArray {
        // 1. 노출·색온도·틴트: 선형 빛에서 곱한다
        val gain = 2f.pow(adjustments.exposure)
        val magenta = 1f + TINT_SHIFT * adjustments.tint
        var red = toSrgb(toLinear(r) * gain * (1f + TEMPERATURE_SHIFT * adjustments.temperature) * magenta)
        var green = toSrgb(toLinear(g) * gain * (1f - TINT_GREEN_SHIFT * adjustments.tint))
        var blue = toSrgb(toLinear(b) * gain * (1f - TEMPERATURE_SHIFT * adjustments.temperature) * magenta)

        // 2. 그림자·하이라이트: 어두운 곳·밝은 곳의 밝기만 옮긴다
        val luma = luma(red, green, blue)
        val shift = TONE_SHIFT * (
            adjustments.shadows * (1f - smoothstep(0f, 0.6f, luma)) +
                adjustments.highlights * smoothstep(0.4f, 1f, luma)
            )
        red = (red + shift).coerceIn(0f, 1f)
        green = (green + shift).coerceIn(0f, 1f)
        blue = (blue + shift).coerceIn(0f, 1f)

        // 3. 밝기: 검정·흰색은 두고 중간 밝기를 올리거나 내린다(감마)
        val gamma = 2f.pow(-adjustments.brightness)
        red = red.pow(gamma)
        green = green.pow(gamma)
        blue = blue.pow(gamma)

        // 4. 대비: 가운데(0.5)를 기준으로 벌리거나 좁힌다
        val k = 1f + adjustments.contrast
        red = ((red - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
        green = ((green - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
        blue = ((blue - 0.5f) * k + 0.5f).coerceIn(0f, 1f)

        // 5. 곡선: 표 사이는 직선으로 잇는다(GPU 텍스처의 직선 필터와 같다)
        if (curveTable != null) {
            red = lookUp(curveTable, red)
            green = lookUp(curveTable, green)
            blue = lookUp(curveTable, blue)
        }

        // 6. 채도: 밝기(회색)에서 멀어지거나 가까워진다
        val gray = luma(red, green, blue)
        val amount = 1f + adjustments.saturation
        return floatArrayOf(
            (gray + (red - gray) * amount).coerceIn(0f, 1f),
            (gray + (green - gray) * amount).coerceIn(0f, 1f),
            (gray + (blue - gray) * amount).coerceIn(0f, 1f)
        )
    }

    /** sRGB → 선형 빛 */
    fun toLinear(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

    /** 선형 빛 → sRGB(0..1로 자른다) */
    fun toSrgb(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return if (clamped <= 0.0031308f) clamped * 12.92f else 1.055f * clamped.pow(1f / 2.4f) - 0.055f
    }

    fun luma(r: Float, g: Float, b: Float): Float = LUMA_R * r + LUMA_G * g + LUMA_B * b

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 표 [table]에서 [x](0..1)의 값. 칸 사이는 직선으로 잇는다. */
    fun lookUp(table: FloatArray, x: Float): Float {
        val position = x.coerceIn(0f, 1f) * (table.size - 1)
        val index = position.toInt().coerceAtMost(table.size - 2)
        val fraction = position - index
        return table[index] + (table[index + 1] - table[index]) * fraction
    }
}
