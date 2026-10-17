package com.kiln.stratum.ui.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/**
 * 노드 안쪽(모든 Modifier를 거친 내용)의 창 좌표. 버튼이면 눈에 보이는 부분이다.
 * Material 버튼은 그 바깥에 최소 터치 영역(48dp)을 더 두므로, 힌지 검사는 [touchBoundsInWindow]를 쓴다.
 */
fun SemanticsNode.layoutBoundsInWindow(): Rect = layoutInfo.coordinates.boundsInWindow()

/** 최소 터치 영역(CLAUDE.md 6장). Compose는 이보다 작은 것도 가운데를 기준으로 이만큼 넓혀 터치를 받는다. */
private val MinTouchTarget = 48.dp

/**
 * 48dp로 넓힌 터치 영역은 소수 픽셀이고, Material 버튼은 그 자리를 정수 픽셀로 반올림해 잡는다.
 * 그 차이(1px 미만)만큼 띠에 닿는 것은 허용한다.
 */
private const val ROUNDING_TOLERANCE_PX = 1f

/** 누를 수 있는 영역의 창 좌표. 눈에 보이는 부분이 48dp보다 작으면 가운데를 기준으로 48dp까지 넓힌다. */
fun SemanticsNode.touchBoundsInWindow(): Rect {
    val bounds = layoutBoundsInWindow()
    val min = with(layoutInfo.density) { MinTouchTarget.toPx() }
    val halfWidth = maxOf(bounds.width, min) / 2
    val halfHeight = maxOf(bounds.height, min) / 2
    return Rect(
        bounds.center.x - halfWidth,
        bounds.center.y - halfHeight,
        bounds.center.x + halfWidth,
        bounds.center.y + halfHeight
    )
}

/**
 * 누를 수 있는 것(비활성 포함)의 터치 영역(최소 48dp)이 모두 [hinge]의 띠(양쪽 [HingeMargin]) 밖에 있는지 확인한다.
 * 화면 크기 강제(ForcedSize)는 밀도를 바꾸므로, 띠의 폭은 노드마다 그 노드의 밀도로 잰다.
 * 스크롤해서 가려진 것처럼 보이는 부분이 없는(경계가 빈) 것은 누를 수 없으므로 건너뛴다.
 */
fun ComposeTestRule.assertClickablesAvoid(hinge: Hinge) {
    val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue("no clickable nodes", nodes.isNotEmpty())
    for (node in nodes) {
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        if (node.layoutBoundsInWindow().isEmpty) continue
        val bounds = node.touchBoundsInWindow()
        val (start, end) = if (hinge.isVertical) bounds.left to bounds.right else bounds.top to bounds.bottom
        assertTrue(
            "$bounds overlaps hinge band $band",
            end <= band.start + ROUNDING_TOLERANCE_PX || start >= band.end - ROUNDING_TOLERANCE_PX
        )
    }
}
