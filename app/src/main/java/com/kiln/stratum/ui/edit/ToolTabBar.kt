package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import com.kiln.stratum.R

/**
 * 커버 화면(Compact) 아래 도구 탭 줄. 기능이 있는 도구(지금은 자르기·변형)만 누를 수 있다.
 * 누르면 그 도구를 고르고, 다시 누르면 내려놓는다. 하위 도구가 있는 도구는 길게 누르면 하위 도구 메뉴가 뜬다.
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
    val haptic = LocalHapticFeedback.current
    val menuLabel = stringResource(R.string.subtool_menu)
    NavigationBar(
        modifier = modifier.testTag(EditScreenTags.TOOL_TAB_BAR),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        for (tool in EditTool.tabTools) {
            key(tool) {
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
                        Box {
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
                    modifier = Modifier
                        .testTag(EditScreenTags.tabTool(tool))
                        .then(if (itemEnabled && tool.hasSubTools) Modifier.longPressFirst(openMenu) else Modifier)
                        // 칸에는 짧은 이름을 보이고, 화면 읽기 기능은 긴 이름(예: 자르기·변형)을 읽게 한다.
                        // 안쪽 의미를 지우므로 역할·선택·누르기를 여기서 다시 알린다.
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
        }
    }
}
