package com.kiln.stratum.ui.edit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.kiln.stratum.R
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.layout.zoomChipSpot
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 캔버스 왼쪽 위의 배율 표시(예: 250%, 사용자 피드백 10차 8: "비율 표시 중앙에 보이는 걸 제거하고 비율 조정 시에만 좌측 상단에 보여지고
 * 사라지게 해주세요. (약간의 시간을 두고 그라데이션으로 사라지도록 해서 사용자가 비율부분을 클릭하면 화면에 맞춰지게 해주세요)").
 * 손으로 배율을 바꿀 때만([CanvasViewState.zoomChanges]) 보이고, 마지막으로 바꾼 뒤 [ZOOM_CHIP_HOLD_MILLIS] 동안 있다가
 * [ZOOM_CHIP_FADE_MILLIS] 동안 서서히 사라진다. 보이는 동안 누르면 화면에 맞추고 맞춘 배율을 잠깐 보인다. 누르는 영역은 48dp다(Material Surface).
 * 접히는 선(힌지)과 도구 옵션 띠([avoid], 창 좌표)를 피해 놓는다(띠가 왼쪽 위에 있으면 그 오른쪽). [documentSize]는 보이는(돌린) 문서 크기다.
 * 캔버스 영역 전체를 덮는 자리에 두고, 터치는 표시만 받는다. 숨은 동안의 맞추기는 캔버스의 화면 읽기 동작('화면에 맞추기')이 한다.
 */
@Composable
fun CanvasZoomChip(viewState: CanvasViewState, documentSize: PixelSize, modifier: Modifier = Modifier, avoid: () -> Region? = { null }) {
    val changes = viewState.zoomChanges
    val alpha = remember(viewState) { Animatable(0f) }
    LaunchedEffect(viewState, changes) {
        if (changes == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(ZOOM_CHIP_HOLD_MILLIS)
        alpha.animateTo(0f, tween(ZOOM_CHIP_FADE_MILLIS, easing = LinearEasing))
    }
    // 사라지는 동안은 그릴 때만 옅어지고(다시 그리지 않음), 다 사라지면 빠진다
    val shown by remember(alpha) { derivedStateOf { alpha.value > 0f } }
    if (!shown) return
    val scale = viewState.scaleFor(documentSize) ?: return
    val percent = zoomPercentText(scale)
    val description = stringResource(R.string.canvas_zoom_fit_description, percent)
    val hinges = LocalHinges.current
    Layout(
        content = {
            Surface(
                onClick = viewState::fit,
                // 화면 읽기 기능에는 숫자만이 아니라 누르면 무엇을 하는지 알려 준다
                modifier = Modifier
                    .graphicsLayer { this.alpha = alpha.value }
                    .testTag(EditScreenTags.ZOOM_CHIP)
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick {
                            viewState.fit()
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
        },
        modifier = modifier
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val margin = ZoomChipMargin.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 구석에 놓고, 곧 이어지는 배치에서 다시 놓는다
            val origin = coordinates?.positionInWindow()
            if (origin == null) {
                placeable.place(margin, margin)
                return@layout
            }
            val left = origin.x.roundToInt()
            val top = origin.y.roundToInt()
            val hingeMargin = HingeMargin.roundToPx()
            // 보이는 칸(높이 32dp)은 누르는 자리(48dp) 가운데라, 누르는 자리를 캔버스 위 끝에 붙이면 보이는 칸이 위에서 8dp 떨어진다
            // (도구 옵션 띠의 위 끝과 같은 줄)
            val (x, y) = zoomChipSpot(
                x = left + margin,
                y = top,
                width = placeable.width,
                height = placeable.height,
                verticalBands = hinges.filter { it.isVertical }.map { it.band(hingeMargin) },
                horizontalBands = hinges.filter { !it.isVertical }.map { it.band(hingeMargin) },
                avoid = avoid(),
                gap = margin
            )
            placeable.place(x - left, y - top)
        }
    }
}

/** 배율을 마지막으로 바꾼 뒤 배율 표시가 그대로 보이는 시간(밀리초). 폴드8에서 써 보고 고칠 수 있게 한곳에 둔다. */
const val ZOOM_CHIP_HOLD_MILLIS = 1_000L

/** 배율 표시가 서서히 사라지는 시간(밀리초) */
const val ZOOM_CHIP_FADE_MILLIS = 500

/** 배율 표시와 캔버스 끝·도구 옵션 띠 사이 여백 */
private val ZoomChipMargin = 8.dp
