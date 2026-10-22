package com.kiln.stratum.ui.edit

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kiln.stratum.R
import kotlin.math.roundToInt

/**
 * 편집 화면의 키보드 단축키가 하는 일(사용자 피드백 12차 4: "사진 끌어다 놓아 열기, 키보드 마우스 단축키 먼저 하고").
 * 키는 포토샵과 같게 둔다(계획 승인). 글자 칸에 쓰는 동안처럼 화면이 이미 쓴 키는 단축키가 되지 않는다(MainActivity).
 */
sealed interface Shortcut {
    /** 실행 취소(Ctrl+Z) */
    data object Undo : Shortcut

    /** 다시 실행(Ctrl+Shift+Z, Ctrl+Y) */
    data object Redo : Shortcut

    /** [tool] 도구를 고른다. [subTool]이 있으면 그 하위 도구로 고른다(V 이동, M 사각형 선택 등). */
    data class PickTool(val tool: EditTool, val subTool: SubTool? = null) : Shortcut

    /** 붓 크기를 한 단계 키우거나(]) 줄인다([) */
    data class Size(val larger: Boolean) : Shortcut

    /** 붓 경도를 한 단계 올리거나(Shift+]) 내린다(Shift+[) */
    data class Hardness(val harder: Boolean) : Shortcut

    /** 화면에 맞추기(Ctrl+0) */
    data object FitToScreen : Shortcut

    /** 100%로 보기(Ctrl+1) */
    data object ActualPixels : Shortcut

    /** 확대(Ctrl+=)·축소(Ctrl+-) */
    data class Zoom(val zoomIn: Boolean) : Shortcut

    /** 모두 선택(Ctrl+A) */
    data object SelectAll : Shortcut

    /** 선택 해제(Ctrl+D) */
    data object Deselect : Shortcut

    /** 선택 반전(Ctrl+Shift+I) */
    data object InvertSelection : Shortcut

    /** 새 레이어(Ctrl+Shift+N) */
    data object NewLayer : Shortcut

    /** 레이어 복제(Ctrl+J) */
    data object DuplicateLayer : Shortcut

    /** 아래 레이어와 병합(Ctrl+E) */
    data object MergeDown : Shortcut

    /** 고른 곳 지우기(Delete) */
    data object DeleteSelected : Shortcut

    /** 자르기 적용(Enter) */
    data object Confirm : Shortcut

    /** 자르기 취소, 그다음은 도구 닫기(Esc). 편집 화면을 나가지는 않는다. */
    data object Cancel : Shortcut

    /** 스페이스바를 누르기 시작했다. 받으면(true) 누르고 있는 동안 캔버스를 끌어 옮긴다([KeyShortcutHost.spaceHeld]). */
    data object Pan : Shortcut
}

/** 누르고 있으면 되풀이하는 단축키(크기·경도·확대·축소). 나머지는 한 번 누를 때 한 번만 한다. */
val Shortcut.repeats: Boolean
    get() = this is Shortcut.Size || this is Shortcut.Hardness || this is Shortcut.Zoom

/**
 * 키보드 단축키를 받을 곳. 앱(MainActivity)이 하나 두고, 편집 화면이 떠 있는 동안 [handler]를 맡긴다(홈·설정에서는 단축키가 없다).
 * [spaceHeld]는 스페이스바를 누르고 있는지다(누른 채 끌면 캔버스를 옮긴다, 포토샵과 같다).
 */
class KeyShortcutHost {

    /** 단축키를 한 일로 바꾼다. 했으면 true. 편집 화면이 없으면 null. */
    var handler: ((Shortcut) -> Boolean)? = null

    var spaceHeld by mutableStateOf(false)
        private set

    /**
     * 화면(글자 칸 등)이 쓰지 않은 키를 받는다. 단축키로 썼으면 true다.
     * 스페이스바는 편집 화면이 있을 때만 누름·뗌을 기억한다(창이 포커스를 잃으면 [release]로 뗀 것으로 본다).
     */
    fun onKey(event: KeyEvent): Boolean {
        val handle = handler ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_SPACE) {
            if (event.action == KeyEvent.ACTION_UP) {
                val held = spaceHeld
                spaceHeld = false
                return held
            }
            // 편집 화면이 받을 때만 누른 것으로 본다(창이 떠 있으면 창의 몫)
            if (event.repeatCount == 0 && !spaceHeld && !handle(Shortcut.Pan)) return false
            spaceHeld = true
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val shortcut = shortcutFor(event.keyCode, event.isCtrlPressed || event.isMetaPressed, event.isShiftPressed, event.isAltPressed) ?: return false
        if (event.repeatCount > 0 && !shortcut.repeats) return true
        return handle(shortcut)
    }

