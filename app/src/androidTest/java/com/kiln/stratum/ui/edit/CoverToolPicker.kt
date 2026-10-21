package com.kiln.stratum.ui.edit

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * 커버 화면(Compact)에서 [tool]을 누른다(결정 기록 '커버 화면 개편'). 탭 줄에 있는 도구는 그 탭을 누르고,
 * 없는 도구(이동·지우개·텍스트·명암·흐림·모자이크)는 더보기 칸을 눌러 더보기 줄을 연 뒤 그 도구를 고른다.
 * 탭 줄의 도구를 이미 골랐으면 탭을 다시 누른 것과 같다(내려놓거나 하위 도구 메뉴). 더보기 줄의 도구는 다시 골라도 그대로다.
 */
fun SemanticsNodeInteractionsProvider.chooseCoverTool(tool: EditTool) {
    if (tool in EditTool.coverTabs) {
        onNodeWithTag(EditScreenTags.tabTool(tool)).performClick()
    } else {
        onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
        onNodeWithTag(EditScreenTags.moreTool(tool)).performClick()
    }
}

/** 상단 바의 [panel] 버튼(조정·레이어)을 누른다(켜고 끔). 커버·메인 화면 모두 같은 버튼이다. */
fun SemanticsNodeInteractionsProvider.togglePanel(panel: MainPanel) {
    onNodeWithTag(EditScreenTags.panelToggle(panel)).performClick()
}
