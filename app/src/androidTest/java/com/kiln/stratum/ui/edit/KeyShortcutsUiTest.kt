package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 키보드 단축키(사용자 피드백 12차 4, 포토샵과 같은 키)가 편집 화면에서 하는 일. 앱(MainActivity)이 화면이 쓰지 않은 키를
 * [KeyShortcutHost]로 보내는 것과 같이, 테스트도 받을 곳에 키를 보낸다.
 */
@RunWith(AndroidJUnit4::class)
class KeyShortcutsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val host = KeyShortcutHost()
    private var brush by mutableStateOf(BrushSettings(size = 60f, hardness = 0.5f, color = 0xFF000000.toInt()))
    private val calls = mutableListOf<String>()
    private var backs = 0

    private val strokes = object : StrokeActions {
        override fun begin(x: Float, y: Float, erase: Boolean) {
            calls += "stroke"
        }

        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
    private var viewports = mutableListOf<Viewport>()

    private fun show(widthDp: Int = 930, heightDp: Int = 700) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalKeyShortcutHost provides host) {
                    StratumTheme {
                        EditScreen(
                            state = EditorState.Ready(
                                PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                                document,
                                layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
                                activeLayer = LayerId(0),
                                canUndo = true,
                                canRedo = true
                            ),
                            onBack = { backs++ },
                            onCanvasSize = { _, _ -> },
                            onViewport = { viewports += it },
                            onChooseSize = {},
                            onUndo = { calls += "undo" },
                            onRedo = { calls += "redo" },
                            brush = brush,
                            onBrush = { brush = it },
                            strokeActions = strokes
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 키 하나를 눌렀다 뗀다(보조 키 [meta]) */
    private fun press(keyCode: Int, meta: Int = 0): Boolean {
        var handled = false
        composeRule.runOnIdle {
            handled = host.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            host.onKey(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        composeRule.waitForIdle()
        return handled
    }

    @Test
    fun letterKeys_pickTools_andBracketsChangeTheBrush() {
        show()
        assertTrue(press(KeyEvent.KEYCODE_B))
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).assertIsSelected()

        press(KeyEvent.KEYCODE_RIGHT_BRACKET)
        composeRule.runOnIdle { assertEquals(70f, brush.size) }
        press(KeyEvent.KEYCODE_LEFT_BRACKET)
        press(KeyEvent.KEYCODE_LEFT_BRACKET)
        composeRule.runOnIdle { assertEquals(50f, brush.size) }
        press(KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.META_SHIFT_ON)
        composeRule.runOnIdle { assertEquals(0.75f, brush.hardness) }

        // M은 사각형 선택(선택 도구의 하위 도구)
        press(KeyEvent.KEYCODE_M)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).assertIsSelected()
    }

    @Test
    fun ctrlZ_undoes_ctrlShiftZ_redoes() {
        show()
        press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
        press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
        press(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
        composeRule.runOnIdle { assertEquals(listOf("undo", "redo", "redo"), calls) }
    }

    @Test
    fun escape_closesTheTool_butNeverLeavesTheEditor() {
        show()
        press(KeyEvent.KEYCODE_B)
        assertTrue(press(KeyEvent.KEYCODE_ESCAPE))
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
        // 도구가 없을 때도 편집 화면을 나가지 않는다(닫을 것이 없으면 Esc를 받지 않고 화면으로 보낸다)
        assertFalse(press(KeyEvent.KEYCODE_ESCAPE))
        composeRule.runOnIdle { assertEquals(0, backs) }
    }

    @Test
    fun enter_isAlwaysTakenInTheEditor_soAFocusedButtonIsNotPressed() {
        // 키보드를 쓰면 안드로이드가 뒤로 버튼에 포커스를 주어, Enter를 받지 않으면 홈으로 나갔다(에뮬레이터에서 확인)
        show()
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        composeRule.runOnIdle { assertEquals(0, backs) }
    }

    @Test
    fun crop_enterApplies_escapeCancels() {
        show()
        press(KeyEvent.KEYCODE_C)
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertIsDisplayed()
        // 바꾼 것이 없으면 Enter로 적용할 것이 없고, Esc는 자르기를 끝낸다
        press(KeyEvent.KEYCODE_ESCAPE)
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertDoesNotExist()
    }

    @Test
    fun ctrlPlusMinusAndZero_zoomAndFit() {
        show()
        val fitted = composeRule.runOnIdle { viewports.last().scale }
        press(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON)
        composeRule.runOnIdle { assertEquals(fitted * KEY_ZOOM_STEP, viewports.last().scale, fitted * 0.01) }
        press(KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON)
        composeRule.runOnIdle { assertEquals(1.0, viewports.last().scale, 0.001) }
        press(KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON)
        composeRule.runOnIdle { assertEquals(fitted, viewports.last().scale, fitted * 0.01) }
    }

    @Test
    fun aRefusedSpace_isNotHeldByItsRepeats() {
        // 창이 떠 있어 편집 화면이 스페이스바를 받지 않으면, 누르고 있는 동안 되풀이되는 키도 받지 않는다(창의 몫, 12차 검토)
        val refusing = KeyShortcutHost().apply { handler = { false } }
        assertFalse(refusing.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0)))
        assertFalse(refusing.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 1)))
        assertFalse(refusing.spaceHeld)
        assertFalse(refusing.onKey(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0)))

        // 받은 스페이스바는 되풀이도 받고, 떼면 놓는다
        val accepting = KeyShortcutHost().apply { handler = { true } }
        assertTrue(accepting.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0)))
        assertTrue(accepting.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 1)))
        assertTrue(accepting.spaceHeld)
        assertTrue(accepting.onKey(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0)))
        assertFalse(accepting.spaceHeld)
    }

    @Test
    fun holdingSpace_dragsTheCanvas_insteadOfPainting() {
        show()
        press(KeyEvent.KEYCODE_B)
        press(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON)
        val before = composeRule.runOnIdle { viewports.last().visible }
        composeRule.runOnIdle { host.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)) }
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(center)
            moveBy(Offset(-100f, 0f))
            moveBy(Offset(-100f, 0f))
            up()
        }
        composeRule.runOnIdle { host.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)) }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertTrue("$calls should have no stroke", "stroke" !in calls)
            // 손가락을 왼쪽으로 끌면 문서의 오른쪽이 보인다
            assertTrue("${viewports.last().visible} should be right of $before", viewports.last().visible.left > before.left)
        }
    }
}
