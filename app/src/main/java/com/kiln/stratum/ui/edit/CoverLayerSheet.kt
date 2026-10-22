package com.kiln.stratum.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/** 레이어 시트가 반 높이일 때 캔버스 높이에서 차지할 수 있는 몫 */
private const val HALF_SHEET_FRACTION = 0.55f

/** 머리(손잡이와 제목 줄)를 이만큼 넘게 끌면 닫거나(아래로) 다 펼친다(위로) */
private val SheetDragThreshold = 48.dp

/**
 * 커버 화면 레이어 시트(결정 기록 '커버 화면 개편' Q2 A). 상단 바의 레이어 버튼으로 켜면 한 줄 막대 위(캔버스 아래쪽 위)에 올라온다.
 * 도구를 고르지 않아 막대가 안내 글뿐이면 막대 자리까지 덮는다(편집 화면이 정함, 2026-10-22 사용자 요청). 그때는 덮은 막대 높이([extra])만큼 더 길어져
 * 위 끝 자리가 막대 위에 뜰 때와 같다.
 * 머리(맨 위 가는 손잡이와 제목·버튼 줄)를 아래로 끌면 닫히고([onClose]) 위로 끌면 다 펼친다. 제목을 누르면 반 높이·전체 높이를 오간다.
 * 레이어 버튼을 다시 누르거나 뒤로 가기로도 닫힌다(편집 화면). 캔버스를 쓰거나 도구를 바꿔도 열려 있다(메인 패널의 '직접 끄기 전까지 유지'와 같다).
 * 내용은 메인 화면의 떠 있는 레이어 패널과 같다: 제목 줄의 버튼 줄([LayerToolbar]), 그 아래 목록·불투명도·혼합 모드([LayerColumn]).
 * 높이는 내용만큼이고, 반 높이는 [maxHeight](캔버스 높이)의 [HALF_SHEET_FRACTION], 전체 높이는 [maxHeight]까지다(넘치면 목록을 밀어 본다). 둘 다 [extra]를 더한다.
 * 화면 읽기 기능에는 창 이름 '레이어'와 '닫기' 동작을 알린다(메인 패널과 같다).
 */
@Composable
fun CoverLayerSheet(controls: LayerControls, onClose: () -> Unit, maxHeight: Dp, modifier: Modifier = Modifier, extra: Dp = 0.dp) {
    val colors = StratumTheme.colors
    var full by rememberSaveable { mutableStateOf(false) }
    // 머리를 끄는 동안 아래로 끈 만큼 시트가 따라 내려간다(위로는 따라가지 않고 놓을 때 펼친다)
    var dragged by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { SheetDragThreshold.toPx() }
    val title = stringResource(R.string.tool_layers)
    val closeLabel = stringResource(R.string.floating_panel_close, title)
    val toggleLabel = stringResource(if (full) R.string.sheet_collapse else R.string.sheet_expand)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = (if (full) maxHeight else maxHeight * HALF_SHEET_FRACTION) + extra)
            .graphicsLayer { translationY = dragged.coerceAtLeast(0f) }
            .testTag(EditScreenTags.COVER_LAYER_SHEET)
            .semantics {
                paneTitle = title
                customActions = listOf(
                    CustomAccessibilityAction(closeLabel) {
                        onClose()
                        true
                    }
                )
            },
        shape = StratumShapes.Sheet,
        color = colors.panel,
        contentColor = colors.textPrimary,
        border = BorderStroke(StratumDimens.Divider, colors.fieldBorder)
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = rememberDraggableState { delta -> dragged += delta },
                        orientation = Orientation.Vertical,
                        onDragStarted = { dragged = 0f },
                        onDragStopped = {
                            when {
                                dragged > threshold -> onClose()
                                dragged < -threshold -> full = true
                            }
                            dragged = 0f
                        }
                    )
            ) {
                // 끌 수 있다는 표시(가는 가로 막대)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(colors.textDisabled, StratumShapes.Thumbnail)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 제목 자리를 누르면 반 높이·전체 높이를 오간다(누르는 영역은 버튼 줄 앞 전체 × 48dp)
                    Text(
                        text = title,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = toggleLabel) { full = !full }
                            .padding(start = 16.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .semantics { heading() }
                            .testTag(EditScreenTags.COVER_LAYER_SHEET_HANDLE),
                        color = colors.textPrimary,
                        style = MaterialTheme.typography.titleSmall
                    )
                    LayerToolbar(controls)
                }
            }
            HorizontalDivider(color = colors.divider)
            LayerColumn(controls, Modifier.weight(1f, fill = false).padding(horizontal = 12.dp))
        }
    }
}

/**
 * 커버를 가로로 돌렸을 때의 레이어 패널(사용자 피드백 12차 3: "커버화면 배치가 돌아간형태"): 레이어 시트를 돌려 캔버스 오른쪽(세로 막대 왼쪽)에
 * 붙인 것이다. 폭은 [CoverSidePanelWidth]이고 높이는 내용만큼, 캔버스 높이를 넘으면 목록을 밀어 본다. 도구를 고르지 않아 막대가 안내 글뿐이면
 * 편집 화면이 막대 자리까지 덮게 둔다(그때는 덮은 막대 폭([extra])만큼 넓어져 왼쪽 끝 자리가 막대 옆에 뜰 때와 같다, 시트와 같은 규칙).
 * 머리(제목·버튼 줄)를 오른쪽으로 끌면 닫히고([onClose]), 레이어 버튼을 다시 누르거나 뒤로 가기로도 닫힌다(편집 화면).
 * 내용·이름표·화면 읽기는 레이어 시트([CoverLayerSheet])와 같다.
 */
@Composable
fun CoverLayerPanel(controls: LayerControls, onClose: () -> Unit, modifier: Modifier = Modifier, extra: Dp = 0.dp) {
    val colors = StratumTheme.colors
    // 머리를 끄는 동안 오른쪽으로 끈 만큼 패널이 따라간다(왼쪽으로는 따라가지 않음)
    var dragged by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { SheetDragThreshold.toPx() }
    val title = stringResource(R.string.tool_layers)
    val closeLabel = stringResource(R.string.floating_panel_close, title)
    Surface(
        modifier = modifier
            .width(CoverSidePanelWidth + extra)
            .graphicsLayer { translationX = dragged.coerceAtLeast(0f) }
            .testTag(EditScreenTags.COVER_LAYER_SHEET)
            .semantics {
                paneTitle = title
                customActions = listOf(
                    CustomAccessibilityAction(closeLabel) {
                        onClose()
                        true
                    }
                )
            },
        shape = StratumShapes.SidePanel,
        color = colors.panel,
        contentColor = colors.textPrimary,
        border = BorderStroke(StratumDimens.Divider, colors.fieldBorder)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = rememberDraggableState { delta -> dragged += delta },
                        orientation = Orientation.Horizontal,
                        onDragStarted = { dragged = 0f },
                        onDragStopped = {
                            if (dragged > threshold) onClose()
                            dragged = 0f
                        }
                    )
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .padding(start = 16.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .semantics { heading() }
                        .testTag(EditScreenTags.COVER_LAYER_SHEET_HANDLE),
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleSmall
                )
                LayerToolbar(controls)
            }
            HorizontalDivider(color = colors.divider)
            LayerColumn(controls, Modifier.weight(1f, fill = false).padding(horizontal = 12.dp))
        }
    }
}

/** 커버를 가로로 돌렸을 때 캔버스 오른쪽에 붙는 패널(레이어·곡선)의 폭. 메인 화면 떠 있는 패널(280dp)과 같다. */
internal val CoverSidePanelWidth = 280.dp
