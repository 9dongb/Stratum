package com.kiln.stratum.ui.edit

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 키보드 단축키 표(포토샵과 같은 키, 사용자 피드백 12차 4)와 대괄호 단계 */
class KeyShortcutsTest {

    private fun key(code: Int, command: Boolean = false, shift: Boolean = false, alt: Boolean = false) = shortcutFor(code, command, shift, alt)

    @Test
    fun commandKeys_undoRedoViewSelectionAndLayers() {
        assertEquals(Shortcut.Undo, key(KeyEvent.KEYCODE_Z, command = true))
        assertEquals(Shortcut.Redo, key(KeyEvent.KEYCODE_Z, command = true, shift = true))
        assertEquals(Shortcut.Redo, key(KeyEvent.KEYCODE_Y, command = true))
        assertEquals(Shortcut.FitToScreen, key(KeyEvent.KEYCODE_0, command = true))
        assertEquals(Shortcut.ActualPixels, key(KeyEvent.KEYCODE_1, command = true))
        assertEquals(Shortcut.Zoom(zoomIn = true), key(KeyEvent.KEYCODE_EQUALS, command = true))
        assertEquals(Shortcut.Zoom(zoomIn = false), key(KeyEvent.KEYCODE_MINUS, command = true))
        assertEquals(Shortcut.SelectAll, key(KeyEvent.KEYCODE_A, command = true))
        assertEquals(Shortcut.Deselect, key(KeyEvent.KEYCODE_D, command = true))
        assertEquals(Shortcut.InvertSelection, key(KeyEvent.KEYCODE_I, command = true, shift = true))
        assertEquals(Shortcut.NewLayer, key(KeyEvent.KEYCODE_N, command = true, shift = true))
        assertEquals(Shortcut.DuplicateLayer, key(KeyEvent.KEYCODE_J, command = true))
        assertEquals(Shortcut.MergeDown, key(KeyEvent.KEYCODE_E, command = true))
        // Ctrl+I·Ctrl+N(Shift 없이)은 단축키가 아니다
        assertNull(key(KeyEvent.KEYCODE_I, command = true))
        assertNull(key(KeyEvent.KEYCODE_N, command = true))
    }

    @Test
    fun letterKeys_pickTools_likePhotoshop() {
        assertEquals(Shortcut.PickTool(EditTool.Move), key(KeyEvent.KEYCODE_V))
        assertEquals(Shortcut.PickTool(EditTool.Transform, SubTool.CROP), key(KeyEvent.KEYCODE_C))
        assertEquals(Shortcut.PickTool(EditTool.Select, SubTool.SELECT_RECT), key(KeyEvent.KEYCODE_M))
        assertEquals(Shortcut.PickTool(EditTool.Select, SubTool.SELECT_LASSO), key(KeyEvent.KEYCODE_L))
        assertEquals(Shortcut.PickTool(EditTool.Select, SubTool.SELECT_WAND), key(KeyEvent.KEYCODE_W))
        assertEquals(Shortcut.PickTool(EditTool.Brush), key(KeyEvent.KEYCODE_B))
        assertEquals(Shortcut.PickTool(EditTool.Eraser), key(KeyEvent.KEYCODE_E))
        assertEquals(Shortcut.PickTool(EditTool.Text), key(KeyEvent.KEYCODE_T))
        assertEquals(Shortcut.PickTool(EditTool.Retouch), key(KeyEvent.KEYCODE_J))
        assertEquals(Shortcut.PickTool(EditTool.Tone), key(KeyEvent.KEYCODE_O))
        assertEquals(Shortcut.PickTool(EditTool.Blur), key(KeyEvent.KEYCODE_R))
        // Shift·Alt와 함께면 도구 키가 아니다
        assertNull(key(KeyEvent.KEYCODE_B, shift = true))
        assertNull(key(KeyEvent.KEYCODE_B, alt = true))
        assertNull(key(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun bracketsDeleteEnterAndEscape() {
        assertEquals(Shortcut.Size(larger = true), key(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(Shortcut.Size(larger = false), key(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertEquals(Shortcut.Hardness(harder = true), key(KeyEvent.KEYCODE_RIGHT_BRACKET, shift = true))
        assertEquals(Shortcut.Hardness(harder = false), key(KeyEvent.KEYCODE_LEFT_BRACKET, shift = true))
        assertEquals(Shortcut.DeleteSelected, key(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(Shortcut.DeleteSelected, key(KeyEvent.KEYCODE_DEL))
        assertEquals(Shortcut.Confirm, key(KeyEvent.KEYCODE_ENTER))
        assertEquals(Shortcut.Cancel, key(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun brushSizeSteps_growWithTheSize_andSnapToTheStep() {
        assertEquals(70f, steppedBrushSize(60f, larger = true))
        assertEquals(70f, steppedBrushSize(65f, larger = true))
        assertEquals(50f, steppedBrushSize(60f, larger = false))
        assertEquals(60f, steppedBrushSize(65f, larger = false))
        assertEquals(90f, steppedBrushSize(100f, larger = false))
        assertEquals(125f, steppedBrushSize(100f, larger = true))
        assertEquals(10f, steppedBrushSize(9f, larger = true))
        assertEquals(9f, steppedBrushSize(10f, larger = false))
        assertEquals(250f, steppedBrushSize(300f, larger = false))
        assertEquals(400f, steppedBrushSize(300f, larger = true))
        // 끝에서 멈춘다
        assertEquals(1f, steppedBrushSize(1f, larger = false))
        assertEquals(1000f, steppedBrushSize(1000f, larger = true))
    }

    @Test
    fun hardnessSteps_areQuarters() {
        assertEquals(0.75f, steppedHardness(0.5f, harder = true))
        assertEquals(0.75f, steppedHardness(0.6f, harder = true))
        assertEquals(0.25f, steppedHardness(0.5f, harder = false))
        assertEquals(0.5f, steppedHardness(0.6f, harder = false))
        assertEquals(1f, steppedHardness(1f, harder = true))
        assertEquals(0f, steppedHardness(0f, harder = false))
    }
}
