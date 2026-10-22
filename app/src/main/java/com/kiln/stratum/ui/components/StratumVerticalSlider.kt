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
import kotlin.math.abs

/**
 * 세로 슬라이더(펼친 화면 도구 옵션 띠의 옵션 창, 결정 기록 '도구 옵션 띠'): 얇은 세로 트랙(3dp, 끝은 각짐)과 흰 원 손잡이.
 * 손잡이·색은 가로 [StratumSlider]와 같다. 아래 끝이 0, 위 끝이 1이고, 아래 끝에서 손잡이까지 채움 색으로 칠한다(위로 밀면 커진다).
 * 트랙 어디를 눌러도 그 자리 값으로 가고, 끌면 따라온다. 손을 떼거나 이 자리가 사라지면 [onValueChangeFinished]를 부른다.
 * 크기는 부르는 쪽이 정한다(폭은 누르는 영역 48dp 이상). 화면 읽기 기능에는 진행 정도(0..1)와 값 조절을 알리고,
 * 이름·값 글은 부르는 쪽이 semantics로 더한다(가로 슬라이더와 같다).
 * [origin]은 채움이 시작하는 자리(0..1, 아래 끝이 0)다. 0에서 양쪽으로 바꾸는 값(조정)은 0의 자리를 넘겨 그 자리부터 손잡이까지 칠한다
 * (가로 [StratumSlider]의 centered와 같다).
 * [onThumbDoubleTap]이 있으면 손잡이 둘레(세로 [ThumbTouchHeight])를 누른 터치는 누른 자리로 튀지 않고 끈 만큼 따라오며,
 * 움직이지 않고 두 번 톡이면 그것을 부른다(가로 슬라이더의 원 두 번 톡 초기화와 같다).
 */
@Composable
fun StratumVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true,
    origin: Float = 0f,
    onThumbDoubleTap: (() -> Unit)? = null
) {
    val colors = StratumTheme.colors
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val currentDoubleTap by rememberUpdatedState(onThumbDoubleTap)
    val currentValue by rememberUpdatedState(value.coerceIn(0f, 1f))
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
                // 손잡이를 마지막으로 톡하고 뗀 때(두 번 톡을 알아본다)
                var lastThumbTap: Long? = null
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val inset = VerticalSliderInset.toPx()
                    val length = (size.height - 2 * inset).coerceAtLeast(1f)
                    fun valueAt(y: Float): Float = (1f - (y - inset) / length).coerceIn(0f, 1f)
                    // 두 번 톡이 있으면 손잡이를 잡은 터치는 튀지 않고 끈 만큼 따라온다
                    val start = currentValue
                    val onThumb = currentDoubleTap != null && abs(down.position.y - (inset + (1f - start) * length)) <= ThumbTouchHeight.toPx() / 2
                    if (!onThumb) currentOnChange(valueAt(down.position.y))
                    var moved = false
                    var releasedAt: Long? = null
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                releasedAt = change.uptimeMillis
                                break
                            }
                            if (!moved && abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) moved = true
                            if (change.positionChanged()) {
                                if (!onThumb) {
                                    currentOnChange(valueAt(change.position.y))
                                } else if (moved) {
                                    currentOnChange((start + (down.position.y - change.position.y) / length).coerceIn(0f, 1f))
                                }
                            }
                            change.consume()
                        }
                    } finally {
                        // 손을 뗐거나, 끄는 도중 이 자리가 사라졌다(옵션 창이 닫힘). 어느 쪽이든 끝낸 것으로 본다.
                        currentOnFinished()
                    }
                    val previous = lastThumbTap
                    lastThumbTap = null
                    if (onThumb && releasedAt != null && !moved) {
                        if (previous != null && down.uptimeMillis - previous <= viewConfiguration.doubleTapTimeoutMillis) {
                            currentDoubleTap?.invoke()
                        } else {
                            lastThumbTap = releasedAt
                        }
                    }
                }
            }
    ) {
        val inset = VerticalSliderInset.toPx()
        val length = (size.height - 2 * inset).coerceAtLeast(0f)
        val trackWidth = StratumDimens.SliderTrack.toPx()
        val x = size.width / 2
        val thumbY = inset + (1f - fraction) * length
        val originY = inset + (1f - origin.coerceIn(0f, 1f)) * length
        drawRect(if (enabled) colors.sliderTrack else colors.divider, Offset(x - trackWidth / 2, inset), Size(trackWidth, length))
        drawRect(if (enabled) colors.sliderFill else colors.textDisabled, Offset(x - trackWidth / 2, minOf(thumbY, originY)), Size(trackWidth, abs(originY - thumbY)))
        val radius = StratumDimens.SliderThumb.toPx() / 2
        drawCircle(if (enabled) colors.sliderThumb else colors.textDisabled, radius, Offset(x, thumbY))
        drawCircle(colors.sliderThumbBorder, radius, Offset(x, thumbY), style = Stroke(StratumDimens.Divider.toPx()))
    }
}

/** 트랙 위아래 끝과 슬라이더 끝 사이 여백. 손잡이가 끝에 와도 잘리지 않고 손가락으로 잡기 쉽게 반지름보다 넉넉하다. */
private val VerticalSliderInset = 16.dp

/** 손잡이를 잡은 것으로 보는 세로 폭(누르는 영역 48dp, CLAUDE.md 6장). 손잡이 가운데에서 위아래로 반씩이다. */
private val ThumbTouchHeight = 48.dp
