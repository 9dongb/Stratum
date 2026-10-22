package com.kiln.stratum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs

/**
 * 앱의 슬라이더(결정 기록 '디자인(시안)'): 얇은 트랙(3dp, 끝은 각짐)과 흰 원 손잡이(14dp).
 * [centered]면 0을 가운데에 두고 0에서 지금 값까지 칠한다(양쪽으로 바꾸는 보정 값). 아니면 왼쪽 끝부터 칠한다.
 * M3 Slider를 그대로 써서 끌기·누르기·화면 읽기 기능의 값 조절과 차지하는 높이(48dp)는 같다.
 * [onThumbDoubleTap]이 있으면 손잡이 둘레를 누른 터치는 슬라이더가 직접 다룬다([thumbTouch]): 끌면 튀지 않고 따라오고,
 * 두 번 톡이면 그것을 부른다(조정 슬라이더 원 두 번 톡 초기화, 2026-10-21 사용자 요청).
 */
// 손잡이·막대를 바꾸는 Slider는 아직 실험 기능이라 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StratumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    centered: Boolean = false,
    onThumbDoubleTap: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 터치를 다루는 동안 값·할 일이 바뀌어도 다시 시작하지 않고 지금 것을 쓴다
    val currentValue = rememberUpdatedState(value)
    val currentRange = rememberUpdatedState(valueRange)
    val currentChange = rememberUpdatedState(onValueChange)
    val currentFinished = rememberUpdatedState(onValueChangeFinished)
    val currentDoubleTap = rememberUpdatedState(onThumbDoubleTap)
    val geometry = remember { ThumbGeometry() }
    val handlesThumb = enabled && onThumbDoubleTap != null
    val thumbTouch = if (handlesThumb) {
        Modifier
            .onGloballyPositioned { geometry.slider = it }
            .thumbTouch(geometry, currentValue, currentRange, currentChange, currentFinished, currentDoubleTap)
    } else {
        Modifier
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        // 손잡이 터치는 M3 슬라이더의 누르기·끌기보다 바깥에 두어 먼저 받는다
        modifier = modifier.then(thumbTouch),
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        interactionSource = interactionSource,
        thumb = { SliderThumb(enabled, Modifier.onGloballyPositioned { geometry.thumb = it }) },
        track = { state -> SliderTrack(state, enabled, centered) }
    )
}

/** 슬라이더 손잡이(흰 원 자리)의 화면 테스트 이름표 */
const val SLIDER_THUMB_TAG = "sliderThumb"

/** M3 Slider는 손잡이 자리를 16dp보다 작게 두지 않는다(requiredSizeIn). 그 자리 가운데에 원을 그린다. */
private val ThumbSlot = 16.dp

/** 손잡이를 잡은 것으로 보는 가로 폭(누르는 영역 48dp, CLAUDE.md 6장). 손잡이 가운데에서 양쪽으로 반씩이다. */
private val ThumbTouchWidth = 48.dp

/**
 * 그려진 손잡이와 손잡이 터치를 받는 자리(슬라이더 바깥)가 놓인 곳. 손잡이 가운데와 트랙 길이를 M3 슬라이더가
 * 실제로 놓은 대로 읽는다(M3의 손잡이 자리 폭·배치를 짐작하지 않게, 펼친 에뮬레이터에서 짐작이 어긋남을 확인).
 */
private class ThumbGeometry {
    var slider: LayoutCoordinates? = null
    var thumb: LayoutCoordinates? = null

    /** 손잡이 가운데의 가로 자리(손잡이 터치를 받는 자리 기준, 픽셀). 아직 놓이지 않았으면 null. */
    fun centerX(): Float? {
        val slider = slider?.takeIf { it.isAttached } ?: return null
        val thumb = thumb?.takeIf { it.isAttached } ?: return null
        return slider.localPositionOf(thumb, Offset(thumb.size.width / 2f, thumb.size.height / 2f)).x
    }

    /** 손잡이 가운데가 왼쪽 끝에서 오른쪽 끝까지 움직이는 거리. M3 슬라이더의 트랙 길이(슬라이더 폭 - 손잡이 자리 폭)다. */
    fun travel(): Float? {
        val box = thumb?.takeIf { it.isAttached }?.parentLayoutCoordinates ?: return null
        val layout = box.parentLayoutCoordinates ?: return null
        return (layout.size.width - box.size.width).toFloat().takeIf { it > 0f }
    }
}

