package com.kiln.stratum.ui.edit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.LongPressTimes
import kotlin.math.abs

/**
 * 수치 칸·칩을 누른 채 끄는 축. [VERTICAL]은 위로 끌면 커진다(펼친 화면 띠, 세로 커버 막대).
 * [HORIZONTAL]은 왼쪽(캔버스 쪽)으로 끌면 커진다(가로로 돌린 커버 기둥: 세로 커버의 '위로 끌기'를 돌린 것. 기둥은 위아래로 밀어 본다).
 */
enum class ScrubAxis { VERTICAL, HORIZONTAL }

/**
 * 수치 칸·칩의 손가락 처리(공통 수치 칸 기능, 2026-10-23 사용자 요청: 칩을 누른 채 끌어 값 바꾸기와 꾹 눌러 0). 안쪽 누르기보다 먼저(Initial 단계)
 * 한 손가락을 끝까지 본다.
 * - 톡(움직이지 않고 뗌)은 아무것도 하지 않아 안쪽 누르기(슬라이더 줄·옵션 창 열기)가 받는다. 오래 눌렀다 떼도 0으로 되돌릴 값이 아니면 톡과 같다.
 * - [axis]로 터치 허용 거리를 넘게 움직이면 그 자리부터 값을 바꾼다([ScrubFullRange]가 처음~끝, 슬라이더와 같은 눈금). 손을 떼면 [StripSlider.onFinished]다.
 *   다른 축으로 먼저 움직이면 놓아 둬 밀어 보기(칩 줄·기둥·띠)가 받는다.
 * - [StripSlider.onReset]이 있으면(-100~100 값) 움직이지 않고 0.3초(메뉴 묶음, 레이어 줄과 같음) 누르면 짧은 진동이고, 그대로 떼면 0으로 되돌리고 알린다.
 *   진동 뒤라도 끌면 0이 아니라 값을 바꾼다(끌기 전에 잠깐 멈춘 것만으로 0이 되지 않게).
 * 끄는 동안은 [onScrubbing]으로 알리고, [showValue]면 제스처 알림 자리([LocalValueFeedback])에 '[caption]: [value]'를 손을 뗄 때까지 보인다
 * (커버는 손가락이 칩을 가린다). 끈 터치·0으로 되돌린 터치는 안쪽 누르기로 가지 않는다.
 */
@Composable
internal fun Modifier.valueGestures(
    slider: StripSlider,
    axis: ScrubAxis,
    caption: String,
    value: String,
    showValue: Boolean,
    onScrubbing: (Boolean) -> Unit = {}
): Modifier {
    val feedback = LocalValueFeedback.current
    val haptic = LocalHapticFeedback.current
    val resetText = stringResource(R.string.value_reset_to_zero, caption)
    val pattern = stringResource(R.string.strip_choice_description)
    // 끄는 동안 값이 바뀌어 칸이 다시 그려져도 끌기를 다시 시작하지 않고 지금 슬라이더·값을 쓴다
    val currentSlider by rememberUpdatedState(slider)
    val currentCaption = rememberUpdatedState(caption)
    val currentValue = rememberUpdatedState(value)
    val currentResetText by rememberUpdatedState(resetText)
    val scrubbingChanged by rememberUpdatedState(onScrubbing)
    return pointerInput(axis, showValue, feedback) {
        val fullRange = ScrubFullRange.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val last = arrayOfNulls<PointerInputChange>(1)
            var armed = false
            var move = withTimeoutOrNull(LongPressTimes.MENU_MILLIS) { firstMove(down.id, down.position, slop, axis, last) }
            if (move == null) {
                // 움직이지 않고 0.3초: 0으로 되돌릴 수 있는 값이면 진동으로 알리고, 떼기·끌기를 끝까지 기다린다
                if (currentSlider.onReset != null) {
                    armed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                move = firstMove(down.id, down.position, slop, axis, last)
            }
            when (move) {
                ValueMove.UP -> if (armed) {
                    last[0]?.consume()
                    currentSlider.onReset?.invoke()
                    feedback?.show(currentResetText)
                }
                ValueMove.ALONG -> {
                    val start = last[0] ?: return@awaitEachGesture
                    start.consume()
                    val from = start.position
                    val startFraction = currentSlider.fraction
                    scrubbingChanged(true)
                    if (showValue) feedback?.startScrub { pattern.format(currentCaption.value, currentValue.value) }
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            event.changes.forEach { it.consume() }
                            if (change == null || !change.pressed) break
                            // 화면에서 위가 작은 y·왼쪽이 작은 x라, 위로(왼쪽으로) 끈 만큼 커진다
                            val moved = if (axis == ScrubAxis.VERTICAL) from.y - change.position.y else from.x - change.position.x
                            currentSlider.onFraction((startFraction + moved / fullRange).coerceIn(0f, 1f))
                        }
                    } finally {
                        scrubbingChanged(false)
                        if (showValue) feedback?.endScrub()
                        currentSlider.onFinished()
                    }
                }
                ValueMove.ACROSS, ValueMove.GONE -> Unit
            }
        }
    }
}

/** 손가락이 처음 한 일: 뗌, 끄는 축으로 움직임, 다른 축으로 움직임, 사라짐(다른 것이 가져감) */
private enum class ValueMove { UP, ALONG, ACROSS, GONE }

/** 손가락 [id]가 떼거나 [start]에서 [slop]을 넘게 움직일 때까지 본다. 마지막 변화는 [last]에 둔다(그것을 소비할 수 있게). */
private suspend fun AwaitPointerEventScope.firstMove(
    id: PointerId,
    start: Offset,
    slop: Float,
    axis: ScrubAxis,
    last: Array<PointerInputChange?>
): ValueMove {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id } ?: return ValueMove.GONE
        last[0] = change
        if (change.isConsumed) return ValueMove.GONE
        if (!change.pressed) return ValueMove.UP
        val delta = change.position - start
        val along = abs(if (axis == ScrubAxis.VERTICAL) delta.y else delta.x)
        val across = abs(if (axis == ScrubAxis.VERTICAL) delta.x else delta.y)
        if (along > slop && along >= across) return ValueMove.ALONG
        if (across > slop) return ValueMove.ACROSS
    }
}
