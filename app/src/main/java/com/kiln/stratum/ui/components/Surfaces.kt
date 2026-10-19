package com.kiln.stratum.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/** 메뉴: 모서리 6dp, 패널 색, 1dp 테두리(결정 기록 '디자인(시안)') */
@Composable
fun StratumDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = StratumTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = StratumShapes.Menu,
        containerColor = colors.panel,
        border = BorderStroke(StratumDimens.Divider, colors.fieldBorder),
        content = content
    )
}

/**
 * 스위치. 켜지면 주요 색 바탕에 흰 손잡이, 꺼지면 입력칸 바탕·테두리에 설명색 손잡이다.
 * M3 기본은 어두운 테마에서 꺼진 손잡이·바탕이 거의 같은 색이라 잘 보이지 않는다.
 * 줄 전체를 눌러 바꾸는 곳은 [onCheckedChange]를 null로 둔다.
 */
@Composable
fun StratumSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = StratumTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.textDescription,
            uncheckedTrackColor = colors.fieldBackground,
            uncheckedBorderColor = colors.fieldBorder,
            disabledCheckedThumbColor = colors.textDisabled,
            disabledCheckedTrackColor = colors.divider,
            disabledUncheckedThumbColor = colors.textDisabled,
            disabledUncheckedTrackColor = colors.fieldBackground,
            disabledUncheckedBorderColor = colors.divider
        )
    )
}

/** 상단 바 색: 기본 배경, 기본색 제목, 보조색 아이콘 */
// TopAppBarColors를 만드는 함수가 아직 실험 기능 이름표를 달고 있어 opt-in이 필요하다
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun stratumTopAppBarColors(): TopAppBarColors {
    val colors = StratumTheme.colors
    return TopAppBarDefaults.topAppBarColors(
        containerColor = colors.background,
        scrolledContainerColor = colors.background,
        titleContentColor = colors.textPrimary,
        navigationIconContentColor = colors.textSecondary,
        actionIconContentColor = colors.textSecondary
    )
}

/** 구분선을 그릴 가장자리. 시작·끝은 글자 방향(오른쪽에서 왼쪽으로 쓰는 언어)을 따른다. */
enum class DividerEdge { Top, Bottom, Start, End }

/**
 * 가장자리에 구분선을 안쪽으로 그린다. 크기를 바꾸지 않아 배치가 움직이지 않는다(상단 바 아래, 도구 막대 오른쪽 등).
 */
fun Modifier.edgeDivider(edge: DividerEdge, color: Color, thickness: Dp = StratumDimens.Divider): Modifier = drawWithContent {
    drawContent()
    val line = thickness.toPx()
    val rtl = layoutDirection == LayoutDirection.Rtl
    when (edge) {
        DividerEdge.Top -> drawRect(color, Offset.Zero, Size(size.width, line))
        DividerEdge.Bottom -> drawRect(color, Offset(0f, size.height - line), Size(size.width, line))
        DividerEdge.Start -> drawRect(color, Offset(if (rtl) size.width - line else 0f, 0f), Size(line, size.height))
        DividerEdge.End -> drawRect(color, Offset(if (rtl) 0f else size.width - line, 0f), Size(line, size.height))
    }
}

/**
 * 세로로 밀어 보는 내용의 위·아래 끝을 바탕색 [color] 쪽으로 흐리게 그린다. 더 밀어 볼 내용이 있는 쪽만 흐려,
 * 칸이 더 있다는 것을 알린다(안드로이드의 가장자리 흐림과 같다). [state]는 그 내용의 스크롤 상태다. 크기는 바꾸지 않는다.
 */
fun Modifier.scrollFadeEdges(state: ScrollState, color: Color, length: Dp = 24.dp): Modifier = drawWithContent {
    drawContent()
    val fade = length.toPx().coerceAtMost(size.height / 2)
    val clear = color.copy(alpha = 0f)
    if (state.canScrollBackward) {
        drawRect(Brush.verticalGradient(listOf(color, clear), startY = 0f, endY = fade), Offset.Zero, Size(size.width, fade))
    }
    if (state.canScrollForward) {
        val top = size.height - fade
        drawRect(Brush.verticalGradient(listOf(clear, color), startY = top, endY = size.height), Offset(0f, top), Size(size.width, fade))
    }
}