/**
 * 손잡이 둘레(가로 [ThumbTouchWidth])를 누른 터치를 M3 슬라이더보다 먼저(Initial 단계) 받아 모두 가진다.
 * 끌면 누른 자리로 튀지 않고 끈 만큼 따라오며([change]), 손을 떼면 [finished]. 움직이지 않고 두 번 톡이면 [doubleTap].
 * 한 번 톡은 아무것도 하지 않는다. 손잡이 밖을 누른 터치는 건드리지 않아 지금처럼 누른 자리로 간다.
 * 이유: M3 슬라이더는 누른 자리로 값이 튀고 놓을 때 기록해, 두 번 톡만 더하면 기록이 둘 생기고 값이 살짝 바뀐다.
 */
private fun Modifier.thumbTouch(
    geometry: ThumbGeometry,
    value: State<Float>,
    range: State<ClosedFloatingPointRange<Float>>,
    change: State<(Float) -> Unit>,
    finished: State<() -> Unit>,
    doubleTap: State<(() -> Unit)?>
): Modifier = pointerInput(Unit) {
    val reach = ThumbTouchWidth.toPx() / 2
    // 앞 톡(움직이지 않고 뗀 터치)이 끝난 때와 자리. 두 번 톡을 알아본다.
    var lastTapUp = -1L
    var lastTapX = 0f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val thumbX = geometry.centerX() ?: return@awaitEachGesture
        val travel = geometry.travel() ?: return@awaitEachGesture
        if (abs(down.position.x - thumbX) > reach) return@awaitEachGesture
        down.consume()
        val values = range.value
        val span = values.endInclusive - values.start
        val doubled = lastTapUp >= 0 && down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis &&
            abs(down.position.x - lastTapX) <= reach
        val start = value.value
        var moved = 0f
        var dragging = false
        var released = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (pointer == null) break
                if (!pointer.pressed) {
                    released = true
                    if (!dragging) {
                        if (doubled) {
                            lastTapUp = -1L
                            doubleTap.value?.invoke()
                        } else {
                            lastTapUp = pointer.uptimeMillis
                            lastTapX = down.position.x
                        }
                    }
                    break
                }
                moved += pointer.position.x - pointer.previousPosition.x
                if (!dragging && abs(moved) > viewConfiguration.touchSlop) dragging = true
                if (dragging && span > 0f) change.value((start + moved / travel * span).coerceIn(values.start, values.endInclusive))
            }
        } finally {
            // 다른 손가락 등으로 끊기거나 끄는 도중 이 자리가 사라져도(두 번 톡을 끔 등) 끈 값은 놓은 것으로 보고 기록한다(M3 슬라이더와 같다)
            if (dragging) {
                lastTapUp = -1L
                finished.value()
            }
        }
        if (!dragging && !released) lastTapUp = -1L
    }
}

/** 흰 원 손잡이. 라이트 테마는 흰 패널 위에서도 보이게 테두리를 두른다. */
@Composable
private fun SliderThumb(enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = StratumTheme.colors
    Box(modifier.size(ThumbSlot).testTag(SLIDER_THUMB_TAG), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(StratumDimens.SliderThumb)
                .clip(StratumShapes.Dot)
                .background(if (enabled) colors.sliderThumb else colors.textDisabled)
                .border(StratumDimens.Divider, colors.sliderThumbBorder, StratumShapes.Dot)
        )
    }
}

/**
 * 얇은 트랙. [centered]면 0에서 지금 값까지, 아니면 왼쪽 끝에서 지금 값까지 칠한다.
 * 손잡이 가운데가 트랙의 같은 비율 자리에 오도록 슬라이더가 트랙을 놓으므로, 비율대로 그리면 손잡이와 맞는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderTrack(state: SliderState, enabled: Boolean, centered: Boolean) {
    val colors = StratumTheme.colors
    val track = if (enabled) colors.sliderTrack else colors.divider
    val fill = if (enabled) colors.sliderFill else colors.textDisabled
    Canvas(Modifier.fillMaxWidth().height(StratumDimens.SliderTrack)) {
        val range = state.valueRange
        val origin = if (centered) ((0f - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f) * size.width else 0f
        val value = state.coercedValueAsFraction * size.width
        drawRect(track, Offset.Zero, size)
        drawRect(fill, Offset(minOf(origin, value), 0f), Size(abs(value - origin), size.height))
    }
}
