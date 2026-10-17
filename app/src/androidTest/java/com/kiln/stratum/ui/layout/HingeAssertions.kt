package com.kiln.stratum.ui.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.assertTrue

/**
 * 노드가 차지하는 레이아웃 전체의 창 좌표.
 * 버튼의 의미 노드 경계는 안쪽 클릭 영역만일 수 있어, 최소 터치 영역(48dp)까지 포함한 바깥 경계를 본다.
 */
fun SemanticsNode.layoutBoundsInWindow(): Rect = layoutInfo.coordinates.boundsInWindow()

/**
 * 누를 수 있는 것(비활성 포함)이 모두 [hinge]의 띠(양쪽 [HingeMargin]) 밖에 있는지 확인한다.
 * 화면 크기 강제(ForcedSize)는 밀도를 바꾸므로, 띠의 폭은 노드마다 그 노드의 밀도로 잰다.
 * 스크롤해서 가려진 것처럼 보이는 부분이 없는(경계가 빈) 것은 누를 수 없으므로 건너뛴다.
 */
fun ComposeTestRule.assertClickablesAvoid(hinge: Hinge) {
    val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue("no clickable nodes", nodes.isNotEmpty())
    for (node in nodes) {
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        val bounds = node.layoutBoundsInWindow()
        if (bounds.isEmpty) continue
        val (start, end) = if (hinge.isVertical) bounds.left to bounds.right else bounds.top to bounds.bottom
        assertTrue("$bounds overlaps hinge band $band", end <= band.start || start >= band.end)
    }
}
