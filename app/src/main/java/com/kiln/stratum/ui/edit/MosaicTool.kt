package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Stable
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.effect.EffectMath
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 모자이크 도구 설정. 브러시 모양(크기·경도)과 셀 크기(네모 칸 한 변, 문서 픽셀, 포토샵 한국어판 모자이크 필터의 '셀 크기').
 * 처음 셀 크기는 처음 연 문서 짧은 변의 2%다(ViewModel이 정함). 브러시 불투명도는 쓰지 않는다.
 */
data class MosaicOptions(
    val brush: BrushSettings = BrushSettings(size = 200f, hardness = 1f),
    val cell: Int = 16
)

/**
 * 모자이크 옵션이 쓰는 값과 할 일. 설정은 ViewModel이 들고(앱을 끌 때까지 남음), 바꾸면 [onChange]로 알린다.
 * [sizePreview]는 크기·경도 슬라이더를 끄는 동안 캔버스에 크기를 보이는 상태다(없으면 보이지 않음).
 */
@Stable
class MosaicControls(val options: MosaicOptions, val sizePreview: SizePreviewState? = null, val onChange: (MosaicOptions) -> Unit)

/** 셀 크기 슬라이더의 눈금(0..1)을 셀 크기(px)로. 크기 슬라이더처럼 로그 눈금이라 작은 칸도 세밀하게 고른다. */
internal fun fractionToCell(fraction: Float): Int {
    val min = ln(EffectMath.MIN_CELL.toFloat())
    val max = ln(EffectMath.MAX_CELL.toFloat())
    return exp(min + (max - min) * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(EffectMath.MIN_CELL, EffectMath.MAX_CELL)
}

/** 셀 크기(px)를 셀 크기 슬라이더의 눈금(0..1)으로 */
internal fun cellToFraction(cell: Int): Float {
    val min = ln(EffectMath.MIN_CELL.toFloat())
    val max = ln(EffectMath.MAX_CELL.toFloat())
    return ((ln(cell.coerceIn(EffectMath.MIN_CELL, EffectMath.MAX_CELL).toFloat()) - min) / (max - min)).coerceIn(0f, 1f)
}
