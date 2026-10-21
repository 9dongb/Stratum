package com.kiln.stratum.ui.layout

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 편집 화면·유동화 작업 모드·커버 새 창의 상단 바 높이: 상태 표시줄 높이다(사용자 피드백 10차 6: "헤더 줄여달라는 크기는 상태표시줄 위아래 폭 만큼").
 * 상태 표시줄을 숨겨도 그 자리 높이를 읽는다. 상태 표시줄이 없는 창(여러 창 등)은 32dp이고, 버튼이 들어가게 24~48dp 안이다.
 * 글자를 크게 쓰는 기기에서는 바 제목(한 줄)이 잘리지 않게 그 줄 높이까지 높인다(휴대폰 설정의 글자 크기를 따름).
 * 바의 버튼은 이 높이에 맞춘 것([com.kiln.stratum.ui.components.TopBarIconButton] 등)이다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun workTopBarHeight(): Dp {
    val density = LocalDensity.current
    val status = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(density).toDp() }
    val bar = (if (status > 0.dp) status else FallbackWorkTopBarHeight).coerceIn(MinWorkTopBarHeight, MaxWorkTopBarHeight)
    // 바 제목은 TopAppBar의 제목 글자(titleLarge)다
    val title = with(density) { MaterialTheme.typography.titleLarge.lineHeight.toDp() }
    return maxOf(bar, title)
}

private val FallbackWorkTopBarHeight = 32.dp
private val MinWorkTopBarHeight = 24.dp
private val MaxWorkTopBarHeight = 48.dp

/**
 * 상단 바의 창 여백(결정 기록 '전체 화면').
 *
 * 상태 표시줄을 숨겨 바가 화면 맨 위에 붙으므로, 위쪽 카메라 구멍이 바의 버튼·제목([topBarItem]을 단 것)에 걸릴 때만
 * 바 내용을 구멍 아래로 내린다. 구멍이 바의 빈 곳(편집 화면 바의 가운데 등)에 있으면 맨 위에 그대로 두어,
 * 상태 표시줄이 있던 자리를 모두 쓴다. 바의 배경은 어느 쪽이든 화면 맨 위까지 칠한다.
 * 옆쪽은 늘 시스템 바·카메라 구멍을 피하고, 상태 표시줄이 보이는 동안(위에서 밀어 잠깐 보일 때는 빼고)은 그 높이를 비운다.
 */
@Stable
class TopBarInsets internal constructor() {
    /** 바의 버튼·제목이 차지하는 창 가로 범위(px), 이름별 */
    internal val items = mutableStateMapOf<String, ClosedFloatingPointRange<Float>>()

    /**
     * 바 높이 안에 들어오는 위쪽 카메라 구멍의 창 가로 범위(px). 상태라서, 창 여백이 늦게 와 바뀌면 이것을 읽는 배치
     * ([HoleAvoidingBarRow])가 다시 놓인다. 같은 목록을 다시 넣으면 아무 일도 없다.
     */
    internal var holes: List<ClosedFloatingPointRange<Float>> by mutableStateOf(emptyList())

    /** 구멍과 버튼·제목 사이에 둘 여유(px) */
    internal var margin: Float = 0f

    internal fun report(key: String, range: ClosedFloatingPointRange<Float>) {
        if (items[key] != range) items[key] = range
    }

    internal fun remove(key: String) {
        items.remove(key)
    }
}

/** 상단 바의 창 여백 상태. 바 높이 [barHeight] 안으로 들어오는 카메라 구멍만 본다. */
@Composable
fun rememberTopBarInsets(barHeight: Dp): TopBarInsets {
    val insets = remember { TopBarInsets() }
    val view = LocalView.current
    val density = LocalDensity.current
    // 구멍 여백은 돌리기·접기로 바뀐다. 여기서 읽어 두면 바뀔 때 구멍 자리를 다시 읽는다.
    val cutout = WindowInsets.displayCutout
    val sides = listOf(
        cutout.getLeft(density, LayoutDirection.Ltr),
        cutout.getTop(density),
        cutout.getRight(density, LayoutDirection.Ltr),
        cutout.getBottom(density)
    )
    val band = with(density) { barHeight.toPx() }
    insets.holes = remember(view, sides, band) { topHoles(view, band) }
    insets.margin = with(density) { HoleMargin.toPx() }
    return insets
}

/** 바에 줄 창 여백(TopAppBar의 windowInsets) */
@Composable
fun TopBarInsets.windowInsets(): WindowInsets {
    val top = if (cutoutBlocks(holes, items.values, margin)) WindowInsets.safeDrawing else WindowInsets.statusBars
    return top.only(WindowInsetsSides.Top).union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
}

