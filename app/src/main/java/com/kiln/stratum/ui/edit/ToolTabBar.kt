package com.kiln.stratum.ui.edit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumRadius
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/**
 * 커버 화면(Compact) 아래 도구 탭 줄. 기능이 있는 도구만 누를 수 있다.
 * 누르면 그 도구를 고르고, 다시 누르면 내려놓는다. 하위 도구가 있는 도구는 길게 누르면 하위 도구 메뉴가 뜬다.
 * 칸은 폭을 똑같이 나누되 [MIN_TAB_WIDTH]보다 좁아지면 그 폭으로 두고 옆으로 밀어 본다(누르는 영역 48dp 이상).
 * 고른 도구가 가려 있으면 보이게 민다(메인 화면에서 고르고 접었을 때).
 * 커버 화면에는 접히는 부분이 없어 힌지를 보지 않는다. 아래 시스템 바는 NavigationBar가 피한다.
 *
 * @param enabled 문서가 열려 있어 도구를 쓸 수 있는지
 */
@Composable
fun ToolTabBar(
    selection: ToolSelection,
    enabled: Boolean,
    onChoose: (SubTool) -> Unit,
    modifier: Modifier = Modifier
) {
    val tools = EditTool.tabTools
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // 누를 때 번지는 표시는 M3 안쪽에서 알약 모양이라 이 줄에서만 끈다(고른 칸은 사각형이 바로 보인다)
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    NavigationBar(
        modifier = modifier.edgeDivider(DividerEdge.Top, StratumTheme.colors.divider).testTag(EditScreenTags.TOOL_TAB_BAR),
        containerColor = StratumTheme.colors.background
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val itemWidth = maxOf(maxWidth / tools.size, MIN_TAB_WIDTH)
            // 고른 도구가 가려 있으면 보이게 민다
            LaunchedEffect(selection.active, itemWidth) {
                val index = tools.indexOf(selection.active)
                if (index < 0) return@LaunchedEffect
                val itemPx = with(density) { itemWidth.toPx() }
                val start = (index * itemPx).roundToInt()
                val end = ((index + 1) * itemPx).roundToInt()
                when {
                    start < scroll.value -> scroll.animateScrollTo(start)
                    end > scroll.value + scroll.viewportSize -> scroll.animateScrollTo(end - scroll.viewportSize)
                }
            }
            Row(Modifier.horizontalScroll(scroll).testTag(EditScreenTags.TOOL_TAB_ROW)) {
                for (tool in tools) {
                    key(tool) {
                        // 칸 하나를 정한 폭의 줄로 감싸, 칸이 나누는 폭(weight)을 그 안에서 쓰게 한다
                        Row(Modifier.width(itemWidth)) { TabToolItem(tool, selection, enabled, onChoose) }
                    }
                }
            }
        }
    }
    }
}

/**
 * 탭 칸 하나. 칸에는 짧은 이름을 보이고, 화면 읽기 기능은 긴 이름(예: 자르기·변형)을 읽는다.
 * 고른 칸은 도구 막대처럼 아이콘 뒤 모서리 8dp 사각형(선택 배경)과 선택 색 아이콘·이름으로 보인다(사용자 결정).
 * M3의 고른 표시는 알약 모양만 되어 끄고(투명), 사각형은 아이콘 뒤에 그린다(칸 크기는 그대로).
 */
@Composable
private fun RowScope.TabToolItem(tool: EditTool, selection: ToolSelection, enabled: Boolean, onChoose: (SubTool) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val menuLabel = stringResource(R.string.subtool_menu)
    val label = stringResource(tool.label)
    val itemEnabled = enabled && tool.available
    val selected = selection.active == tool
    var menuOpen by remember { mutableStateOf(false) }
    val openMenu = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuOpen = true
    }
    NavigationBarItem(
        selected = selected,
        onClick = { selection.tap(tool) },
        enabled = itemEnabled,
        icon = {
            // 메뉴는 아이콘 옆에 붙인다. 칸 자체를 감싸면 칸의 폭 나누기(weight)가 깨진다.
            Box(if (selected) Modifier.selectedToolSquare(StratumTheme.colors.selectedBackground) else Modifier) {
                ToolIcon(tool, contentDescription = null)
                if (tool.hasSubTools) {
                    SubToolMenu(
                        tool = tool,
                        expanded = menuOpen,
                        current = selection.modeOf(tool),
                        onDismiss = { menuOpen = false },
                        onChoose = {
                            menuOpen = false
                            onChoose(it)
                        }
                    )
                }
            }
        },
        label = { Text(stringResource(tool.shortLabel), maxLines = 1, overflow = TextOverflow.Clip) },
        colors = tabItemColors(),
        modifier = Modifier
            .testTag(EditScreenTags.tabTool(tool))
            .then(if (itemEnabled && tool.hasSubTools) Modifier.longPressFirst(openMenu) else Modifier)
            // 안쪽 의미를 지우므로 역할·선택·누르기를 여기서 다시 알린다
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Tab
                this.selected = selected
                if (itemEnabled) {
                    onClick(label) {
                        selection.tap(tool)
                        true
                    }
                    if (tool.hasSubTools) {
                        onLongClick(menuLabel) {
                            openMenu()
                            true
                        }
                    }
                } else {
                    disabled()
                }
            }
    )
}

/** 탭 칸 색: M3 고른 표시(알약)는 투명, 고른 칸은 선택 색, 나머지 아이콘은 보조 색·이름은 설명색 */
@Composable
private fun tabItemColors(): NavigationBarItemColors {
    val colors = StratumTheme.colors
    return NavigationBarItemDefaults.colors(
        selectedIconColor = colors.selectedContent,
        selectedTextColor = colors.selectedContent,
        indicatorColor = Color.Transparent,
        unselectedIconColor = colors.textSecondary,
        unselectedTextColor = colors.textDescription,
        disabledIconColor = colors.textDisabled,
        disabledTextColor = colors.textDisabled
    )
}

/** 아이콘 뒤에 고른 도구 사각형을 그린다. 아이콘 밖으로 그리지만 크기를 바꾸지 않아 칸 배치는 그대로다. */
private fun Modifier.selectedToolSquare(color: Color): Modifier = drawBehind {
    val square = StratumDimens.TabToolSquare.toSize()
    drawRoundRect(
        color = color,
        topLeft = Offset((size.width - square.width) / 2, (size.height - square.height) / 2),
        size = square,
        cornerRadius = CornerRadius(StratumRadius.Control.toPx())
    )
}

/** 탭 칸의 가장 좁은 폭. 이보다 좁아지면 옆으로 밀어 본다. 짧은 이름 한 줄과 누르는 영역(48dp)이 들어간다. */
private val MIN_TAB_WIDTH = 56.dp
