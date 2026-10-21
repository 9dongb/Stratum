package com.kiln.stratum.ui.edit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.Region
import com.kiln.stratum.ui.layout.Span
import com.kiln.stratum.ui.layout.flyoutOnLeft
import com.kiln.stratum.ui.layout.flyoutSides
import com.kiln.stratum.ui.layout.stripTop
import com.kiln.stratum.ui.theme.StratumDimens
import com.kiln.stratum.ui.theme.StratumShapes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt

/** 메인 화면에서 상단 바 버튼으로 여는, 캔버스 위에 떠 있는 패널 */
enum class MainPanel { ADJUST, LAYERS }

/**
 * 메인 화면(Medium·Expanded)의 캔버스 위에 떠 있는 것: 도구 옵션 띠 칸의 옵션 창과 레이어·조정 패널(결정 기록 '떠 있는 레이어·조정 패널').
 * 옵션 창은 잠깐 쓰는 것이라 캔버스를 누르거나, 뒤로 가기, 다른 곳(패널 버튼 등)을 누르면 닫힌다.
 * 패널은 상단 바 버튼(화면 읽기 기능은 '닫기' 동작)으로만 닫힌다(사용자 요청: 직접 끄기 전까지 유지). 패널은 한 번에 하나이고, 옵션 창과 함께 뜰 수 있다.
 * 패널은 접기·펼치기나 백그라운드 종료 뒤에도 남고(저장), 옵션 창은 남지 않는다(칸 자리를 다시 재야 해서).
 */
@Stable
class FloatingState(panel: MainPanel? = null) {

    /** 열린 패널. 없으면 null. */
    var panel: MainPanel? by mutableStateOf(panel)
        private set

    /** 옵션 창이 열린 띠 칸의 이름([StripItem.key]). 없으면 null. */
    var flyout: String? by mutableStateOf(null)
        private set

    /** 띠 칸의 자리(창 좌표). 옵션 창을 그 칸 옆에 띄운다. */
    internal val anchors = mutableStateMapOf<String, Region>()

    /** 도구 막대 버튼의 자리(창 좌표). 도구 옵션 띠를 고른 도구 옆에 띄운다([OptionStripHost]). */
    internal val toolAnchors = mutableStateMapOf<EditTool, Region>()

    /** 놓인 도구 옵션 띠의 자리(창 좌표, [OptionStripHost]가 놓을 때 알림). 띠가 없으면 null. 배율 표시가 띠를 피한다([CanvasZoomChip]). */
    internal var stripRegion: Region? by mutableStateOf(null)

    /** [key] 칸의 옵션 창을 열고, 이미 열려 있으면 닫는다. 열린 패널은 그대로 둔다. */
    fun toggleFlyout(key: String) {
        flyout = if (flyout == key) null else key
    }

    /** [target] 패널을 열고, 이미 열려 있으면 닫는다. 다른 패널이 열려 있으면 그 패널로 바꾼다. 옵션 창은 닫는다(다른 곳을 눌렀으므로). */
    fun togglePanel(target: MainPanel) {
        flyout = null
        panel = if (panel == target) null else target
    }

    /** [target] 패널을 연다(커버에서 고른 조정·레이어를 펼친 화면으로 이을 때) */
    fun openPanel(target: MainPanel) {
        flyout = null
        panel = target
    }

    fun closeFlyout() {
        flyout = null
    }

    fun closePanel() {
        panel = null
    }

    companion object {
        /** 열린 패널 이름만 저장한다(없으면 빈 문자열) */
        val Saver: Saver<FloatingState, String> = Saver(
            save = { it.panel?.name.orEmpty() },
            restore = { saved -> FloatingState(saved.takeIf { it.isNotEmpty() }?.let(MainPanel::valueOf)) }
        )
    }
}

/** 처음에는 아무것도 떠 있지 않다 */
@Composable
fun rememberFloatingState(): FloatingState = rememberSaveable(saver = FloatingState.Saver) { FloatingState() }

