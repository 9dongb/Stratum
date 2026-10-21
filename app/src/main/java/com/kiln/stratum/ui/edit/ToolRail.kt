package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.components.DividerEdge
import com.kiln.stratum.ui.components.MenuLongPress
import com.kiln.stratum.ui.components.StratumIconButton
import com.kiln.stratum.ui.components.edgeDivider
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/**
 * 메인 화면(Medium·Expanded) 왼쪽 도구 막대. 기능이 있는 도구만 누를 수 있다.
 * 누르면 그 도구를 고르고, 다시 누르면 내려놓는다. 하위 도구가 있는 도구는 길게 누르거나(메뉴 묶음 0.3초) 고른 뒤 다시 누르면
 * 하위 도구 메뉴([menu])가 버튼 오른쪽에 뜨고, 꾹 누른 채 끌어 항목 위에서 떼면 고른다(사용자 피드백 7차).
 * 작업 공간을 넓게 쓰려고 폭은 버튼(48dp)에 양쪽 4dp만 더한 [ToolRailWidth]다(결정 기록 '화면 공간').
 * 배경은 아래 끝까지 칠하고, 버튼만 시스템 바·카메라 구멍을 피한다. 접히는 선은 보지 않는다(사용자 피드백 11차 1).
 *
 * @param enabled 문서가 열려 있어 도구를 쓸 수 있는지
 * @param menu 하위 도구 메뉴(편집 화면 맨 위에 하나, [SubToolMenuHost]가 그린다)
 */
@Composable
fun ToolRail(
    selection: ToolSelection,
    enabled: Boolean,
    onChoose: (SubTool) -> Unit,
    modifier: Modifier = Modifier,
    menu: SubToolMenuState = remember { SubToolMenuState() }
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .edgeDivider(DividerEdge.End, StratumTheme.colors.divider)
            .testTag(EditScreenTags.TOOL_RAIL),
        color = StratumTheme.colors.background
    ) {
        // 도구가 막대 높이보다 많으면(가로로 눕힌 폰처럼 낮은 화면) 위아래로 밀어 본다
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom))
                .width(ToolRailWidth)
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (tool in EditTool.railTools) {
                Box(Modifier.width(ToolRailWidth), contentAlignment = Alignment.Center) {
                    RailToolButton(tool, selection, enabled && tool.available, menu, onChoose)
                }
            }
        }
    }
}

/** 도구 막대 폭(시스템 바·카메라 구멍 여백은 빼고) */
internal val ToolRailWidth = 56.dp

/**
 * 도구 막대의 버튼 하나. 고른 도구는 모서리 8dp 사각형 배경(선택 배경)과 선택 색 아이콘으로 보인다(결정 기록 '디자인(시안)').
 * 하위 도구가 있으면 길게 누르거나 고른 뒤 다시 눌러 [menu]를 연다. 길게 누른 채 끌면 손가락 밑 항목이 하이라이트되고(짧은 진동), 떼면 고른다.
 */
@Composable
private fun RailToolButton(
    tool: EditTool,
    selection: ToolSelection,
    enabled: Boolean,
    menu: SubToolMenuState,
    onChoose: (SubTool) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val selected = selection.active == tool
    val menuLabel = stringResource(R.string.subtool_menu)
    // 버튼 자리(창 좌표). 메뉴를 그 오른쪽에 띄운다.
    val bounds = remember { arrayOfNulls<Region>(1) }
    val openMenu = { bounds[0]?.let { menu.open(tool, it, MenuSide.END) } }
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            val left = position.x.roundToInt()
            val top = position.y.roundToInt()
            bounds[0] = Region(left, top, left + coordinates.size.width, top + coordinates.size.height)
        }
    ) {
        // 메뉴를 여는 꾹 누르기는 메뉴 묶음 시간(0.3초)이다(결정 기록 '꾹 누르기 시간')
        MenuLongPress {
            StratumIconButton(
                // 고른 도구를 다시 누르면 하위 도구 메뉴를 연다(하위 도구가 없는 도구는 내려놓는다)
                onClick = { if (selected && tool.hasSubTools) openMenu() else selection.tap(tool) },
                enabled = enabled,
                selected = selected,
                modifier = Modifier
                    .testTag(EditScreenTags.railTool(tool))
                    .then(
                        if (enabled && tool.hasSubTools) {
                            Modifier.longPressFirst(
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    openMenu()
                                },
                                onDrag = { if (menu.hover(it)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                onRelease = { position ->
                                    menu.release(position)?.let {
                                        menu.close()
                                        onChoose(it)
                                    }
                                }
                            ).semantics {
                                // 화면 읽기 기능에서도 길게 누르기로 메뉴를 연다
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
        }
    }
}
