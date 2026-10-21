package com.kiln.stratum.ui.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp

/**
 * 노드 안쪽(모든 Modifier를 거친 내용)의 창 좌표. 버튼이면 눈에 보이는 부분이다.
 * Material 버튼은 그 바깥에 최소 터치 영역(48dp)을 더 두므로, 누르는 자리는 [touchBoundsInWindow]로 본다.
 */
fun SemanticsNode.layoutBoundsInWindow(): Rect = layoutInfo.coordinates.boundsInWindow()

/** 최소 터치 영역(CLAUDE.md 6장). Compose는 이보다 작은 것도 가운데를 기준으로 이만큼 넓혀 터치를 받는다. */
private val MinTouchTarget = 48.dp

/**
 * 누를 수 있는 영역의 창 좌표. 노드가 48dp보다 작으면 가운데를 기준으로 48dp까지 넓힌다.
 * 스크롤 영역처럼 잘라 그리는 부모에 일부가 가려진 노드는, 가려진 쪽 터치도 보이는 끝에서 잘린다
 * (Compose는 잘라 그리는 부모 밖의 터치를 자식에게 주지 않는다). 그래서 그쪽은 보이는 끝까지만 본다.
 */
fun SemanticsNode.touchBoundsInWindow(): Rect {
    val coordinates = layoutInfo.coordinates
    val visible = coordinates.boundsInWindow()
    val position = coordinates.positionInWindow()
    val whole = Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height)
    val min = with(layoutInfo.density) { MinTouchTarget.toPx() }
    val halfWidth = maxOf(whole.width, min) / 2
    val halfHeight = maxOf(whole.height, min) / 2
    val expanded = Rect(whole.center.x - halfWidth, whole.center.y - halfHeight, whole.center.x + halfWidth, whole.center.y + halfHeight)
    return Rect(
        if (visible.left > whole.left + CLIP_EPSILON_PX) maxOf(expanded.left, visible.left) else expanded.left,
        if (visible.top > whole.top + CLIP_EPSILON_PX) maxOf(expanded.top, visible.top) else expanded.top,
        if (visible.right < whole.right - CLIP_EPSILON_PX) minOf(expanded.right, visible.right) else expanded.right,
        if (visible.bottom < whole.bottom - CLIP_EPSILON_PX) minOf(expanded.bottom, visible.bottom) else expanded.bottom
    )
}

/** 보이는 끝과 노드 끝의 차이가 이보다 작으면 가려지지 않은 것으로 본다(소수 픽셀 반올림) */
private const val CLIP_EPSILON_PX = 0.5f
