package com.kiln.stratum.ui.edit

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R

/** 아래 패널을 펼쳤을 때 내용이 들어갈 자리의 높이. 레이어·조정 내용이 생기면 다시 정한다. */
private val BottomPanelBodyHeight = 240.dp

/** 메인 가로 오른쪽 패널의 폭 */
private val SidePanelWidth = 320.dp

/**
 * 메인 세로(Medium) 아래 접이식 패널. 레이어·조정 탭은 아직 기능이 없어 누를 수 없고, 접기·펼치기만 된다.
 *
 * 탭은 왼쪽에 붙이고 접기 버튼은 오른쪽 끝에 둔다. 가운데를 지나는 세로 힌지에 닿지 않는다.
 * 왼쪽에는 도구 막대가 있어, 오른쪽과 아래 시스템 바만 피한다.
 */
@Composable
fun BottomPanel(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(EditScreenTags.BOTTOM_PANEL),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelTab(R.string.tool_layers)
                PanelTab(R.string.tool_adjust)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggle) {
                    Icon(
                        painter = painterResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less),
                        contentDescription = stringResource(if (expanded) R.string.panel_collapse else R.string.panel_expand)
                    )
                }
            }
            if (expanded) {
                // 레이어 목록·조정 슬라이더가 들어갈 자리
                Spacer(Modifier.fillMaxWidth().height(BottomPanelBodyHeight))
            }
        }
    }
}

/** 아래 패널의 탭 하나. 아직 기능이 없어 누를 수 없다. */
@Composable
private fun PanelTab(@StringRes label: Int) {
    TextButton(onClick = {}, enabled = false) {
        Text(stringResource(label))
    }
}

/**
 * 메인 가로(Expanded) 오른쪽 고정 패널. CLAUDE.md 6장대로 조정과 레이어를 위아래로 함께 보여 준다.
 * 내용은 아직 없고 제목만 있다. 누를 것이 없어 힌지를 피할 것도 없다(내용을 넣을 때 피하게 한다).
 */
@Composable
fun SidePanel(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxHeight().width(SidePanelWidth).testTag(EditScreenTags.SIDE_PANEL),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
        ) {
            PanelSection(R.string.tool_adjust, Modifier.weight(1f))
            HorizontalDivider()
            PanelSection(R.string.tool_layers, Modifier.weight(1f))
        }
    }
}

/** 오른쪽 패널의 한 칸(제목과 내용 자리). */
@Composable
private fun PanelSection(@StringRes title: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(title),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall
        )
    }
}
