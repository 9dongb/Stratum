package com.kiln.stratum.ui.layout

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** 편집 화면·유동화 작업 모드의 상단 바 높이(결정 기록 '화면 공간'). 버튼의 누르는 자리(48dp)가 꼭 들어간다. */
val WorkTopBarHeight: Dp = 48.dp

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

    /** 바 높이 안에 들어오는 위쪽 카메라 구멍의 창 가로 범위(px) */
    internal var holes: List<ClosedFloatingPointRange<Float>> = emptyList()

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

/** 이 버튼·제목이 차지하는 자리를 [insets]에 알린다. [key]는 바 안에서 겹치지 않는 이름이다. */
fun Modifier.topBarItem(insets: TopBarInsets, key: String): Modifier = this then TopBarItemElement(insets, key)

/** 구멍([holes]) 가운데 하나라도 바의 버튼·제목([items])과 [margin]보다 가까운지(창 가로 범위, px) */
internal fun cutoutBlocks(
    holes: List<ClosedFloatingPointRange<Float>>,
    items: Collection<ClosedFloatingPointRange<Float>>,
    margin: Float
): Boolean = holes.any { hole ->
    items.any { item -> hole.start < item.endInclusive + margin && item.start - margin < hole.endInclusive }
}

private val HoleMargin = 4.dp

/**
 * 상단 바 가운데에 겹쳐 둘 글자 칸(폭 [width], 배율 표시)의 왼쪽 자리(창 x, px, 사용자 피드백 9차).
 * 바 가운데([center])에 두되 가운데 양쪽 버튼·제목([items]) 사이 빈 곳 안으로 밀고, 카메라 구멍([holes], 폴드8 커버는 위 가운데)에
 * 걸리면 빈 곳 안에서 구멍 바로 옆(처음 자리에 가까운 쪽)으로 비킨다. 빈 곳이 모자라면 그 끝에, 구멍을 피할 자리가 없으면 그대로 둔다.
 * [margin]은 버튼·구멍과 둘 여유다. [barLeft]·[barRight]는 바의 양 끝이다(가운데 쪽에 버튼이 없을 때의 빈 곳 끝).
 */
internal fun barLabelLeft(
    width: Int,
    center: Int,
    barLeft: Int,
    barRight: Int,
    items: Collection<ClosedFloatingPointRange<Float>>,
    holes: List<ClosedFloatingPointRange<Float>>,
    margin: Float
): Int {
    val freeStart = items.filter { it.endInclusive <= center }.maxOfOrNull { it.endInclusive + margin } ?: barLeft.toFloat()
    val freeEnd = items.filter { it.start >= center }.minOfOrNull { it.start - margin } ?: barRight.toFloat()
    val lowest = freeStart.toInt()
    val highest = (freeEnd - width).toInt()
    val wanted = center - width / 2
    if (highest < lowest) return wanted
    val placed = wanted.coerceIn(lowest, highest)
    fun blocked(left: Int) = holes.any { it.start < left + width + margin && left - margin < it.endInclusive }
    if (!blocked(placed)) return placed
    val candidates = holes.flatMap { listOf((it.start - margin - width).toInt(), (it.endInclusive + margin).toInt() + 1) }
        .filter { it in lowest..highest && !blocked(it) }
    return candidates.minByOrNull { kotlin.math.abs(it - wanted) } ?: placed
}


/** 창 맨 위에서 [band] 안으로 들어오는 카메라 구멍들의 가로 범위(px). 구멍 자리는 창 좌표로 온다. */
private fun topHoles(view: View, band: Float): List<ClosedFloatingPointRange<Float>> =
    view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
        .filter { !it.isEmpty && it.top < band && it.bottom > 0 }
        .map { it.left.toFloat()..it.right.toFloat() }

private class TopBarItemElement(val insets: TopBarInsets, val key: String) : ModifierNodeElement<TopBarItemNode>() {
    override fun create() = TopBarItemNode(insets, key)

    override fun update(node: TopBarItemNode) {
        if (node.insets !== insets || node.key != key) {
            node.insets.remove(node.key)
            node.insets = insets
            node.key = key
        }
    }

    override fun equals(other: Any?): Boolean = other is TopBarItemElement && other.insets === insets && other.key == key

    override fun hashCode(): Int = 31 * System.identityHashCode(insets) + key.hashCode()
}

/** 놓인 자리(창 좌표)를 알리고, 화면에서 빠지면 지운다 */
private class TopBarItemNode(var insets: TopBarInsets, var key: String) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bounds = coordinates.boundsInWindow()
        insets.report(key, bounds.left..bounds.right)
    }

    override fun onDetach() {
        insets.remove(key)
    }
}
