package com.kiln.stratum.ui.edit

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolSelectionTest {

    private fun rotating() = ToolSelection(active = null, modes = mapOf(EditTool.Transform to SubTool.ROTATE))

    @Test
    fun tap_picksTheTool_andTappingAgainPutsItDown() {
        val selection = ToolSelection(active = null)

        selection.tap(EditTool.Transform)
        assertEquals(EditTool.Transform, selection.active)

        selection.tap(EditTool.Transform)
        assertNull(selection.active)
    }

    @Test
    fun eachToolStartsWithItsFirstSubTool() {
        val selection = ToolSelection(active = null)

        assertEquals(SubTool.CROP, selection.transformMode)
        assertEquals(SubTool.SELECT_RECT, selection.modeOf(EditTool.Select))
        assertNull(selection.modeOf(EditTool.Brush))
    }

    @Test
    fun choose_switchesModeAndPicksTheTool() {
        val selection = ToolSelection(active = null)

        selection.choose(SubTool.ROTATE)

        assertEquals(EditTool.Transform, selection.active)
        assertEquals(SubTool.ROTATE, selection.transformMode)
    }

    @Test
    fun choose_remembersSubToolsPerTool() {
        val selection = rotating()

        selection.choose(SubTool.SELECT_WAND)

        // 선택 도구를 골랐고, 자르기·변형의 하위 도구는 그대로다
        assertEquals(EditTool.Select, selection.active)
        assertEquals(SubTool.SELECT_WAND, selection.modeOf(EditTool.Select))
        assertEquals(SubTool.ROTATE, selection.transformMode)
    }

    @Test
    fun oneShotSubTool_isNotRemembered() {
        val selection = rotating()

        // 크기 변경은 창을 띄우고 끝나므로, 고른 도구와 시작할 하위 도구를 바꾸지 않는다
        selection.choose(SubTool.RESIZE)

        assertNull(selection.active)
        assertEquals(SubTool.ROTATE, selection.transformMode)
        assertThrows(IllegalArgumentException::class.java) { ToolSelection(null, mapOf(EditTool.Transform to SubTool.RESIZE)) }
    }

    @Test
    fun subToolOfAnotherTool_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { ToolSelection(null, mapOf(EditTool.Transform to SubTool.SELECT_LASSO)) }
    }

    @Test
    fun aiRemove_isTheFourthRetouchSubTool_andIsKeptWhenTheScreenIsRecreated() {
        assertEquals(listOf(SubTool.RETOUCH_SPOT, SubTool.RETOUCH_HEAL, SubTool.RETOUCH_CLONE, SubTool.RETOUCH_REMOVE), EditTool.Retouch.subTools)
        // AI 제거는 가져올 곳을 쓰는 리터칭 획(엔진의 RetouchMode)이 아니다
        assertNull(SubTool.RETOUCH_REMOVE.retouchMode)
        val selection = ToolSelection(active = null)

        selection.choose(SubTool.RETOUCH_REMOVE)
        val saved = checkNotNull(with(ToolSelection.Saver) { SaverScope { true }.save(selection) })
        val restored = checkNotNull(ToolSelection.Saver.restore(saved))

        assertEquals(EditTool.Retouch, restored.active)
        assertEquals(SubTool.RETOUCH_REMOVE, restored.modeOf(EditTool.Retouch))
    }

    @Test
    fun saver_keepsToolAndModes() {
        val scope = SaverScope { true }
        val chosen = ToolSelection(EditTool.Select, mapOf(EditTool.Transform to SubTool.ROTATE, EditTool.Select to SubTool.SELECT_LASSO))
        for (selection in listOf(chosen, ToolSelection(null))) {
            val saved = checkNotNull(with(ToolSelection.Saver) { scope.save(selection) })

            val restored = checkNotNull(ToolSelection.Saver.restore(saved))

            assertEquals(selection.active, restored.active)
            assertEquals(selection.transformMode, restored.transformMode)
            assertEquals(selection.modeOf(EditTool.Select), restored.modeOf(EditTool.Select))
        }
    }
}
