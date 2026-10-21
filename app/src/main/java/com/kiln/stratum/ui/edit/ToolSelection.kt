package com.kiln.stratum.ui.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * 고른 도구와 하위 도구. 편집 화면 맨 위에서 기억해, 접기·펼치기나 화면 배치가 바뀌어도 이어진다.
 *
 * @param active 지금 고른 도구. 없으면 null(캔버스만 본다).
 * @param modes 하위 도구가 있는 도구마다, 그 도구를 고르면 시작할 하위 도구(자르기·변형은 자르기 또는 회전·뒤집기 등).
 *   없는 도구는 첫 하위 도구로 시작한다.
 */
@Stable
class ToolSelection(active: EditTool?, modes: Map<EditTool, SubTool> = emptyMap()) {

    var active: EditTool? by mutableStateOf(active)
        private set

    private var modes: Map<EditTool, SubTool> by mutableStateOf(modes)

    init {
        for ((tool, subTool) in modes) {
            require(!subTool.oneShot) { "One-shot sub-tool cannot be a mode: $subTool" }
            require(subTool in tool.subTools) { "$subTool is not a sub-tool of $tool" }
        }
    }

    /** [tool]을 고르면 시작할 하위 도구. 하위 도구가 없는 도구면 null. */
    fun modeOf(tool: EditTool): SubTool? = modes[tool] ?: tool.subTools.firstOrNull { !it.oneShot }

    /** 자르기·변형 도구를 고르면 시작할 하위 도구(자르기 또는 회전·뒤집기) */
    val transformMode: SubTool
        get() = checkNotNull(modeOf(EditTool.Transform))

    /** [tool]을 누른다. 고른 도구를 다시 누르면 내려놓는다. */
    fun tap(tool: EditTool) {
        active = if (active == tool) null else tool
    }

    /**
     * 하위 도구 [subTool]을 고른다(길게 누른 메뉴, 옵션 위쪽 버튼). 그 하위 도구가 속한 도구를 고른 상태가 된다.
     * 한 번 하고 끝나는 하위 도구(크기 변경)는 화면이 창을 띄우고, 고른 도구는 바꾸지 않는다.
     */
    fun choose(subTool: SubTool) {
        if (subTool.oneShot) return
        val tool = EditTool.of(subTool)
        modes = modes + (tool to subTool)
        active = tool
    }

    /** 고른 도구를 내려놓는다. */
    fun close() {
        active = null
    }

    companion object {
        /**
         * 고른 도구의 이름(없으면 빈 문자열)과, 도구마다의 하위 도구 이름으로 저장한다.
         * 되살릴 때 지금 앱에 없는 이름(없앤 하위 도구 '회전·뒤집기' 등, 사용자 피드백 8차 1)이나 그 도구의 것이 아닌 짝은 건너뛴다.
         */
        val Saver: Saver<ToolSelection, List<String>> = Saver(
            save = { selection ->
                listOf(selection.active?.name.orEmpty()) + selection.modes.map { (tool, subTool) -> "${tool.name}=${subTool.name}" }
            },
            restore = { saved ->
                ToolSelection(
                    active = EditTool.entries.firstOrNull { it.name == saved[0] },
                    modes = saved.drop(1).mapNotNull { entry ->
                        val names = entry.split('=')
                        val tool = EditTool.entries.firstOrNull { it.name == names.first() }
                        val subTool = SubTool.entries.firstOrNull { it.name == names.last() }
                        if (tool != null && subTool != null && subTool in tool.subTools && !subTool.oneShot) tool to subTool else null
                    }.toMap()
                )
            }
        )
    }
}

/** 처음에는 아무 도구도 고르지 않았고, 하위 도구는 도구마다 첫 하위 도구부터 시작한다(자르기·변형은 자르기). */
@Composable
fun rememberToolSelection(): ToolSelection =
    rememberSaveable(saver = ToolSelection.Saver) { ToolSelection(active = null) }
