package com.kiln.stratum.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 세로로 차례로 쌓되, 가로로 지나가는 힌지의 띠(양쪽 [HingeMargin])에 걸리는 것은 띠 아래로 비켜 놓는다.
 *
 * 배치하는 순간 자기 위치(창 좌표)를 읽어(PlacementScope.coordinates) 한 프레임 늦거나 흔들리지 않는다.
 * 자기 크기는 힌지와 상관없이 정한다. 폭은 가장 넓은 것이고, 높이는 받은 최대 높이다(정해지지 않았으면 쌓은 높이).
 * 아래로 비켜 놓은 것이 영역 밖으로 나가지 않을 만큼 높이가 충분할 때 쓴다.
 */
@Composable
fun HingeAvoidingColumn(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val hinges = LocalHinges.current
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gapPx = gap.roundToPx()
        val marginPx = HingeMargin.roundToPx()
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val stackedHeight = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
        val height =
            if (constraints.hasBoundedHeight) constraints.maxHeight else stackedHeight.coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 띠 없이 놓고, 곧 이어지는 배치에서 다시 놓는다
            val origin = coordinates?.positionInWindow()
            val bands =
                if (origin == null) {
                    emptyList()
                } else {
                    hinges
                        .filter { !it.isVertical && it.left < origin.x + width && it.right > origin.x }
                        .map { it.band(marginPx).shiftedBy(-origin.y.roundToInt()) }
                }
            val tops = stackAvoiding(placeables.map { it.height }, gapPx, bands)
            placeables.forEachIndexed { index, placeable ->
                placeable.place((width - placeable.width) / 2, tops[index])
            }
        }
    }
}

/**
 * [first]와 [second]를 같은 폭으로 가로로 나란히 놓는다. 세로로 지나가는 힌지의 띠가 줄을 지나면 띠 양쪽에 하나씩 놓는다.
 *
 * 두 개의 폭은 힌지의 위치와 상관없이 띠의 너비로만 정하고, 자리는 배치하는 순간 자기 위치를 읽어 정한다(한 프레임 늦지 않음).
 * 그래서 세로 힌지가 있으면 줄을 지나지 않아도 간격이 띠의 너비만큼 벌어진다. 힌지가 없으면 [gap]만큼 띄운다.
 * 받은 폭이 정해져 있어야 한다.
 */
@Composable
fun HingeSplitRow(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp
) {
    val verticalHinges = LocalHinges.current.filter { it.isVertical }
    Layout(contents = listOf(first, second), modifier = modifier) { (firstMeasurables, secondMeasurables), constraints ->
        val width = constraints.maxWidth
        val marginPx = HingeMargin.roundToPx()
        val bandWidth = verticalHinges.maxOfOrNull { it.band(marginPx).let { band -> band.end - band.start } } ?: 0
        val childWidth = ((width - maxOf(gap.roundToPx(), bandWidth)) / 2).coerceAtLeast(0)
        val childConstraints = constraints.copy(minWidth = childWidth, maxWidth = childWidth, minHeight = 0)
        val firstPlaceable = firstMeasurables.single().measure(childConstraints)
        val secondPlaceable = secondMeasurables.single().measure(childConstraints)
        val height = maxOf(firstPlaceable.height, secondPlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val originX = coordinates?.positionInWindow()?.x?.roundToInt()
            val band =
                if (originX == null) {
                    null
                } else {
                    verticalHinges
                        .map { it.band(marginPx).shiftedBy(-originX) }
                        .firstOrNull { it.end > 0 && it.start < width }
                }
            val (firstX, secondX) = splitAround(width, childWidth, band)
            firstPlaceable.place(firstX, (height - firstPlaceable.height) / 2)
            secondPlaceable.place(secondX, (height - secondPlaceable.height) / 2)
        }
    }
}

/**
 * 하나를 왼쪽 위 구석([margin]만큼 안쪽)에 놓되, 힌지의 띠(양쪽 [HingeMargin])에 걸리면 비켜 놓는다.
 * 세로 힌지에 걸리면 띠 오른쪽으로, 가로 힌지에 걸리면 띠 아래로 옮긴다.
 *
 * 받은 영역을 다 차지하지만 터치는 받지 않아서, 아래에 깔린 캔버스 조작을 막지 않는다.
 * 배치하는 순간 자기 위치를 읽어 한 프레임 늦지 않는다([HingeAvoidingColumn]과 같다).
 */
@Composable
fun HingeAvoidingTopStart(
    modifier: Modifier = Modifier,
    margin: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val hinges = LocalHinges.current
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val marginPx = margin.roundToPx()
        val hingeMarginPx = HingeMargin.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            var x = marginPx
            var y = marginPx
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 구석에 놓고, 곧 이어지는 배치에서 다시 놓는다
            val origin = coordinates?.positionInWindow()
            if (origin != null) {
                val left = origin.x.roundToInt() + marginPx
                val top = origin.y.roundToInt() + marginPx
                x += stackAvoiding(
                    listOf(placeable.width),
                    gap = 0,
                    bands = hinges.filter { it.isVertical }.map { it.band(hingeMarginPx).shiftedBy(-left) }
                ).single()
                y += stackAvoiding(
                    listOf(placeable.height),
                    gap = 0,
                    bands = hinges.filter { !it.isVertical }.map { it.band(hingeMarginPx).shiftedBy(-top) }
                ).single()
            }
            placeable.place(x, y)
        }
    }
}
