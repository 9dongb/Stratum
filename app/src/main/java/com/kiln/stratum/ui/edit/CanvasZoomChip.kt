package com.kiln.stratum.ui.edit

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.ui.layout.HingeAvoidingTopStart
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme

/**
 * 캔버스 왼쪽 위의 배율 표시(예: 250%). 확대하거나 옮겼을 때만 보이고, 누르면 화면에 맞춘다.
 * 접히는 부분(힌지)을 피해 놓고(CLAUDE.md 6장), 누르는 영역은 48dp다(Material Surface).
 * 보기 상태가 바뀔 때마다 이것만 다시 그려진다. 캔버스 영역 전체를 덮는 자리에 놓는다.
 */
@Composable
fun CanvasZoomChip(viewState: CanvasViewState, modifier: Modifier = Modifier) {
    val view = viewState.view
    if (view !is CanvasView.Free) return
    val percent = zoomPercentText(view.scale)
    val description = stringResource(R.string.canvas_zoom_fit_description, percent)
    val fit = { viewState.view = CanvasView.Fit }
    HingeAvoidingTopStart(modifier, margin = 8.dp) {
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
            contentColor = StratumTheme.colors.textPrimary
        ) {
            Text(
                text = stringResource(R.string.canvas_zoom_percent, percent),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