/**
 * 이 버튼·제목이 차지하는 자리를 [insets]에 알린다. [key]는 바 안에서 겹치지 않는 이름이다.
 * [inset]만큼 양옆을 줄여 알린다(보이는 부분만 구멍과 견줄 때, [HoleAvoidingBarRow]).
 */
fun Modifier.topBarItem(insets: TopBarInsets, key: String, inset: Dp = 0.dp): Modifier = this then TopBarItemElement(insets, key, inset)

/** 구멍([holes]) 가운데 하나라도 바의 버튼·제목([items])과 [margin]보다 가까운지(창 가로 범위, px) */
internal fun cutoutBlocks(
    holes: List<ClosedFloatingPointRange<Float>>,
    items: Collection<ClosedFloatingPointRange<Float>>,
    margin: Float
): Boolean = holes.any { hole ->
    items.any { item -> hole.start < item.endInclusive + margin && item.start - margin < hole.endInclusive }
}

private val HoleMargin = 4.dp

/** [HoleAvoidingBarRow]에서 제목과 첫 버튼 사이에 남길 가장 작은 틈 */
private val TitleGap = 8.dp

/** M3 상단 바의 제목 자리 왼쪽 끝(앞 버튼이 없을 때). 줄이 놓이기 전 창 위치 어림에만 쓴다. */
private val BarTitleStart = 16.dp

/**
 * 바 버튼에서 보이는 부분(글자 버튼의 글자, 아이콘 버튼의 아이콘)의 양옆 여백. 글자 버튼 안쪽 여백·(48 − 24)/2와 같다.
 * 버튼(줄)을 [topBarItem]으로 알릴 때 이만큼 줄여 알리면, 버튼 여백만 카메라 구멍에 걸릴 때 바를 내리지 않는다
 * (사용자 피드백 10차: 버튼 여백이 구멍에 걸리는 것은 가려지는 것이 아니다).
 */
val BarButtonContentInset = 12.dp

/**
 * 상단 바 한 줄: 왼쪽에 [title], 오른쪽 끝부터 [actions](이름, 버튼). 위쪽 카메라 구멍([insets]의 구멍)에 걸리는 버튼은
 * 그 버튼부터 왼쪽 버튼들을 구멍 왼쪽으로 비켜 놓아, 바를 구멍 아래로 내리지 않는다(사용자 피드백 10차 7: 커버 홈 헤더가 구멍만큼 높아졌다).
 * 비키면 제목과 겹칠 만큼 자리가 모자라면 그대로 둔다(그때는 지금처럼 바가 구멍 아래로 내려간다).
 * 제목은 버튼을 오른쪽 끝에 붙였을 때 남는 폭까지만 쓴다(넘치면 제목 글자가 정한 대로 줄인다, 홈은 '…').
 * 제목과 버튼마다 [topBarItem]으로 자리를 알린다(버튼 키는 [actions]의 이름). 바의 제목 자리에 바 폭으로 둔다.
 */
@Composable
fun HoleAvoidingBarRow(
    insets: TopBarInsets,
    title: @Composable () -> Unit,
    actions: List<Pair<String, @Composable () -> Unit>>,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // 이 줄의 창 왼쪽 끝(px). 놓인 뒤에 알고, 바뀌면 다시 놓는다. 처음에는 바 제목 자리(바 왼쪽에서 16dp)로 어림해 첫 화면부터 구멍을 피한다.
    var rowLeft by remember { mutableStateOf<Float?>(null) }
    Layout(
        content = {
            Box(Modifier.topBarItem(insets, "title")) { title() }
            // 버튼은 보이는 부분(양옆 [BarButtonContentInset] 안쪽)만 알린다. 버튼 여백이 구멍에 걸리는 것은 가려지는 것이 아니다.
            for ((key, action) in actions) Box(Modifier.topBarItem(insets, key, BarButtonContentInset)) { action() }
        },
        modifier = modifier.fillMaxWidth().onGloballyPositioned { rowLeft = it.positionInWindow().x }
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val width = constraints.maxWidth
        // 버튼을 먼저 재고, 제목에는 버튼을 오른쪽 끝에 붙였을 때 남는 폭까지만 준다(글자를 크게 쓰거나 줄이 좁으면 제목이 '…'로 줄어
        // 버튼 밑에 깔리지 않는다. TopAppBar의 제목과 같다)
        val actionPlaceables = measurables.drop(1).map { it.measure(loose) }
        val titleWidth = (width - actionPlaceables.sumOf { it.width } - with(density) { TitleGap.roundToPx() }).coerceAtLeast(0)
        val titlePlaceable = measurables.first().measure(loose.copy(maxWidth = titleWidth))
        val placeables = listOf(titlePlaceable) + actionPlaceables
        val height = placeables.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val widths = actionPlaceables.map { it.width }
            // 구멍은 창 좌표라 이 줄의 창 위치를 빼서 견준다
            val left = rowLeft ?: with(density) { BarTitleStart.toPx() }
            val inset = with(density) { BarButtonContentInset.roundToPx() }
            val starts = holeAvoidingStarts(
                widths = widths,
                end = width,
                holes = insets.holes.map { (it.start - left)..(it.endInclusive - left) },
                margin = insets.margin,
                minStart = titlePlaceable.width + with(density) { TitleGap.roundToPx() } - inset,
                inset = inset
            ) ?: plainStarts(widths, width)
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            actionPlaceables.forEachIndexed { index, placeable -> placeable.place(starts[index], (height - placeable.height) / 2) }
        }
    }
}

