package com.kiln.stratum.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs

/**
 * 앱의 슬라이더(결정 기록 '디자인(시안)'): 얇은 트랙(3dp, 끝은 각짐)과 흰 원 손잡이(14dp).
 * [centered]면 0을 가운데에 두고 0에서 지금 값까지 칠한다(양쪽으로 바꾸는 보정 값). 아니면 왼쪽 끝부터 칠한다.
 * M3 Slider를 그대로 써서 끌기·누르기·화면 읽기 기능의 값 조절과 차지하는 높이(48dp)는 같다.
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
    centered: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        interactionSource = interactionSource,
        thumb = { SliderThumb(enabled) },
        track = { state -> SliderTrack(state, enabled, centered) }
    )
}

/** M3 Slider는 손잡이 자리를 16dp보다 작게 두지 않는다(requiredSizeIn). 그 자리 가운데에 원을 그린다. */
private val ThumbSlot = 16.dp

/** 흰 원 손잡이. 라이트 테마는 흰 패널 위에서도 보이게 테두리를 두른다. */
@Composable
private fun SliderThumb(enabled: Boolean) {
    val colors = StratumTheme.colors
    Box(Modifier.size(ThumbSlot), contentAlignment = Alignment.Center) {
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
