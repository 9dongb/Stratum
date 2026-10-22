package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 마우스(사용자 피드백 12차 4): 휠은 캔버스를 옮기고 Ctrl+휠은 마우스 자리를 기준으로 확대한다. 가운데 버튼으로 끌면 옮기고,
 * 오른쪽 버튼은 칠하지 않는다. 올려 두면 붓 원이 보이고, 레이어 줄은 오른쪽 클릭으로 메뉴를 연다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MouseInputTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var brush by mutableStateOf(BrushSettings(size = 60f, hardness = 0.5f, color = 0xFF000000.toInt()))
    private val strokes = mutableListOf<String>()
    private val viewports = mutableListOf<Viewport>()

    private val strokeActions = object : StrokeActions {
        override fun begin(x: Float, y: Float, erase: Boolean) {
            strokes += "begin"
        }

        override fun extend(x: Float, y: Float) = Unit
        override fun end() = Unit
        override fun cancel() = Unit
    }

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(930.dp, 700.dp))) {
                StratumTheme {
                    EditScreen(
                        state = EditorState.Ready(
                            PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                            document,
                            layers = listOf(
                                LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE),
                                LayerSummary(LayerId(1), "Layer 1", 1f, BlendMode.NORMAL, Adjustments.NONE)
                            ),
                            activeLayer = LayerId(1)
                        ),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = { viewports += it },
                        onChooseSize = {},
                        brush = brush,
                        onBrush = { brush = it },
                        strokeActions = strokeActions
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun pickBrush() {
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.waitForIdle()
    }

    private fun lastViewport() = composeRule.runOnIdle { viewports.last() }

    @Test
    fun wheel_movesTheCanvas_andCtrlWheel_zoomsAtThePointer() {
        show()
        pickBrush()
        val fitted = lastViewport()
        // Ctrl+휠을 위로 굴리면 마우스 자리를 기준으로 확대한다
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performKeyInput { keyDown(Key.CtrlLeft) }
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performMouseInput {
            moveTo(center)
            scroll(-3f)
        }
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performKeyInput { keyUp(Key.CtrlLeft) }
        val zoomed = lastViewport()
        assertTrue("${zoomed.scale} should be above ${fitted.scale}", zoomed.scale > fitted.scale * 1.3)

        // 휠을 아래로 굴리면 문서의 아래쪽이 보인다(캔버스가 위로 간다)
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performMouseInput {
            moveTo(center)
            scroll(2f)
        }
        val scrolled = lastViewport()
        assertTrue("${scrolled.visible} should be below ${zoomed.visible}", scrolled.visible.top > zoomed.visible.top)
        assertEquals(zoomed.scale, scrolled.scale, 0.0001)
        composeRule.runOnIdle { assertTrue("$strokes should be empty", strokes.isEmpty()) }
    }

    @Test
    fun middleButtonDrag_movesTheCanvas_andRightButton_doesNotPaint() {
        show()
        pickBrush()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performMouseInput {
            moveTo(center)
            scroll(-3f)
        }
        val before = lastViewport()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performMouseInput {
            moveTo(center)
            press(MouseButton.Tertiary)
            moveBy(Offset(-120f, 0f))
            moveBy(Offset(-120f, 0f))
            release(MouseButton.Tertiary)
            press(MouseButton.Secondary)
            moveBy(Offset(60f, 60f))
            release(MouseButton.Secondary)
        }
        val after = lastViewport()
        assertTrue("${after.visible} should be right of ${before.visible}", after.visible.left > before.visible.left)
        composeRule.runOnIdle { assertTrue("$strokes should be empty", strokes.isEmpty()) }
        // 왼쪽 버튼은 그대로 칠한다
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performMouseInput {
            moveTo(center)
            press(MouseButton.Primary)
            moveBy(Offset(30f, 0f))
            release(MouseButton.Primary)
        }
        composeRule.runOnIdle { assertEquals(listOf("begin"), strokes) }
    }

    @Test
    fun hoveringTheMouse_showsTheBrushCircle_beforePainting() {
        show()
        pickBrush()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performMouseInput {
            enter(center)
            moveTo(center + Offset(20f, 20f))
        }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performMouseInput { exit() }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertDoesNotExist()
    }

    @Test
    fun rightClickingALayerRow_opensItsMenu() {
        show()
        composeRule.togglePanel(MainPanel.LAYERS)
        composeRule.onNodeWithTag(EditScreenTags.layerRow(LayerId(1))).performMouseInput {
            moveTo(Offset(width * 0.4f, height / 2f))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertIsDisplayed()
    }
}
