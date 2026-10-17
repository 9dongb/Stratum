package com.kiln.stratum.ui.edit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow

/**
 * 커버 화면(Compact) 아래 도구 탭 줄. 도구는 아직 기능이 없어 누를 수 없다.
 * 커버 화면에는 접히는 부분이 없어 힌지를 보지 않는다. 아래 시스템 바는 NavigationBar가 피한다.
 */
@Composable
fun ToolTabBar(modifier: Modifier = Modifier) {
    NavigationBar(
        modifier = modifier.testTag(EditScreenTags.TOOL_TAB_BAR),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        for (tool in EditTool.tabTools) {
            val label = stringResource(tool.label)
            NavigationBarItem(
                selected = false,
                onClick = {},
                enabled = false,
                icon = { ToolIcon(tool, contentDescription = null) },
                label = { Text(stringResource(tool.shortLabel), maxLines = 1, overflow = TextOverflow.Clip) },
                // 칸에는 짧은 이름을 보이고, 화면 읽기 기능은 긴 이름(예: 자르기·변형)을 읽게 한다
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Tab
                    disabled()
                }
            )
        }
    }
}
