package com.kiln.stratum.ui.layout

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 세로로 차례로 쌓되, 가로로 지나가는 힌지의 띠(양쪽 [HingeMargin])에 걸리는 것은 띠 아래로 비켜 놓는다.
 *
 * 배치하는 순간 자기 위치(창 좌표)를 읽어(PlacementScope.coordinates) 한 프레임 늦거나 흔들리지 않는다.
 * 자기 크기는 힌지와 상관없이 정한다. 폭은 가장 넓은 것이고, 높이는 받은 최대 높이다(정해지지 않았거나 [fillHeight]가 false면 쌓은 높이).
 * 아래로 비켜 놓은 것이 영역 밖으로 나가지 않을 만큼 높이가 충분할 때 쓴다.
 */
@Composable
fun HingeAvoidingColumn(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    fillHeight: Boolean = true,
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
            if (fillHeight && constraints.hasBoundedHeight) {
                constraints.maxHeight
            } else {
                stackedHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            }
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
 * 세로로 스크롤하는 목록. 스크롤하지 않았을 때 가로 힌지의 띠(양쪽 [HingeMargin])에 걸리는 항목은 띠 아래로 비켜 놓는다.
 *
 * 비켜 놓은 자리는 내용에 붙어 있어, 스크롤하면 내용과 함께 움직이고 띠를 지나갈 수 있다(결정 기록 '홈 최근 프로젝트'와 같은 까닭).
 * 그래서 내용이 다 들어가 스크롤하지 않는 화면(폴드 메인 세로)에서는 항목이 띠에 놓이지 않는다.
 * 높이는 쌓은 높이에, 가로 힌지가 있으면 비켜 놓을 만큼(띠 높이 + 가장 큰 항목 높이)을 더한다. 끝에 남는 자리는 비어 있다.
 *
 * 스크롤하지 않았을 때의 위치는 스크롤 바깥(스크롤이 옮기기 전)의 좌표로 잰다. 배치하는 순간 읽어 한 프레임 늦지 않는다.
 */
@Composable
fun HingeAvoidingScrollColumn(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit
) {
    val hinges = LocalHinges.current.filter { !it.isVertical }
    Layout(content = content, modifier = modifier.verticalScroll(scrollState)) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gapPx = gap.roundToPx()
        val marginPx = HingeMargin.roundToPx()
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val stackedHeight = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
        val tallest = placeables.maxOfOrNull { it.height } ?: 0
        val room = hinges.sumOf { hinge -> hinge.band(marginPx).let { it.end - it.start } + tallest }
        val height = (stackedHeight + room).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 띠 없이 놓고, 곧 이어지는 배치에서 다시 놓는다.
            // 바로 바깥 좌표는 스크롤이 옮기기 전의 자리라서, 스크롤해도 비켜 놓은 자리가 바뀌지 않는다.
            val origin = coordinates?.let { it.parentCoordinates ?: it }?.positionInWindow()
            val bands =
                if (origin == null) {
                    emptyList()
                } else {
                    hinges
                        .filter { it.left < origin.x + width && it.right > origin.x }
                        .map { it.band(marginPx).shiftedBy(-origin.y.roundToInt()) }
                }
            val tops = stackAvoiding(placeables.map { it.height }, gapPx, bands)
            placeables.forEachIndexed { index, placeable -> placeable.place(0, tops[index]) }
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

/**
 * 하나를 아래쪽 가운데에 놓되, 힌지의 띠(양쪽 [HingeMargin])를 피한다. 잠깐 뜨는 알림 막대에 쓴다.
 *
 * - 세로 힌지의 띠가 가로를 나누면, 띠 밖에서 가장 넓은 칸에 들어가도록 폭을 줄여 그 칸 가운데에 놓는다.
 * - 가로 힌지의 띠에 걸리면 띠 위로 올린다.
 *
 * 폭을 정하려면 자기 위치(창 좌표)가 필요해서, 배치하는 순간에 위치를 읽고 그때 크기를 잰다.
 * 받은 영역을 다 차지하지만 터치는 받지 않는다([HingeAvoidingTopStart]와 같다).
 */
@Composable
fun HingeAvoidingBottom(
    modifier: Modifier = Modifier,
    margin: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val hinges = LocalHinges.current
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val marginPx = margin.roundToPx()
        val hingeMarginPx = HingeMargin.roundToPx()
        layout(width, height) {
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 띠 없이 놓고, 곧 이어지는 배치에서 다시 놓는다
            val origin = coordinates?.positionInWindow()
            val row = Span(marginPx, (width - marginPx).coerceAtLeast(marginPx))
            val span = if (origin == null) {
                row
            } else {
                widestFreeSpan(row, hinges.filter { it.isVertical }.map { it.band(hingeMarginPx).shiftedBy(-origin.x.roundToInt()) })
            }
            val placeable = measurables.single().measure(
                Constraints(maxWidth = (span.end - span.start).coerceAtLeast(0), maxHeight = height)
            )
            val x = span.start + (span.end - span.start - placeable.width) / 2
            var y = height - marginPx - placeable.height
            if (origin != null) {
                val top = origin.y.roundToInt()
                // 아래에서부터 올라가며, 걸리는 띠가 있으면 그 띠 위로 올린다
                for (band in hinges.filter { !it.isVertical }.map { it.band(hingeMarginPx).shiftedBy(-top) }.sortedByDescending { it.start }) {
                    if (y < band.end && y + placeable.height > band.start) y = band.start - placeable.height
                }
            }
            placeable.place(x, y)
        }
    }
}

/**
 * 하나를 위쪽 가운데에 놓되, 힌지의 띠(양쪽 [HingeMargin])를 피한다. 잠깐 뜨는 제스처 알림에 쓴다([HingeAvoidingBottom]과 같은 방식).
 *
 * - 세로 힌지의 띠가 가로를 나누면, 띠 밖에서 가장 넓은 칸에 들어가도록 폭을 줄여 그 칸 가운데에 놓는다.
 * - 가로 힌지의 띠에 걸리면 띠 아래로 내린다.
 * - 놓을 것이 없으면(사라지는 알림이 다 사라짐) 아무것도 놓지 않는다.
 *
 * 받은 영역을 다 차지하지만 터치는 받지 않는다([HingeAvoidingTopStart]와 같다).
 */
@Composable
fun HingeAvoidingTop(
    modifier: Modifier = Modifier,
    margin: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val hinges = LocalHinges.current
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val marginPx = margin.roundToPx()
        val hingeMarginPx = HingeMargin.roundToPx()
        layout(width, height) {
            val measurable = measurables.firstOrNull() ?: return@layout
            // 정렬선을 재는 단계에서는 위치를 몰라(null) 띠 없이 놓고, 곧 이어지는 배치에서 다시 놓는다
            val origin = coordinates?.positionInWindow()
            val row = Span(0, width)
            val span = if (origin == null) {
                row
            } else {
                widestFreeSpan(row, hinges.filter { it.isVertical }.map { it.band(hingeMarginPx).shiftedBy(-origin.x.roundToInt()) })
            }
            val placeable = measurable.measure(Constraints(maxWidth = (span.end - span.start).coerceAtLeast(0), maxHeight = height))
            val x = span.start + (span.end - span.start - placeable.width) / 2
            var y = marginPx
            if (origin != null) {
                val top = origin.y.roundToInt()
                // 위에서부터 내려가며, 걸리는 띠가 있으면 그 띠 아래로 내린다
                for (band in hinges.filter { !it.isVertical }.map { it.band(hingeMarginPx).shiftedBy(-top) }.sortedBy { it.start }) {
                    if (y < band.end && y + placeable.height > band.start) y = band.end
                }
            }
            placeable.place(x, y)
        }
    }
}
