package com.kiln.stratum.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R

/** 아래 패널을 펼쳤을 때 내용이 들어갈 자리의 높이. 조정은 슬라이더 칸을 밀어 보고, 곡선 그래프가 이 높이에 들어간다. */
private val BottomPanelBodyHeight = 240.dp

/** 메인 가로 오른쪽 패널의 폭 */
private val SidePanelWidth = 320.dp

/**
 * 메인 세로(Medium) 아래 패널의 탭.
 *
 * @property available 기능이 있어 누를 수 있는지. 레이어는 레이어 패널 작업(5번)에서 켠다.
 */
enum class PanelTab(@get:StringRes val label: Int, val available: Boolean) {
    LAYERS(R.string.tool_layers, available = false),
    ADJUST(R.string.tool_adjust, available = true)
}

/**
 * 메인 세로(Medium) 아래 접이식 패널. 탭(레이어·조정)으로 내용을 바꾸고, 접기·펼치기를 한다.
 * 도구를 고르면([toolOptions]가 있으면) 탭 대신 그 도구의 이름([toolTitle])과 옵션을 보인다.
 *
 * 탭은 왼쪽에 붙이고 접기 버튼은 오른쪽 끝에 둔다. 가운데를 지나는 세로 힌지에 닿지 않는다.
 * 왼쪽에는 도구 막대가 있어, 오른쪽과 아래 시스템 바만 피한다.
 *
 * @param tab 고른 탭
 * @param tabsEnabled 탭을 누를 수 있는지(문서가 열려 있는지)
 * @param tabAction 고른 탭의 내용에 딸린 버튼(조정의 '모두 초기화'). 탭 줄 오른쪽, 접기 버튼 앞에 놓인다.
 * @param tabContent 고른 탭의 내용. 없으면 빈 자리다.
 */
@Composable
fun BottomPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toolTitle: String? = null,
    toolOptions: (@Composable () -> Unit)? = null,
    tab: PanelTab = PanelTab.ADJUST,
    onTab: (PanelTab) -> Unit = {},
    tabsEnabled: Boolean = false,
    tabAction: (@Composable () -> Unit)? = null,
    tabContent: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(EditScreenTags.BOTTOM_PANEL),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
        ) {
            val showingTool = toolOptions != null && toolTitle != null
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showingTool) {
                    PanelTitle(checkNotNull(toolTitle))
                } else {
                    for (entry in PanelTab.entries) {
                        PanelTabButton(
                            label = stringResource(entry.label),
                            selected = entry == tab,
                            enabled = tabsEnabled && entry.available,
                            onClick = { onTab(entry) },
                            modifier = Modifier.testTag(EditScreenTags.panelTab(entry))
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!showingTool && expanded) tabAction?.invoke()
                IconButton(onClick = onToggle) {
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less),
                        contentDescription = stringResource(if (expanded) R.string.panel_collapse else R.string.panel_expand)
                    )
                }
            }
            if (expanded) {
                val content = if (showingTool) toolOptions else tabContent
                if (content != null) {
                    Box(Modifier.fillMaxWidth().height(BottomPanelBodyHeight).padding(horizontal = 12.dp)) {
                        content()
                    }
                } else {
                    // 내용이 없는 탭(레이어는 5번에서)
                    Spacer(Modifier.fillMaxWidth().height(BottomPanelBodyHeight))
                }
            }
        }
    }
}

/**
 * 아래 패널의 탭 하나. 고른 탭은 글자색과 아래 막대로 보인다. 누르는 영역은 높이 48dp 이상이다.
 * 화면 읽기 기능에는 '탭'으로 알리고 고른 상태를 함께 알린다.
 */
@Composable
private fun PanelTabButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        selected -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = color, style = MaterialTheme.typography.titleSmall)
        if (selected && enabled) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(colors.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            )
        }
    }
}

/**
 * 메인 가로(Expanded) 오른쪽 고정 패널. CLAUDE.md 6장대로 조정과 레이어를 위아래로 함께 보여 준다.
 * 도구를 고르면([toolOptions]가 있으면) 맨 위에 그 도구의 이름([toolTitle])과 옵션을 보인다. 옵션은 가로로 지나가는 힌지의 띠를 피해 쌓는다.
 * 조정 칸이 남는 높이를 쓰고 그 안에서 밀어 본다. 레이어 칸은 아직 제목뿐이다(5번에서 내용과 나누기를 다시 정한다).
 *
 * @param adjustAction 조정 칸 제목 줄 오른쪽의 버튼('모두 초기화')
 * @param adjustContent 조정 칸의 내용. 문서가 없으면 null이다.
 */
@Composable
fun SidePanel(
    modifier: Modifier = Modifier,
    toolTitle: String? = null,
    toolOptions: (@Composable () -> Unit)? = null,
    adjustAction: (@Composable () -> Unit)? = null,
    adjustContent: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxHeight().width(SidePanelWidth).testTag(EditScreenTags.SIDE_PANEL),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
        ) {
            if (toolOptions != null && toolTitle != null) {
                PanelTitle(toolTitle, Modifier.padding(horizontal = 8.dp))
                Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    toolOptions()
                }
                HorizontalDivider()
            }
            PanelSection(R.string.tool_adjust, Modifier.weight(1f), action = adjustAction, content = adjustContent)
            HorizontalDivider()
            PanelSection(R.string.tool_layers)
        }
    }
}

/** 고른 도구의 이름. 패널 위쪽에 탭 대신 보인다. */
@Composable
private fun PanelTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 12.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall
    )
}

/** 오른쪽 패널의 한 칸: 제목(과 오른쪽 버튼), 그 아래 내용. 내용은 칸에 남는 높이를 쓴다. */
@Composable
private fun PanelSection(
    @StringRes title: Int,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(title),
                modifier = Modifier.weight(1f).semantics { heading() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall
            )
            action?.invoke()
        }
        if (content != null) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) {
                content()
            }
        }
    }
}