/**
 * 옵션 창이 떠 있는 동안 캔버스를 덮는 바탕. 캔버스에 손가락이 닿으면 옵션 창을 닫기만 하고, 그 터치는 손을 뗄 때까지 도구로 가지 않는다(그리지 않음).
 * 패널만 열려 있을 때는 없다(패널은 직접 끌 때까지 남고, 캔버스는 바로 쓴다).
 * 캔버스 도구 화면(overlay)의 맨 위에 둔다. 두 손가락 확대·이동·톡과 세 손가락 제스처는 부모(CanvasViewport)가 먼저 읽어 그대로 된다.
 * 누르는 것(clickable)이 아니라서 화면 읽기 기능에는 나오지 않는다. 화면 읽기 기능은 뒤로 가기나 같은 칸으로 닫는다.
 */
@Composable
fun FloatingDismissLayer(state: FloatingState, modifier: Modifier = Modifier) {
    if (state.flyout == null) return
    Box(
        modifier
            .testTag(EditScreenTags.FLOATING_DISMISS)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.closeFlyout()
                    down.consume()
                    // 손을 뗄 때까지 이 터치를 모두 가진다(도구 동작으로 이어지지 않게)
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
    )
}

/** 옵션 창과 영역 가장자리·칸 사이의 간격 */
private val FlyoutMargin = 8.dp
private val FlyoutGap = 4.dp

/** 옵션 창을 칸 어느 쪽에 띄울지 정할 때 보는 창 폭(가장 넓은 옵션 창: 자르기 비율 칸 216dp + 안쪽 여백) */
private val FlyoutDecisionWidth = 240.dp

/** 옵션 창 영역(창 좌표). 배치할 때 적어 두고, 다음에 옵션 창을 그릴 때 어느 쪽에 띄울지 정하는 데 읽는다(다시 그리지 않음). */
private class FlyoutArea {
    var region: Region? = null
}

/**
 * 띠 칸의 옵션 창을 그 칸 옆에 띄우는 자리. 받은 영역을 다 차지하지만 터치는 옵션 창만 받는다(빈 곳은 아래 캔버스로 간다).
 * 칸 오른쪽에 띄우고, 띠를 오른쪽 끝에 옮겨 놓아 자리가 모자라면 왼쪽에 띄운다([flyoutOnLeft], 사용자 피드백 9차).
 * 어느 쪽인지는 그리기 전에 정해, 세로 슬라이더 창의 값 표시를 칸 반대쪽에 두고 삼각형이 칸을 가리키게 한다.
 * 자리는 배치하는 순간 칸 자리(창 좌표)와 자기 위치, 힌지를 읽어 정한다. 접히는 선을 넘지 않게 칸이 있는 쪽 조각 안에 둔다([flyoutSides]).
 * 옵션 창이 그 조각보다 크면 조각 크기로 줄여 잰다(내용이 길면 스스로 밀어 본다).
 */
