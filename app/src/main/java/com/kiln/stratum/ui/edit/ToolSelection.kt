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
 * @param transformMode 자르기·변형 도구를 고르면 시작할 하위 도구(자르기 또는 회전·뒤집기).
 */
@Stable
class ToolSelection(active: EditTool?, transformMode: SubTool) {

    var active: EditTool? by mutableStateOf(active)
        private set

    var transformMode: SubTool by mutableStateOf(transformMode)
        private set

    init {
        require(!transformMode.oneShot) { "One-shot sub-tool cannot be a mode: $transformMode" }
    }

    /** [tool]을 누른다. 고른 도구를 다시 누르면 내려놓는다. */
    fun tap(tool: EditTool) {
        active = if (active == tool) null else tool
    }

    /**
     * 하위 도구 [subTool]을 고른다(길게 누른 메뉴, 옵션 위쪽 버튼). 그 하위 도구의 자르기·변형 도구를 고른 상태가 된다.
     * 한 번 하고 끝나는 하위 도구(크기 변경)는 화면이 창을 띄우고, 고른 도구는 바꾸지 않는다.
     */
    fun choose(subTool: SubTool) {
        if (subTool.oneShot) return
        transformMode = subTool
        active = EditTool.Transform
    }

    /** 고른 도구를 내려놓는다. */
    fun close() {
        active = null
    }

    companion object {
        /** 도구와 하위 도구의 이름으로 저장한다. 고른 도구가 없으면 빈 문자열이다. */
        val Saver: Saver<ToolSelection, List<String>> = Saver(
            save = { listOf(it.active?.name.orEmpty(), it.transformMode.name) },
            restore = { saved ->
                ToolSelection(
                    active = saved[0].takeIf { it.isNotEmpty() }?.let(EditTool::valueOf),
                    transformMode = SubTool.valueOf(saved[1])
                )
            }
        )
    }
}

/** 처음에는 아무 도구도 고르지 않았고, 자르기·변형은 자르기부터 시작한다. */
@Composable
fun rememberToolSelection(): ToolSelection =
    rememberSaveable(saver = ToolSelection.Saver) { ToolSelection(active = null, transformMode = SubTool.CROP) }