    /** 누르고 있던 키를 뗀 것으로 본다(창이 포커스를 잃거나 편집 화면이 사라질 때) */
    fun release() {
        spaceHeld = false
    }
}

/** 키보드 단축키를 받을 곳(앱이 넘긴다). 테스트·미리 보기에서는 아무도 키를 보내지 않는 빈 것이다. */
val LocalKeyShortcutHost = staticCompositionLocalOf { KeyShortcutHost() }

/**
 * [keyCode] 키와 함께 누른 보조 키로 단축키를 찾는다. 없으면 null.
 * [command]는 Ctrl(맥 키보드는 Command, 안드로이드의 Meta)이다. Alt를 함께 누른 키는 단축키가 아니다(다른 앱·시스템 몫).
 */
fun shortcutFor(keyCode: Int, command: Boolean, shift: Boolean, alt: Boolean): Shortcut? {
    if (alt) return null
    if (command) {
        return when (keyCode) {
            KeyEvent.KEYCODE_Z -> if (shift) Shortcut.Redo else Shortcut.Undo
            KeyEvent.KEYCODE_Y -> Shortcut.Redo.takeIf { !shift }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> Shortcut.FitToScreen.takeIf { !shift }
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> Shortcut.ActualPixels.takeIf { !shift }
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> Shortcut.Zoom(zoomIn = true)
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Shortcut.Zoom(zoomIn = false)
            KeyEvent.KEYCODE_A -> Shortcut.SelectAll.takeIf { !shift }
            KeyEvent.KEYCODE_D -> Shortcut.Deselect.takeIf { !shift }
            KeyEvent.KEYCODE_I -> Shortcut.InvertSelection.takeIf { shift }
            KeyEvent.KEYCODE_N -> Shortcut.NewLayer.takeIf { shift }
            KeyEvent.KEYCODE_J -> Shortcut.DuplicateLayer.takeIf { !shift }
            KeyEvent.KEYCODE_E -> Shortcut.MergeDown.takeIf { !shift }
            else -> null
        }
    }
    return when (keyCode) {
        KeyEvent.KEYCODE_LEFT_BRACKET -> if (shift) Shortcut.Hardness(harder = false) else Shortcut.Size(larger = false)
        KeyEvent.KEYCODE_RIGHT_BRACKET -> if (shift) Shortcut.Hardness(harder = true) else Shortcut.Size(larger = true)
        KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> Shortcut.DeleteSelected.takeIf { !shift }
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Shortcut.Confirm.takeIf { !shift }
        KeyEvent.KEYCODE_ESCAPE -> Shortcut.Cancel
        else -> if (shift) null else toolKey(keyCode)
    }
}

/** 도구 글자 키(포토샵과 같다). 흐림 도구는 포토샵에 정해진 키가 없어 R을 쓴다(계획 승인). */
private fun toolKey(keyCode: Int): Shortcut? = when (keyCode) {
    KeyEvent.KEYCODE_V -> Shortcut.PickTool(EditTool.Move)
    KeyEvent.KEYCODE_C -> Shortcut.PickTool(EditTool.Transform, SubTool.CROP)
    KeyEvent.KEYCODE_M -> Shortcut.PickTool(EditTool.Select, SubTool.SELECT_RECT)
    KeyEvent.KEYCODE_L -> Shortcut.PickTool(EditTool.Select, SubTool.SELECT_LASSO)
    KeyEvent.KEYCODE_W -> Shortcut.PickTool(EditTool.Select, SubTool.SELECT_WAND)
    KeyEvent.KEYCODE_B -> Shortcut.PickTool(EditTool.Brush)
    KeyEvent.KEYCODE_E -> Shortcut.PickTool(EditTool.Eraser)
    KeyEvent.KEYCODE_T -> Shortcut.PickTool(EditTool.Text)
    KeyEvent.KEYCODE_J -> Shortcut.PickTool(EditTool.Retouch)
    KeyEvent.KEYCODE_O -> Shortcut.PickTool(EditTool.Tone)
    KeyEvent.KEYCODE_R -> Shortcut.PickTool(EditTool.Blur)
    else -> null
}

/**
 * 대괄호 키로 바꾼 붓 크기(포토샵과 비슷한 단계): 10px 아래는 1px, 100px 아래는 10px, 200px 아래는 25px, 300px 아래는 50px, 그 위는 100px씩.
 * 줄일 때는 지금 크기보다 작은 쪽 단계로 내려간다. [min]~[max] 안이다.
 */
