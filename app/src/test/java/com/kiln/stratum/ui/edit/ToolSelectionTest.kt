package com.kiln.stratum.ui.edit

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolSelectionTest {

    @Test
    fun tap_picksTheTool_andTappingAgainPutsItDown() {
        val selection = ToolSelection(active = null, transformMode = SubTool.CROP)

        selection.tap(EditTool.Transform)
        assertEquals(EditTool.Transform, selection.active)

        selection.tap(EditTool.Transform)
        assertNull(selection.active)
    }

    @Test
    fun choose_switchesModeAndPicksTheTool() {
        val selection = ToolSelection(active = null, transformMode = SubTool.CROP)

        selection.choose(SubTool.ROTATE)

        assertEquals(EditTool.Transform, selection.active)
        assertEquals(SubTool.ROTATE, selection.transformMode)
    }

    @Test
    fun oneShotSubTool_isNotRemembered() {
        val selection = ToolSelection(active = null, transformMode = SubTool.ROTATE)

        // 크기 변경은 창을 띄우고 끝나므로, 고른 도구와 시작할 하위 도구를 바꾸지 않는다
        selection.choose(SubTool.RESIZE)

        assertNull(selection.active)
        assertEquals(SubTool.ROTATE, selection.transformMode)
        assertThrows(IllegalArgumentException::class.java) { ToolSelection(null, SubTool.RESIZE) }
    }

    @Test
    fun saver_keepsToolAndMode() {
        val scope = SaverScope { true }
        for (selection in listOf(ToolSelection(EditTool.Transform, SubTool.ROTATE), ToolSelection(null, SubTool.CROP))) {
            val saved = checkNotNull(with(ToolSelection.Saver) { scope.save(selection) })

            val restored = checkNotNull(ToolSelection.Saver.restore(saved))

            assertEquals(selection.active, restored.active)
            assertEquals(selection.transformMode, restored.transformMode)
        }
    }
}
