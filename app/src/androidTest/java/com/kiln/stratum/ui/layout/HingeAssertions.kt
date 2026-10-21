package com.kiln.stratum.ui.layout

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.dp
import com.kiln.stratum.ui.edit.EditScreenTags
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

/**
 * 힌지 규칙의 예외(사용자 결정, CLAUDE.md 6장): 상단 바 가운데의 배율 표시는 펼친 화면 가운데의 접히는 선 위에도 둔다.
 * 확대 상태에서는 눌러 맞추는 버튼이라 검사에서 뺀다.
 */
private val HingeRuleExceptions = setOf(EditScreenTags.ZOOM_CHIP)

/**
 * 누를 수 있는 것(비활성 포함)의 터치 영역(최소 48dp)이 모두 [hinge]의 띠(양쪽 [HingeMargin]) 밖에 있는지 확인한다.
 * 화면 크기 강제(ForcedSize)는 밀도를 바꾸므로, 띠의 폭은 노드마다 그 노드의 밀도로 잰다.
 * 스크롤해서 가려진 것처럼 보이는 부분이 없는(경계가 빈) 것은 누를 수 없으므로 건너뛴다. [HingeRuleExceptions]도 건너뛴다.
 */
fun ComposeTestRule.assertClickablesAvoid(hinge: Hinge) {
    val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue("no clickable nodes", nodes.isNotEmpty())
    for (node in nodes) {
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        if (node.layoutBoundsInWindow().isEmpty) continue
        if (node.config.getOrNull(SemanticsProperties.TestTag) in HingeRuleExceptions) continue
        val bounds = node.touchBoundsInWindow()
        val (start, end) = if (hinge.isVertical) bounds.left to bounds.right else bounds.top to bounds.bottom
        assertTrue(
            "$bounds overlaps hinge band $band: ${node.config}",
            end <= band.start + ROUNDING_TOLERANCE_PX || start >= band.end - ROUNDING_TOLERANCE_PX
        )
    }
}
