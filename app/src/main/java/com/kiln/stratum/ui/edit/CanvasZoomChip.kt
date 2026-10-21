package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.TopBarInsets
import com.kiln.stratum.ui.layout.barLabelLeft
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/**
 * 상단 바 가운데의 배율 표시(예: 250%, 사용자 피드백 9차: "사진 배율 정보는 헤더 중앙에 위치시키려합니다."). 사진 2(태블릿 포토샵)처럼
 * 늘 보이고(맞춤이면 맞춤 배율), 확대하거나 옮긴 상태에서 누르면 화면에 맞춘다. 누르는 영역은 48dp다(Material Surface).
 * 바 위에 겹쳐 놓는 바 높이의 칸이다. 바의 버튼·카메라 구멍([barInsets])을 읽어, 바 가운데에 두되 버튼이나 구멍(폴드8 커버는 위 가운데)에
 * 걸리면 빈 쪽으로 비킨다([barLabelLeft]). 바의 버튼으로 알리지 않아 바를 구멍 아래로 내리지 않는다.
 * 펼친 화면 가운데의 접히는 선 위에도 둔다(사용자 결정: 이 표시만 힌지 규칙의 예외, CLAUDE.md 6장).
 * 보기 상태가 바뀔 때마다 이것만 다시 그려진다. 캔버스 크기를 아직 모르면(맞춤 배율을 모름) 보이지 않는다.
 */
@Composable
fun CanvasZoomChip(viewState: CanvasViewState, documentSize: PixelSize, barInsets: TopBarInsets, modifier: Modifier = Modifier) {
    val scale = viewState.scaleFor(documentSize) ?: return
    val free = viewState.view is CanvasView.Free
    val percent = zoomPercentText(scale)
    val description = stringResource(if (free) R.string.canvas_zoom_fit_description else R.string.canvas_zoom_description, percent)
    val fit = { viewState.view = CanvasView.Fit }
    val label: @Composable () -> Unit = {
        Text(
            text = stringResource(R.string.canvas_zoom_percent, percent),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
    Layout(
        content = {
            if (free) {
                Surface(
                    onClick = fit,
                    // 화면 읽기 기능에는 숫자만이 아니라 누르면 무엇을 하는지 알려 준다
                    modifier = Modifier
                        .testTag(EditScreenTags.ZOOM_CHIP)
                        .clearAndSetSemantics {
                            contentDescription = description
                            role = Role.Button
                            onClick {
                                fit()
                                true
                            }
                        },
                    shape = StratumShapes.Card,
                    color = StratumTheme.colors.panel,
                    contentColor = StratumTheme.colors.textPrimary,
                    content = label
                )
            } else {
                // 맞춤일 때는 누를 일이 없어 보여 주기만 한다
                Surface(
                    modifier = Modifier
                        .testTag(EditScreenTags.ZOOM_CHIP)
                        .clearAndSetSemantics { contentDescription = description },
                    shape = StratumShapes.Card,
                    color = StratumTheme.colors.panel,
                    contentColor = StratumTheme.colors.textSecondary,
                    content = label
                )
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable = measurables.first().measure(Constraints(maxWidth = width, maxHeight = height))
        layout(width, height) {
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 놓지 않는다. 곧 이어지는 배치에서 놓는다.
            val origin = coordinates?.positionInWindow() ?: return@layout
            val left = origin.x.roundToInt()
            val x = barLabelLeft(
                width = placeable.width,
                center = left + width / 2,
                barLeft = left,
                barRight = left + width,
                items = barInsets.items.values,
                holes = barInsets.holes,
                margin = barInsets.margin
            )
            placeable.place(x - left, (height - placeable.height) / 2)
        }
    }
}
