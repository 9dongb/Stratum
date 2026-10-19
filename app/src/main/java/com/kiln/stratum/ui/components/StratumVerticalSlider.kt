package com.kiln.stratum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 세로 슬라이더(펼친 화면 도구 옵션 띠의 옵션 창, 결정 기록 '도구 옵션 띠'): 얇은 세로 트랙(3dp, 끝은 각짐)과 흰 원 손잡이.
 * 손잡이·색은 가로 [StratumSlider]와 같다. 아래 끝이 0, 위 끝이 1이고, 아래 끝에서 손잡이까지 채움 색으로 칠한다(위로 밀면 커진다).
 * 트랙 어디를 눌러도 그 자리 값으로 가고, 끌면 따라온다. 손을 떼거나 이 자리가 사라지면 [onValueChangeFinished]를 부른다.
 * 크기는 부르는 쪽이 정한다(폭은 누르는 영역 48dp 이상). 화면 읽기 기능에는 진행 정도(0..1)와 값 조절을 알리고,
 * 이름·값 글은 부르는 쪽이 semantics로 더한다(가로 슬라이더와 같다).
 */
@Composable
fun StratumVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true
) {
    val colors = StratumTheme.colors
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val fraction = value.coerceIn(0f, 1f)
    Canvas(
        modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (!enabled) disabled()
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    currentOnChange(target.coerceIn(0f, 1f))
                    currentOnFinished()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val inset = VerticalSliderInset.toPx()
                    fun valueAt(y: Float): Float {
                        val length = (size.height - 2 * inset).coerceAtLeast(1f)
                        return (1f - (y - inset) / length).coerceIn(0f, 1f)
                    }
                    currentOnChange(valueAt(down.position.y))
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) currentOnChange(valueAt(change.position.y))
                            change.consume()
                        }
                    } finally {
                        // 손을 뗐거나, 끄는 도중 이 자리가 사라졌다(옵션 창이 닫힘). 어느 쪽이든 끝낸 것으로 본다.
                        currentOnFinished()
                    }
                }
            }
    ) {
        val inset = VerticalSliderInset.toPx()
        val length = (size.height - 2 * inset).coerceAtLeast(0f)
        val trackWidth = StratumDimens.SliderTrack.toPx()
        val x = size.width / 2
        val thumbY = inset + (1f - fraction) * length
        drawRect(if (enabled) colors.sliderTrack else colors.divider, Offset(x - trackWidth / 2, inset), Size(trackWidth, length))
        drawRect(if (enabled) colors.sliderFill else colors.textDisabled, Offset(x - trackWidth / 2, thumbY), Size(trackWidth, inset + length - thumbY))
        val radius = StratumDimens.SliderThumb.toPx() / 2
        drawCircle(if (enabled) colors.sliderThumb else colors.textDisabled, radius, Offset(x, thumbY))
        drawCircle(colors.sliderThumbBorder, radius, Offset(x, thumbY), style = Stroke(StratumDimens.Divider.toPx()))
    }
}

/** 트랙 위아래 끝과 슬라이더 끝 사이 여백. 손잡이가 끝에 와도 잘리지 않고 손가락으로 잡기 쉽게 반지름보다 넉넉하다. */
private val VerticalSliderInset = 16.dp
