package com.kiln.stratum.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.layout.panelRegion
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/** 떠 있는 패널의 폭(지금까지의 오른쪽 패널과 같다) */
internal val FloatingPanelWidth = 320.dp

/** 떠 있는 패널이 차지할 수 있는 가장 큰 높이. 태블릿처럼 큰 화면에서도 캔버스를 다 덮지 않게 한다. */
private val FloatingPanelMaxHeight = 640.dp

/** 떠 있는 패널과 캔버스 영역 가장자리 사이 */
private val FloatingPanelMargin = 8.dp

/**
 * 떠 있는 패널과 힌지 띠 사이에 더 두는 간격. 패널 끝에서 반쯤 밀려 보이는 슬라이더도 누르는 영역(48dp)이 띠에 닿지 않게 한다.
 */
private val FloatingPanelHingeGap = 24.dp

/**
 * 메인 화면 캔버스 오른쪽 위에 떠 있는 레이어·조정 패널의 자리(사진 2, 프로크리에이트, 결정 기록 '떠 있는 레이어·조정 패널').
 * 상단 바의 [조정][레이어] 버튼으로 열고 닫는다. 받은 영역을 다 차지하지만 터치는 패널만 받는다.
 * 자리는 배치하는 순간 자기 위치와 힌지를 읽어 정한다: 세로 힌지가 있으면 오른쪽 조각, 가로 힌지가 있으면 위 조각 안([panelRegion]).
 * 패널 높이는 내용만큼이고, 그 조각 높이(태블릿은 [FloatingPanelMaxHeight])를 넘으면 안에서 밀어 본다.
 */
@Composable
fun FloatingPanelHost(state: FloatingState, adjust: AdjustControls?, layers: LayerControls?, modifier: Modifier = Modifier) {
    val hinges = LocalHinges.current
    val panel = state.panel
    Layout(
        content = {
            when (panel) {
                MainPanel.ADJUST -> if (adjust != null) {
                    FloatingPanelCard(stringResource(R.string.tool_adjust), state::closePanel, action = { ResetAllButton(adjust) }) { inner ->
                        AdjustList(adjust, modifier = inner)
                    }
                }
                MainPanel.LAYERS -> if (layers != null) {
                    FloatingPanelCard(stringResource(R.string.tool_layers), state::closePanel, action = { LayerToolbar(layers) }) { inner ->
                        LayerColumn(layers, modifier = inner)
                    }
                }
                null -> Unit
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            val measurable = measurables.firstOrNull() ?: return@layout
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 놓지 않는다. 곧 이어지는 배치에서 놓는다.
            val origin = coordinates?.positionInWindow() ?: return@layout
            val left = origin.x.roundToInt()
            val top = origin.y.roundToInt()
            val margin = FloatingPanelMargin.roundToPx()
            val bandMargin = (HingeMargin + FloatingPanelHingeGap).roundToPx()
            val region = panelRegion(
                area = Region(left + margin, top + margin, left + width - margin, top + height - margin),
                verticalBands = hinges.filter { it.isVertical }.map { it.band(bandMargin) },
                horizontalBands = hinges.filter { !it.isVertical }.map { it.band(bandMargin) }
            )
            val placeable = measurable.measure(
                Constraints(
                    maxWidth = minOf(region.width, FloatingPanelWidth.roundToPx()).coerceAtLeast(0),
                    maxHeight = minOf(region.height, FloatingPanelMaxHeight.roundToPx()).coerceAtLeast(0)
                )
            )
            placeable.place(region.right - placeable.width - left, region.top - top)
        }
    }
}

/**
 * 떠 있는 패널의 모양: 패널 색 카드(모서리 12dp, 1dp 테두리, 그림자). 위 줄에 제목과 버튼([action]), 그 아래 내용.
 * 내용은 남는 높이만 쓰고 넘치면 스스로 밀어 본다([content]가 받는 Modifier). 화면 읽기 기능에는 [title]을 창 이름으로 알리고 '닫기' 동작을 준다.
 */
@Composable
private fun FloatingPanelCard(
    title: String,
    onClose: () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    val closeLabel = stringResource(R.string.floating_panel_close, title)
    Surface(
        modifier = Modifier
            .testTag(EditScreenTags.FLOATING_PANEL)
            .semantics {
                paneTitle = title
                customActions = listOf(
                    CustomAccessibilityAction(closeLabel) {
                        onClose()
                        true
                    }
                )
            },
        shape = StratumShapes.Dialog,
        color = StratumTheme.colors.panel,
        contentColor = StratumTheme.colors.textPrimary,
        border = BorderStroke(StratumDimens.Divider, StratumTheme.colors.fieldBorder),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    color = StratumTheme.colors.textPrimary,
                    style = MaterialTheme.typography.titleSmall
                )
                action()
            }
            HorizontalDivider(color = StratumTheme.colors.divider)
            content(Modifier.weight(1f, fill = false).padding(horizontal = 12.dp))
        }
    }
}