/** 버튼들(폭 [widths])을 틈 없이 오른쪽 끝([end])에 붙인 왼쪽 자리 */
private fun plainStarts(widths: List<Int>, end: Int): List<Int> {
    var cursor = end
    return widths.reversed().map { width -> (cursor - width).also { cursor = it } }.reversed()
}

/**
 * 오른쪽 끝([end])부터 틈 없이 붙인 버튼들(폭 [widths], 왼쪽부터)의 왼쪽 자리. 보이는 부분(양옆 [inset] 안쪽)이 구멍([holes], 같은 좌표)에
 * [margin]보다 가까운 버튼은 보이는 부분이 그 구멍 왼쪽([margin]만큼 띄움)에서 끝나게 옮기고, 그 왼쪽 버튼들도 이어서 놓는다.
 * 맨 왼쪽 버튼이 [minStart]보다 앞으로 가면 null이다(자리가 모자람).
 */
internal fun holeAvoidingStarts(
    widths: List<Int>,
    end: Int,
    holes: List<ClosedFloatingPointRange<Float>>,
    margin: Float,
    minStart: Int,
    inset: Int = 0
): List<Int>? {
    val starts = IntArray(widths.size)
    var cursor = end.toFloat()
    for (index in widths.indices.reversed()) {
        var right = cursor
        // 버튼을 놓을 자리가 구멍에 걸리면 구멍 왼쪽으로 옮긴다(옮긴 자리가 다른 구멍에 걸리면 또 옮긴다)
        while (true) {
            val shownLeft = right - widths[index] + inset
            val shownRight = right - inset
            val hole = holes.firstOrNull { it.start < shownRight + margin && shownLeft - margin < it.endInclusive } ?: break
            right = hole.start - margin + inset
        }
        val start = (right - widths[index]).toInt()
        if (start < minStart) return null
        starts[index] = start
        cursor = start.toFloat()
    }
    return starts.toList()
}

/** 창 맨 위에서 [band] 안으로 들어오는 카메라 구멍들의 가로 범위(px). 구멍 자리는 창 좌표로 온다. */
private fun topHoles(view: View, band: Float): List<ClosedFloatingPointRange<Float>> =
    view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
        .filter { !it.isEmpty && it.top < band && it.bottom > 0 }
        .map { it.left.toFloat()..it.right.toFloat() }

private class TopBarItemElement(val insets: TopBarInsets, val key: String, val inset: Dp) : ModifierNodeElement<TopBarItemNode>() {
    override fun create() = TopBarItemNode(insets, key, inset)

    override fun update(node: TopBarItemNode) {
        if (node.insets !== insets || node.key != key) {
            node.insets.remove(node.key)
            node.insets = insets
            node.key = key
        }
        node.inset = inset
    }

    override fun equals(other: Any?): Boolean = other is TopBarItemElement && other.insets === insets && other.key == key && other.inset == inset

    override fun hashCode(): Int = (31 * System.identityHashCode(insets) + key.hashCode()) * 31 + inset.hashCode()
}

/** 놓인 자리(창 좌표, 양옆을 [inset]만큼 줄임)를 알리고, 화면에서 빠지면 지운다 */
private class TopBarItemNode(var insets: TopBarInsets, var key: String, var inset: Dp) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bounds = coordinates.boundsInWindow()
        val shrink = with(currentValueOf(LocalDensity)) { inset.toPx() }.coerceAtMost(bounds.width / 2)
        insets.report(key, (bounds.left + shrink)..(bounds.right - shrink))
    }

    override fun onDetach() {
        insets.remove(key)
    }
}
