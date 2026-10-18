package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.layout.HingeAvoidingScrollColumn
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 메인 화면(Medium·Expanded) 왼쪽 도구 막대. 기능이 있는 도구만 누를 수 있다.
 * 누르면 그 도구를 고르고, 다시 누르면 내려놓는다. 하위 도구가 있는 도구는 길게 누르면 하위 도구 메뉴가 뜬다.
 * 작업 공간을 넓게 쓰려고 폭은 버튼(48dp)에 양쪽 4dp만 더한 [ToolRailWidth]다(결정 기록 '화면 공간').
 * 배경은 아래 끝까지 칠하고, 버튼만 시스템 바·카메라 구멍을 피한다.
 * 가로로 지나가는 힌지가 막대를 지나면, 그 자리의 버튼을 힌지 띠 아래로 비켜 놓는다(CLAUDE.md 6장).
 *
 * @param enabled 문서가 열려 있어 도구를 쓸 수 있는지
 */
@Composable
fun ToolRail(
    selection: ToolSelection,
    enabled: Boolean,
    onChoose: (SubTool) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .edgeDivider(DividerEdge.End, StratumTheme.colors.divider)
            .testTag(EditScreenTags.TOOL_RAIL),
        color = StratumTheme.colors.background
    ) {
        // 도구가 막대 높이보다 많으면(가로로 눕힌 폰처럼 낮은 화면) 위아래로 밀어 본다
        HingeAvoidingScrollColumn(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom))
                .width(ToolRailWidth)
                .padding(vertical = 8.dp),
            gap = 4.dp
        ) {
            for (tool in EditTool.railTools) {
                Box(Modifier.width(ToolRailWidth), contentAlignment = Alignment.Center) {
                    RailToolButton(tool, selection, enabled && tool.available, onChoose)
                }
            }
        }
    }
}

/** 도구 막대 폭(시스템 바·카메라 구멍 여백은 빼고) */
internal val ToolRailWidth = 56.dp

/** 도구 막대의 버튼 하나. 고른 도구는 모서리 8dp 사각형 배경(선택 배경)과 선택 색 아이콘으로 보인다(결정 기록 '디자인(시안)'). */
@Composable
private fun RailToolButton(tool: EditTool, selection: ToolSelection, enabled: Boolean, onChoose: (SubTool) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val selected = selection.active == tool
    val menuLabel = stringResource(R.string.subtool_menu)
    val openMenu = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        menuOpen = true
    }
    Box {
        StratumIconButton(
            onClick = { selection.tap(tool) },
            enabled = enabled,
            selected = selected,
            modifier = Modifier
                .testTag(EditScreenTags.railTool(tool))
                .then(
                    if (enabled && tool.hasSubTools) {
                        // 화면 읽기 기능에서도 길게 누르기로 메뉴를 연다
                        Modifier.longPressFirst(openMenu).semantics {
                            onLongClick(menuLabel) {
                                openMenu()
                                true
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .semantics { this.selected = selected }
        ) {
            ToolIcon(tool, contentDescription = stringResource(tool.label))
        }
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
}