fun steppedBrushSize(size: Float, larger: Boolean, min: Float = 1f, max: Float = 1000f): Float {
    val current = size.roundToInt()
    // 줄일 때는 한 칸 아래에서 단계를 고른다(100에서 줄이면 10씩: 90)
    val basis = if (larger) current else current - 1
    val step = when {
        basis < 10 -> 1
        basis < 100 -> 10
        basis < 200 -> 25
        basis < 300 -> 50
        else -> 100
    }
    val next = if (larger) (current / step + 1) * step else ((current - 1) / step) * step
    return next.toFloat().coerceIn(min, max)
}

/** Shift+대괄호로 바꾼 경도: 25%씩(포토샵과 같다), 0~1 안이다. */
fun steppedHardness(hardness: Float, harder: Boolean): Float {
    val quarters = hardness * 4f
    val next = if (harder) kotlin.math.floor(quarters + 1e-3f) + 1f else kotlin.math.ceil(quarters - 1e-3f) - 1f
    return (next / 4f).coerceIn(0f, 1f)
}

/** 키보드로 확대·축소할 때 한 번의 배율(1.5배, 줄이면 2/3배라 100%에서 66.7%·150%로 간다) */
internal const val KEY_ZOOM_STEP = 1.5

/** 시스템의 '단축키 보기'(메타+/)에 보일 편집 화면의 단축키 목록(두 언어) */
fun keyboardShortcutGroup(context: Context): KeyboardShortcutGroup {
    val ctrl = KeyEvent.META_CTRL_ON
    val shift = KeyEvent.META_SHIFT_ON
    fun item(@StringRes label: Int, keyCode: Int, modifiers: Int = 0) = KeyboardShortcutInfo(context.getString(label), keyCode, modifiers)
    return KeyboardShortcutGroup(
        context.getString(R.string.shortcut_group_edit),
        listOf(
            item(R.string.action_undo, KeyEvent.KEYCODE_Z, ctrl),
            item(R.string.action_redo, KeyEvent.KEYCODE_Z, ctrl or shift),
            item(R.string.tool_move, KeyEvent.KEYCODE_V),
            item(R.string.subtool_crop, KeyEvent.KEYCODE_C),
            item(R.string.subtool_select_rect, KeyEvent.KEYCODE_M),
            item(R.string.subtool_select_lasso, KeyEvent.KEYCODE_L),
            item(R.string.subtool_select_wand, KeyEvent.KEYCODE_W),
            item(R.string.tool_brush, KeyEvent.KEYCODE_B),
            item(R.string.tool_eraser, KeyEvent.KEYCODE_E),
            item(R.string.tool_text, KeyEvent.KEYCODE_T),
            item(R.string.tool_retouch, KeyEvent.KEYCODE_J),
            item(R.string.tool_tone, KeyEvent.KEYCODE_O),
            item(R.string.tool_blur, KeyEvent.KEYCODE_R),
            item(R.string.shortcut_brush_smaller, KeyEvent.KEYCODE_LEFT_BRACKET),
            item(R.string.shortcut_brush_larger, KeyEvent.KEYCODE_RIGHT_BRACKET),
            item(R.string.shortcut_brush_softer, KeyEvent.KEYCODE_LEFT_BRACKET, shift),
            item(R.string.shortcut_brush_harder, KeyEvent.KEYCODE_RIGHT_BRACKET, shift),
            item(R.string.canvas_fit, KeyEvent.KEYCODE_0, ctrl),
            item(R.string.shortcut_actual_pixels, KeyEvent.KEYCODE_1, ctrl),
            item(R.string.shortcut_zoom_in, KeyEvent.KEYCODE_EQUALS, ctrl),
            item(R.string.shortcut_zoom_out, KeyEvent.KEYCODE_MINUS, ctrl),
            item(R.string.shortcut_pan, KeyEvent.KEYCODE_SPACE),
            item(R.string.selection_all_description, KeyEvent.KEYCODE_A, ctrl),
            item(R.string.selection_none, KeyEvent.KEYCODE_D, ctrl),
            item(R.string.shortcut_invert_selection, KeyEvent.KEYCODE_I, ctrl or shift),
            item(R.string.selection_delete_description, KeyEvent.KEYCODE_FORWARD_DEL),
            item(R.string.layer_add, KeyEvent.KEYCODE_N, ctrl or shift),
            item(R.string.layer_duplicate, KeyEvent.KEYCODE_J, ctrl),
            item(R.string.layer_merge_down, KeyEvent.KEYCODE_E, ctrl),
            item(R.string.shortcut_apply_crop, KeyEvent.KEYCODE_ENTER),
            item(R.string.shortcut_cancel, KeyEvent.KEYCODE_ESCAPE)
        )
    )
}

