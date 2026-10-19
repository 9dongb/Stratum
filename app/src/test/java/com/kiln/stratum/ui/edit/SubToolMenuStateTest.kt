package com.kiln.stratum.ui.edit

import androidx.compose.ui.geometry.Offset
import com.kiln.stratum.ui.layout.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 하위 도구 메뉴 상태: 끄는 손가락 밑 항목 하이라이트와 손을 뗄 때 고르기(사용자 피드백 7차) */
class SubToolMenuStateTest {

    /** 자르기·변형 메뉴를 열고 항목 셋을 위에서부터 48픽셀씩 적는다(메뉴가 놓일 때처럼) */
    private fun openTransformMenu(): SubToolMenuState {
        val state = SubToolMenuState()
        state.open(EditTool.Transform, Region(0, 0, 48, 48), MenuSide.END)
        state.items[SubTool.CROP] = Region(60, 0, 260, 48)
        state.items[SubTool.ROTATE] = Region(60, 48, 260, 96)
        state.items[SubTool.RESIZE] = Region(60, 96, 260, 144)
        return state
    }

    @Test
    fun hover_highlightsTheItemUnderTheFinger_andTellsWhenItChanges() {
        val state = openTransformMenu()

        // 도구 버튼 위(항목 밖): 하이라이트 없음
        assertFalse(state.hover(Offset(20f, 20f)))
        assertNull(state.hovered)
        // 회전 위로: 바뀌었으니 true(짧은 진동)
        assertTrue(state.hover(Offset(100f, 60f)))
        assertEquals(SubTool.ROTATE, state.hovered)
        // 같은 항목 안에서 움직이면 false
        assertFalse(state.hover(Offset(200f, 90f)))
        // 크기 변경 위로
        assertTrue(state.hover(Offset(100f, 100f)))
        assertEquals(SubTool.RESIZE, state.hovered)
        // 항목 밖으로 나가면 하이라이트가 사라지지만 진동은 없다
        assertFalse(state.hover(Offset(300f, 100f)))
        assertNull(state.hovered)
    }

    @Test
    fun release_overAnItem_choosesIt_offTheItems_keepsTheMenuOpen() {
        val state = openTransformMenu()
        state.hover(Offset(100f, 60f))

        assertEquals(SubTool.ROTATE, state.release(Offset(100f, 60f)))
        assertNull(state.hovered)

        // 항목 밖에서 떼면 고르지 않고 메뉴는 열린 채다
        assertNull(state.release(Offset(20f, 20f)))
        assertTrue(state.isOpen)
        // 항목의 오른쪽·아래 끝은 그 항목이 아니다(다음 항목이 시작하는 자리)
        assertEquals(SubTool.RESIZE, state.itemAt(Offset(100f, 96f)))
        assertNull(state.itemAt(Offset(260f, 50f)))
    }

    @Test
    fun close_forgetsTheToolTheAnchorAndTheItems() {
        val state = openTransformMenu()
        state.hover(Offset(100f, 60f))

        state.close()

        assertFalse(state.isOpen)
        assertNull(state.anchor)
        assertNull(state.hovered)
        assertNull(state.itemAt(Offset(100f, 60f)))
    }

    @Test
    fun opening_anotherTool_startsWithoutTheOldItems() {
        val state = openTransformMenu()

        state.open(EditTool.Select, Region(0, 48, 48, 96), MenuSide.ABOVE)

        assertEquals(EditTool.Select, state.tool)
        assertEquals(MenuSide.ABOVE, state.side)
        assertNull(state.itemAt(Offset(100f, 60f)))
    }
}
