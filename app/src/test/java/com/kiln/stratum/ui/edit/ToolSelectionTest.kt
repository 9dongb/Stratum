package com.kiln.stratum.ui.edit

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolSelectionTest {

    /** 선택 도구의 하위 도구를 올가미로 바꿔 둔 상태 */
    private fun lassoing() = ToolSelection(active = null, modes = mapOf(EditTool.Select to SubTool.SELECT_LASSO))

    @Test
    fun coverTabs_followTheUserOrder_andTheMoreRowHasTheRest() {
        // 사용자 지정(2026-10-23): "자르기 도구 위치에 이동 도구를, 브러시 위치에 흐림 도구를 위치해주세요"
        assertEquals(listOf(EditTool.Move, EditTool.Select, EditTool.Blur, EditTool.Retouch, EditTool.Liquify), EditTool.coverTabs)
        // 더보기 줄의 자르기·브러시는 이동·흐림이 있던 자리다
        assertEquals(listOf(EditTool.Transform, EditTool.Eraser, EditTool.Text, EditTool.Tone, EditTool.Brush, EditTool.Mosaic), EditTool.coverMoreTools)
        // 메인 도구 막대의 도구는 탭 줄과 더보기 줄에 한 번씩 다 있다
        assertEquals(EditTool.railTools.size, EditTool.coverTabs.size + EditTool.coverMoreTools.size)
        assertEquals(EditTool.railTools.toSet(), (EditTool.coverTabs + EditTool.coverMoreTools).toSet())
    }

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

        selection.choose(SubTool.SELECT_WAND)

        assertEquals(EditTool.Select, selection.active)
        assertEquals(SubTool.SELECT_WAND, selection.modeOf(EditTool.Select))
    }

    @Test
    fun choose_remembersSubToolsPerTool() {
        val selection = lassoing()

        selection.choose(SubTool.RETOUCH_HEAL)

        // 리터칭을 골랐고, 선택 도구의 하위 도구는 그대로다
        assertEquals(EditTool.Retouch, selection.active)
        assertEquals(SubTool.RETOUCH_HEAL, selection.modeOf(EditTool.Retouch))
        assertEquals(SubTool.SELECT_LASSO, selection.modeOf(EditTool.Select))
    }

    @Test
    fun oneShotSubTool_isNotRemembered() {
        val selection = lassoing()

        // 크기 변경은 창을 띄우고 끝나므로, 고른 도구와 시작할 하위 도구를 바꾸지 않는다
        selection.choose(SubTool.RESIZE)

        assertNull(selection.active)
        assertEquals(SubTool.CROP, selection.transformMode)
        assertEquals(SubTool.SELECT_LASSO, selection.modeOf(EditTool.Select))
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
        val chosen = ToolSelection(EditTool.Select, mapOf(EditTool.Retouch to SubTool.RETOUCH_CLONE, EditTool.Select to SubTool.SELECT_LASSO))
        for (selection in listOf(chosen, ToolSelection(null))) {
            val saved = checkNotNull(with(ToolSelection.Saver) { scope.save(selection) })

            val restored = checkNotNull(ToolSelection.Saver.restore(saved))

            assertEquals(selection.active, restored.active)
            assertEquals(selection.modeOf(EditTool.Retouch), restored.modeOf(EditTool.Retouch))
            assertEquals(selection.modeOf(EditTool.Select), restored.modeOf(EditTool.Select))
        }
    }

    @Test
    fun saver_skipsNamesThisAppDoesNotKnow() {
        // 앞 판 앱이 저장한 것(없앤 하위 도구 '회전·뒤집기' 등)이 남아 있어도 되살리다 멈추지 않는다(사용자 피드백 8차 1)
        val saved = listOf("Transform", "Transform=ROTATE", "Gone=CROP", "Transform=SELECT_LASSO", "Transform=RESIZE", "Select=SELECT_LASSO")

        val restored = checkNotNull(ToolSelection.Saver.restore(saved))

        assertEquals(EditTool.Transform, restored.active)
        assertEquals(SubTool.CROP, restored.transformMode)
        assertEquals(SubTool.SELECT_LASSO, restored.modeOf(EditTool.Select))
        assertNull(checkNotNull(ToolSelection.Saver.restore(listOf("Spin"))).active)
    }

    @Test
    fun iconOf_showsTheChosenSubTool_likePhotoshop() {
        // 도구 막대·탭 줄의 아이콘은 고른 하위 도구의 아이콘이다(펼친 화면에서도 지금 하위 도구가 보이게, 2026-10-22)
        val selection = ToolSelection(active = null)
        assertEquals(SubTool.RETOUCH_SPOT.icon, selection.iconOf(EditTool.Retouch))
        assertEquals(SubTool.TONE_DODGE.icon, selection.iconOf(EditTool.Tone))

        selection.choose(SubTool.RETOUCH_CLONE)
        selection.choose(SubTool.TONE_BURN)
        assertEquals(SubTool.RETOUCH_CLONE.icon, selection.iconOf(EditTool.Retouch))
        // 지금 고르지 않은 도구도 마지막에 고른 하위 도구로 보인다
        assertEquals(SubTool.RETOUCH_CLONE.icon, selection.iconOf(EditTool.Retouch).also { assertEquals(EditTool.Tone, selection.active) })
        assertEquals(SubTool.TONE_BURN.icon, selection.iconOf(EditTool.Tone))

        // 한 번 하고 끝나는 하위 도구(크기 변경)는 아이콘을 바꾸지 않고, 하위 도구가 없는 도구는 도구 아이콘이다
        selection.choose(SubTool.RESIZE)
        assertEquals(SubTool.CROP.icon, selection.iconOf(EditTool.Transform))
        assertEquals(EditTool.Brush.icon, selection.iconOf(EditTool.Brush))
    }
}