@Composable
fun FlyoutHost(state: FloatingState, items: List<StripItem>, modifier: Modifier = Modifier) {
    val item = items.firstOrNull { it.key == state.flyout }
    val anchor = item?.let { state.anchors[it.key] }
    // 슬라이더 칸은 얇은 세로 슬라이더 창(칸 가운데에 맞추고 칸을 가리키는 삼각형), 나머지는 칸 위 끝에 맞춘 카드 창이다
    val slider = item as? SliderCell
    val hinges = LocalHinges.current
    val density = LocalDensity.current
    val area = remember { FlyoutArea() }
    // 세로 슬라이더 창은 삼각형이 들어갈 만큼 칸에서 더 띄운다
    val gapDp = if (slider != null) FlyoutGap + FlyoutArrowWidth else FlyoutGap
    val onLeft = with(density) {
        val cell = anchor
        val region = area.region
        if (cell == null || region == null) {
            false
        } else {
            val gap = gapDp.roundToPx()
            val hingeMargin = HingeMargin.roundToPx()
            val (right, left) = flyoutSides(
                anchor = cell,
                area = region,
                verticalBands = hinges.filter { it.isVertical }.map { it.band(hingeMargin) },
                horizontalBands = hinges.filter { !it.isVertical }.map { it.band(hingeMargin) },
                gap = gap
            )
            flyoutOnLeft(FlyoutDecisionWidth.roundToPx(), cell, right, left, gap)
        }
    }
    val content: (@Composable () -> Unit)? = when {
        slider != null -> ({ VerticalSliderFlyout(slider, valueOnLeft = onLeft) })
        else -> item?.flyout?.let { flyout -> { FlyoutSurface(item.title) { flyout() } } }
    }
    Layout(
        content = {
            if (content != null && anchor != null) {
                content()
                if (slider != null) FlyoutArrow()
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 놓지 않는다. 곧 이어지는 배치에서 놓는다.
            val origin = coordinates?.positionInWindow() ?: return@layout
            val left = origin.x.roundToInt()
            val top = origin.y.roundToInt()
            val margin = FlyoutMargin.roundToPx()
            val region = Region(left + margin, top + margin, left + width - margin, top + height - margin)
            area.region = region
            val measurable = measurables.firstOrNull() ?: return@layout
            val cell = anchor ?: return@layout
            val gap = gapDp.roundToPx()
            val hingeMargin = HingeMargin.roundToPx()
            val (rightSide, leftSide) = flyoutSides(
                anchor = cell,
                area = region,
                verticalBands = hinges.filter { it.isVertical }.map { it.band(hingeMargin) },
                horizontalBands = hinges.filter { !it.isVertical }.map { it.band(hingeMargin) },
                gap = gap
            )
            val side = if (onLeft) leftSide else rightSide
            val placeable = measurable.measure(
                Constraints(maxWidth = side.width.coerceAtLeast(0), maxHeight = side.height.coerceAtLeast(0))
            )
            val x = if (onLeft) {
                (side.right - placeable.width).coerceAtLeast(side.left)
            } else {
                side.left.coerceAtMost(side.right - placeable.width).coerceAtLeast(side.left)
            }
            val y = if (slider != null) {
                // 칸 가운데에 슬라이더 가운데를 맞추고, 위아래가 모자라면 칸에서 시작하거나 끝나게 영역 안으로 민다(띠와 같은 규칙)
                stripTop(Span(cell.top, cell.bottom), placeable.height, Span(side.top, side.bottom))
            } else {
                cell.top.coerceAtMost(side.bottom - placeable.height).coerceAtLeast(side.top)
            }
            placeable.place(x - left, y - top)
            // 칸을 가리키는 삼각형: 창의 칸 쪽 끝에 칸 가운데 높이로, 창 테두리를 덮게 조금 겹쳐 놓는다(왼쪽에 띄우면 뒤집어 놓는다)
            val arrow = measurables.getOrNull(1)?.measure(Constraints()) ?: return@layout
            val arrowY = ((cell.top + cell.bottom) / 2 - arrow.height / 2).coerceIn(y, (y + placeable.height - arrow.height).coerceAtLeast(y))
            if (onLeft) {
                arrow.placeWithLayer(x + placeable.width + FlyoutArrowWidth.roundToPx() - arrow.width - left, arrowY - top) { scaleX = -1f }
            } else {
                arrow.place(x - FlyoutArrowWidth.roundToPx() - left, arrowY - top)
            }
        }
    }
}

/** 옵션 창의 모양: 패널 색 카드(모서리 8dp, 1dp 테두리, 그림자). 화면 읽기 기능에는 [title]을 창 이름으로 알린다. */
@Composable
private fun FlyoutSurface(title: String?, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .testTag(EditScreenTags.OPTION_FLYOUT)
            .semantics { if (title != null) paneTitle = title },
        shape = StratumShapes.Card,
        color = StratumTheme.colors.panel,
        contentColor = StratumTheme.colors.textPrimary,
        border = BorderStroke(StratumDimens.Divider, StratumTheme.colors.fieldBorder),
        shadowElevation = 6.dp
    ) {
        Box(Modifier.padding(12.dp)) { content() }
    }
}
