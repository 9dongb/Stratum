package com.kiln.stratum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumRadius
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs

/**
 * 도구 수치 바의 슬라이더(사용자 피드백 12차: "프로크리에이트에서 가져온 슬라이더 느낌"): 모서리가 둥근 트랙과 밝은 알약 손잡이, 채움은 없다.
 * 받은 자리가 폭보다 높으면 세로(위 끝이 1), 아니면 가로(오른쪽 끝이 1)로 그린다. 트랙은 자리의 가운데에 [ToolSliderTrackThickness] 굵기다
 * (자리 자체는 누르는 영역으로 48dp 이상을 준다).
 *
 * 누른 자리로 손잡이가 튀지 않고, 트랙을 따라 끈 만큼만 움직인다(손잡이가 움직일 수 있는 길이를 끌면 처음부터 끝까지). 그래서 톡 누르기만 하면
 * 값이 바뀌지 않는다. 끌기는 트랙 방향으로 터치 허용 거리를 넘게 움직였을 때 시작하고(그 거리만큼은 빼서 처음에 튀지 않음), 이때 [onDragStart]를
 * 부른다. 끄는 동안 [onValueChange], 손을 떼거나 끄는 도중 이 자리가 사라지면 [onValueChangeFinished]를 부른다.
 * 화면 읽기 기능에는 진행 정도(0..1)와 값 넣기를 알리고, 이름·값 글은 부르는 쪽이 semantics로 더한다.
 */
@Composable
fun ToolSliderTrack(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true
) {
    val colors = StratumTheme.colors
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnStart by rememberUpdatedState(onDragStart)
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
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val vertical = size.height >= size.width
                    // 손잡이가 움직일 수 있는 길이(트랙 길이에서 손잡이 길이와 양 끝 여백을 뺀 것)
                    val travel = thumbTravel(if (vertical) size.height.toFloat() else size.width.toFloat()).coerceAtLeast(1f)
                    val slop = viewConfiguration.touchSlop
                    var start = 0f
                    // 트랙 방향으로 움직인 양(세로는 위가 +). 터치 허용 거리를 넘기 전에는 끌기가 아니다.
                    var moved = 0f
                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val delta = change.position - change.previousPosition
                            moved += if (vertical) -delta.y else delta.x
                            change.consume()
                            if (!dragging) {
                                if (abs(moved) <= slop) continue
                                dragging = true
                                start = currentValue.coerceIn(0f, 1f)
                                // 터치 허용 거리만큼은 빼서, 끌기를 시작하는 순간 값이 튀지 않게 한다
                                moved -= if (moved > 0) slop else -slop
                                currentOnStart()
                            }
                            currentOnChange((start + moved / travel).coerceIn(0f, 1f))
                        }
                    } finally {
                        // 손을 뗐거나, 끄는 도중 이 자리가 사라졌다(도구를 바꿈, 접기·펼치기). 끌었으면 끝낸 것으로 본다.
                        if (dragging) currentOnFinished()
                    }
                }
            }
    ) {
        val vertical = size.height >= size.width
        val long = if (vertical) size.height else size.width
        val cross = if (vertical) size.width else size.height
        val thickness = ToolSliderTrackThickness.toPx().coerceAtMost(cross)
        val crossStart = (cross - thickness) / 2
        val radius = CornerRadius(StratumRadius.Control.toPx())
        // 트랙(자리 가운데의 둥근 사각형)
        drawRoundRect(
            color = if (enabled) colors.sliderTrack else colors.divider,
            topLeft = if (vertical) Offset(crossStart, 0f) else Offset(0f, crossStart),
            size = if (vertical) Size(thickness, long) else Size(long, thickness),
            cornerRadius = radius
        )
        // 알약 손잡이: 트랙 굵기에서 양쪽 여백을 뺀 폭, 트랙 방향으로는 짧다. 위(오른쪽)로 갈수록 값이 크다.
        val inset = ThumbInset.toPx()
        val thumbAcross = (thickness - 2 * inset).coerceAtLeast(0f)
        val thumbAlong = ThumbLength.toPx()
        val travel = thumbTravel(long)
        val alongStart = inset + if (vertical) (1f - fraction) * travel else fraction * travel
        val thumbColor = if (enabled) colors.textSecondary else colors.textDisabled
        drawRoundRect(
            color = thumbColor,
            topLeft = if (vertical) Offset(crossStart + inset, alongStart) else Offset(alongStart, crossStart + inset),
            size = if (vertical) Size(thumbAcross, thumbAlong) else Size(thumbAlong, thumbAcross),
            cornerRadius = CornerRadius(thumbAlong / 2)
        )
    }
}

/** 트랙의 굵기(보이는 폭). 자리 자체는 누르는 영역으로 이보다 넓다. */
val ToolSliderTrackThickness = 32.dp

/** 손잡이와 트랙 가장자리 사이 여백 */
private val ThumbInset = 4.dp

/** 손잡이의 트랙 방향 길이(알약) */
private val ThumbLength = 14.dp

/** 트랙 길이 [long](px) 안에서 손잡이(앞 끝)가 움직일 수 있는 길이 */
private fun Density.thumbTravel(long: Float): Float =
    (long - 2 * ThumbInset.toPx() - ThumbLength.toPx()).coerceAtLeast(0f)
