package com.kiln.stratum.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.ToolSliderTrack
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.layout.SliderBarMetrics
import com.kiln.stratum.ui.layout.Span
import com.kiln.stratum.ui.layout.sideSliderBar
import com.kiln.stratum.ui.layout.stripTop
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 펼친 화면의 도구 수치 바(사용자 피드백 12차: "펼친 화면 기준 도구 수치 조절을 오른쪽에 바에서 일괄 조절합니다. (최대 3개까지)").
 * 고른 도구의 수치 칸([sliders], 크기 → 경도 → 불투명도 → 그 밖, [splitSliders])을 캔버스 오른쪽 가장자리의 세로 슬라이더로 둔다.
 * 프로크리에이트 슬라이더 느낌으로, 패널 색 카드 안에 둥근 트랙과 밝은 알약 손잡이([ToolSliderTrack])가 위부터 쌓인다.
 * 이름표는 늘 두지 않고, 끄는 동안 바 왼쪽에 '크기 60 px' 말풍선을 띄운다. 크기·경도는 지금처럼 캔버스 가운데에 크기 원이 보인다.
 * 받은 영역(캔버스)을 다 차지하지만 터치는 카드만 받는다(카드 위를 눌러도 캔버스에 칠하지 않음). 슬라이더를 끌기 시작하면 [onTouch]를 부른다
 * (띠의 옵션 창을 닫음, 다른 칸을 누를 때와 같다).
 */
@Composable
fun ToolSliderBar(sliders: List<SliderCell>, modifier: Modifier = Modifier, onTouch: () -> Unit = {}) {
    val colors = StratumTheme.colors
    // 끄고 있는 슬라이더([SliderCell.key]). 그 이름·값을 말풍선에 보인다.
    var dragging by remember { mutableStateOf<String?>(null) }
    val currentOnTouch by rememberUpdatedState(onTouch)
    val bubble = sliders.firstOrNull { it.key == dragging }
    Layout(
        content = {
            // 카드 바탕(슬라이더 뒤). 누를 수 없는 Surface라 그 아래 캔버스로 터치가 가지 않는다.
            Surface(
                modifier = Modifier.testTag(EditScreenTags.TOOL_SLIDER_BAR),
                shape = StratumShapes.Card,
                color = colors.panel,
                border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
                shadowElevation = SliderBarElevation
            ) {}
            for (cell in sliders) {
                key(cell.key) {
                    BarSlider(
                        cell = cell,
                        onStart = {
                            dragging = cell.key
                            currentOnTouch()
                        },
                        onEnd = { if (dragging == cell.key) dragging = null }
                    )
                }
            }
            if (bubble != null) ValueBubble(bubble)
        },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            if (sliders.isEmpty()) return@layout
            val area = Region(0, 0, width, height)
            val bar = sideSliderBar(sliders.size, area, sliderBarMetrics())
            measurables[0].measure(Constraints.fixed(bar.box.width, bar.box.height)).place(bar.box.left, bar.box.top)
            bar.slots.forEachIndexed { index, slot ->
                measurables[index + 1].measure(Constraints.fixed(slot.width, slot.height)).place(slot.left, slot.top)
            }
            // 말풍선: 바 왼쪽에 끄는 슬라이더 높이 가운데로, 캔버스 안으로 민다
            val shown = sliders.indexOfFirst { it.key == dragging }
            val bubbleMeasurable = measurables.getOrNull(sliders.size + 1)
            if (shown >= 0 && bubbleMeasurable != null) {
                val margin = SliderBarMargin.roundToPx()
                val placeable = bubbleMeasurable.measure(Constraints(maxWidth = (bar.box.left - 2 * margin).coerceAtLeast(0), maxHeight = height))
                val slot = bar.slots[shown]
                val y = stripTop(Span(slot.top, slot.bottom), placeable.height, Span(margin, height - margin))
                placeable.place((bar.box.left - margin - placeable.width).coerceAtLeast(margin), y)
            }
        }
    }
}

/** 수치 바의 슬라이더 하나: 슬라이더 이름표([StripSlider.tag])·이름·값을 단다. 끄는 도중 사라지면(도구를 바꿈) 크기 원을 끈다. */
@Composable
private fun BarSlider(cell: SliderCell, onStart: () -> Unit, onEnd: () -> Unit) {
    val slider = cell.slider
    EndPreviewOnDispose(slider.preview)
    ToolSliderTrack(
        value = slider.fraction,
        onValueChange = slider.onFraction,
        onDragStart = onStart,
        onValueChangeFinished = {
            slider.onFinished()
            onEnd()
        },
        enabled = cell.enabled,
        modifier = Modifier
            .testTag(slider.tag)
            .semantics {
                contentDescription = cell.caption
                stateDescription = cell.value
            }
    )
}

/** 끄는 동안 바 옆에 뜨는 이름과 값('크기 60 px'). 슬라이더가 이름·값을 읽으므로 화면 읽기 기능에서는 뺀다(글자는 그대로 둔다). */
@Composable
private fun ValueBubble(cell: SliderCell) {
    val colors = StratumTheme.colors
    Surface(
        modifier = Modifier.testTag(EditScreenTags.TOOL_SLIDER_VALUE).semantics(mergeDescendants = true) { hideFromAccessibility() },
        shape = StratumShapes.Card,
        color = colors.panel,
        border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
        shadowElevation = SliderBarElevation
    ) {
        Text(
            text = stringResource(R.string.strip_slider_value, cell.caption, cell.value),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = colors.textPrimary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

/**
 * 수치 바의 크기(사용자 피드백 12차). 폴드8에서 써 보고 고칠 수 있게 한곳에 둔다.
 * 슬라이더 자리(누르는 영역) 굵기 48dp에 트랙 32dp, 카드 폭은 도구 막대와 같은 56dp다. 슬라이더 길이는 캔버스 높이에 맞춰 80~160dp.
 */
private val SliderBarThickness = 48.dp
private val SliderBarMinLength = 80.dp
private val SliderBarMaxLength = 160.dp
private val SliderBarPadding = 4.dp
private val SliderBarGap = 8.dp
private val SliderBarMargin = 8.dp

/** 카드 그림자(도구 옵션 띠와 같다) */
private val SliderBarElevation = 6.dp

private fun Density.sliderBarMetrics() = SliderBarMetrics(
    thickness = SliderBarThickness.roundToPx(),
    minLength = SliderBarMinLength.roundToPx(),
    maxLength = SliderBarMaxLength.roundToPx(),
    padding = SliderBarPadding.roundToPx(),
    gap = SliderBarGap.roundToPx(),
    margin = SliderBarMargin.roundToPx()
)
