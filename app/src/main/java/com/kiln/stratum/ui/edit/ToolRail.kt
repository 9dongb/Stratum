package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 메인 화면(Medium·Expanded) 왼쪽 도구 막대. 도구는 아직 기능이 없어 누를 수 없다.
 * 배경은 아래 끝까지 칠하고, 버튼만 시스템 바·카메라 구멍을 피한다.
 */
@Composable
fun ToolRail(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxHeight().testTag(EditScreenTags.TOOL_RAIL),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom))
                .width(72.dp)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (tool in EditTool.railTools) {
                IconButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag(EditScreenTags.railTool(tool))
                ) {
                    ToolIcon(tool, contentDescription = stringResource(tool.label))
                }
            }
        }
    }
}
