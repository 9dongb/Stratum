package com.kiln.stratum.ui.edit

/** 화면 테스트가 편집 화면의 부분을 찾을 때 쓰는 이름표(testTag). 화면에는 보이지 않는다. */
object EditScreenTags {
    const val CANVAS = "editCanvas"
    const val ZOOM_CHIP = "zoomChip"
    const val BUSY = "editBusy"
    const val UNDO = "undo"
    const val REDO = "redo"
    const val TOOL_OPTIONS = "toolOptions"
    const val ROTATE_LEFT = "rotateLeft"
    const val ROTATE_RIGHT = "rotateRight"
    const val FLIP_HORIZONTAL = "flipHorizontal"
    const val FLIP_VERTICAL = "flipVertical"
    const val TOOL_RAIL = "toolRail"
    const val TOOL_TAB_BAR = "toolTabBar"
    const val BOTTOM_PANEL = "bottomPanel"
    const val SIDE_PANEL = "sidePanel"

    /** 왼쪽 도구 막대의 [tool] 버튼 */
    fun railTool(tool: EditTool): String = "railTool_${tool.name}"

    /** 커버 화면 탭 줄의 [tool] 칸 */
    fun tabTool(tool: EditTool): String = "tabTool_${tool.name}"

    /** 길게 눌러 뜬 메뉴의 [subTool] 항목 */
    fun subTool(subTool: SubTool): String = "subToolMenu_${subTool.name}"

    /** 옵션 위쪽에서 [subTool]로 바꾸는 버튼 */
    fun subToolOption(subTool: SubTool): String = "subToolOption_${subTool.name}"
}
