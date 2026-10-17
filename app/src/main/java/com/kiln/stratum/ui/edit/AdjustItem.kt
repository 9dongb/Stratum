package com.kiln.stratum.ui.edit

import androidx.annotation.StringRes
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.adjust.MAX_EXPOSURE
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 보정 패널의 항목. 화면에 보이는 순서다: 빛(노출·밝기·대비·하이라이트·그림자) → 색(색온도·채도) → 곡선.
 * 곡선 말고는 슬라이더 하나로 바꾼다. 노출은 -2..2 EV, 나머지는 -1..1(화면 숫자는 -100..100)이다.
 *
 * @property min 슬라이더 왼쪽 끝 값
 * @property max 슬라이더 오른쪽 끝 값
 */
enum class AdjustItem(@get:StringRes val label: Int, val min: Float = -1f, val max: Float = 1f) {
    EXPOSURE(R.string.adjust_exposure, -MAX_EXPOSURE, MAX_EXPOSURE),
    BRIGHTNESS(R.string.adjust_brightness),
    CONTRAST(R.string.adjust_contrast),
    HIGHLIGHTS(R.string.adjust_highlights),
    SHADOWS(R.string.adjust_shadows),
    TEMPERATURE(R.string.adjust_temperature),
    SATURATION(R.string.adjust_saturation),
    CURVE(R.string.adjust_curve);

    /** 슬라이더로 바꾸는 항목인지(곡선이 아닌지) */
    val isSlider: Boolean
        get() = this != CURVE

    /** [adjustments]에서 이 슬라이더의 값. 곡선에는 쓰지 않는다. */
    fun valueIn(adjustments: Adjustments): Float =
        when (this) {
            EXPOSURE -> adjustments.exposure
            BRIGHTNESS -> adjustments.brightness
            CONTRAST -> adjustments.contrast
            HIGHLIGHTS -> adjustments.highlights
            SHADOWS -> adjustments.shadows
            TEMPERATURE -> adjustments.temperature
            SATURATION -> adjustments.saturation
            CURVE -> throw IllegalStateException("Curve has no slider value")
        }

    /** [adjustments]의 이 슬라이더 값을 [value]로 바꾼 값. [value]는 화면 숫자 단위(0.01)로 맞춘다. */
    fun applyTo(adjustments: Adjustments, value: Float): Adjustments {
        val rounded = rounded(value)
        return when (this) {
            EXPOSURE -> adjustments.copy(exposure = rounded)
            BRIGHTNESS -> adjustments.copy(brightness = rounded)
            CONTRAST -> adjustments.copy(contrast = rounded)
            HIGHLIGHTS -> adjustments.copy(highlights = rounded)
            SHADOWS -> adjustments.copy(shadows = rounded)
            TEMPERATURE -> adjustments.copy(temperature = rounded)
            SATURATION -> adjustments.copy(saturation = rounded)
            CURVE -> throw IllegalStateException("Curve has no slider value")
        }.normalized()
    }

    /** [adjustments]에서 이 항목을 바꿨는지(0이 아니거나, 곡선이 처음 모양이 아닌지) */
    fun isChanged(adjustments: Adjustments): Boolean =
        if (this == CURVE) !adjustments.curve.isIdentity else valueIn(adjustments) != 0f

    /** [adjustments]에서 이 항목만 처음 값(0, 곡선은 대각선)으로 돌린 값 */
    fun resetIn(adjustments: Adjustments): Adjustments =
        if (this == CURVE) adjustments.copy(curve = Adjustments.NONE.curve) else applyTo(adjustments, 0f)

    /**
     * 화면에 보일 숫자. 노출은 소수 둘째 자리(+0.35), 나머지는 -100..100 정수(+35)이고, 0에는 부호를 붙이지 않는다.
     * 저장되는 값도 0.01 단위라([applyTo]) 보이는 숫자와 같다.
     */
    fun format(value: Float, locale: Locale): String =
        if (this == EXPOSURE) {
            val hundredths = (value * 100).roundToInt()
            if (hundredths == 0) String.format(locale, "%.2f", 0f) else String.format(locale, "%+.2f", hundredths / 100f)
        } else {
            val percent = (value * 100).roundToInt()
            if (percent == 0) String.format(locale, "%d", 0) else String.format(locale, "%+d", percent)
        }

    /** 범위 안으로 묶고 0.01 단위로 맞춘다. -0은 0이 된다. */
    private fun rounded(value: Float): Float = (value.coerceIn(min, max) * 100).roundToInt() / 100f + 0f

    companion object {
        /** 슬라이더 항목(곡선 빼고) */
        val sliders: List<AdjustItem> = entries.filter { it.isSlider }
    }
}
