package com.kiln.stratum.ui.edit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction

/**
 * 커버 화면(Compact)에서 [tool]을 누른다(결정 기록 '커버 화면 개편'). 탭 줄에 있는 도구는 그 탭을 누르고,
 * 없는 도구(자르기·지우개·텍스트·명암·브러시·모자이크)는 더보기 줄을 연 뒤(떠 있지 않으면 더보기 칸을 누름) 그 도구를 고른다.
 * 탭 줄의 도구를 이미 골랐으면 탭을 다시 누른 것과 같다(하위 도구 메뉴 등). 더보기 줄의 도구는 다시 골라도 그대로다.
 */
fun SemanticsNodeInteractionsProvider.chooseCoverTool(tool: EditTool) {
    if (tool in EditTool.coverTabs) {
        onNodeWithTag(EditScreenTags.tabTool(tool)).performClick()
    } else {
        openCoverMoreTools()
        onNodeWithTag(EditScreenTags.moreTool(tool)).performClick()
    }
}

/** 커버 더보기 줄이 떠 있지 않으면 더보기 칸을 눌러 연다 */
private fun SemanticsNodeInteractionsProvider.openCoverMoreTools() {
    if (onAllNodesWithTag(EditScreenTags.COVER_MORE_TOOLS).fetchSemanticsNodes().isEmpty()) {
        onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
    }
}

/** 커버 한 줄 막대의 [key] 칩을 누른다(값 칩은 슬라이더 줄, 고르기 칩은 고를 것들, 동작 칩은 버튼 줄로 펼친다) */
fun SemanticsNodeInteractionsProvider.expandBarChip(key: String) {
    // 칩이 넘쳐 옆으로 밀어 보는 줄이면 보이게 민 뒤 누른다
    onNodeWithTag(EditScreenTags.bar(key)).performScrollTo().performClick()
}

/** 막대 칩 [keys]의 가로 자리(창 기준, 줄 밖으로 밀려 있어도 잘리지 않은 자리) */
fun SemanticsNodeInteractionsProvider.barChipLefts(keys: List<String>): List<Float> =
    keys.map { onNodeWithTag(EditScreenTags.bar(it)).fetchSemanticsNode().positionInWindow.x }

/** 펼친 칩을 접는다(막대 오른쪽 끝 ✓) */
fun SemanticsNodeInteractionsProvider.collapseBar() {
    onNodeWithTag(EditScreenTags.BAR_DONE).performClick()
}

/**
 * 커버에서 [subTool]을 하위 도구 메뉴로 고른다(막대에는 하위 도구 칩이 없다, 2026-10-23 사용자 요청). 그 도구의 탭(더보기 줄의 도구면
 * 더보기 줄을 열고 그 칸)을 길게 눌러(화면 읽기 기능의 길게 누르기와 같은 길) 메뉴를 열고 항목을 누른다. 도구를 고르지 않았어도 함께 고른다.
 */
fun SemanticsNodeInteractionsProvider.chooseCoverSubTool(subTool: SubTool) {
    val tool = EditTool.of(subTool)
    if (tool in EditTool.coverTabs) {
        onNodeWithTag(EditScreenTags.tabTool(tool)).performSemanticsAction(SemanticsActions.OnLongClick)
    } else {
        openCoverMoreTools()
        onNodeWithTag(EditScreenTags.moreTool(tool)).performSemanticsAction(SemanticsActions.OnLongClick)
    }
    onNodeWithTag(EditScreenTags.subTool(subTool)).performClick()
}

/** 커버 조정 막대에서 [item] 칩을 누른다(슬라이더 항목은 같은 줄이 슬라이더, 곡선은 막대 위에 곡선 편집기) */
fun SemanticsNodeInteractionsProvider.expandAdjustChip(item: AdjustItem) {
    onNodeWithTag(EditScreenTags.adjustChip(item)).performScrollTo().performClick()
}

/** 상단 바의 [panel] 버튼(조정·레이어)을 누른다(켜고 끔). 커버·메인 화면 모두 같은 버튼이다. */
fun SemanticsNodeInteractionsProvider.togglePanel(panel: MainPanel) {
    onNodeWithTag(EditScreenTags.panelToggle(panel)).performClick()
}

/**
 * 글자가 한 줄로 잘리지 않고 다 보이는지. 옆으로 밀어 보는 줄 안의 글자는 폭 제한 없이 놓여 `hasVisualOverflow`가 늘 참이라
 * (글 상자 폭이 무한대) 그것으로 보지 않고, 한 줄이며 놓인 폭이 글자 끝까지 닿는지 본다.
 */
fun TextLayoutResult.showsWholeLine(): Boolean =
    lineCount == 1 && !didOverflowHeight && size.width + 1f >= getLineRight(0)
