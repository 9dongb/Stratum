package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 다시 고치는 글자 레이어(사용자 피드백 8차 1-6: "다시 그 텍스트를 누르면 편집가능하도록", 내용·크기·색 모두)의 화면:
 * 글자 도구로 글자 상자를 누르면 고치는 창, 그 밖을 누르면 새 글자 창, 창 안의 크기·색, 픽셀로 바꿀지 묻는 창.
 */
@RunWith(AndroidJUnit4::class)
class TextEditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    /** 문서 가운데를 덮는 'Hello' 글자 레이어 */
    private val spec = TextSpec("Hello", x = 400f, y = 450f, style = TextStyle(size = 80f, color = 0xFF2F6FE4.toInt()), width = 200f, height = 100f)

    private var textStyle by mutableStateOf(TextStyle(size = 60f, color = AndroidColor.BLACK))
    private var styleChanges = 0
    private val added = mutableListOf<Pair<String, TextStyle>>()
    private val edited = mutableListOf<Triple<LayerId, String, TextStyle>>()
    private val selected = mutableListOf<LayerId>()
    private var rasterizeAsk by mutableStateOf<LayerId?>(null)
    private var confirms = 0

    private val layerActions = object : LayerActions {
        override fun select(id: LayerId) {
            selected += id
        }

        override fun add() = Unit
        override fun delete() = Unit
        override fun duplicate() = Unit
        override fun mergeDown() = Unit
        override fun mergeVisible() = Unit
        override fun move(id: LayerId, toIndex: Int, parent: LayerId?) = Unit
        override fun previewOpacity(opacity: Float) = Unit
        override fun commitOpacity() = Unit
        override fun setBlendMode(mode: BlendMode) = Unit
        override fun setVisible(id: LayerId, visible: Boolean) = Unit
    }

    private fun show(textVisible: Boolean = true, textOpacity: Float = 1f) {
        val layers = listOf(
            LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE),
            LayerSummary(LayerId(1), "Hello", textOpacity, BlendMode.NORMAL, Adjustments.NONE, visible = textVisible, text = spec)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)), document, layers = layers, activeLayer = LayerId(0)),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        layerActions = layerActions,
                        textStyle = textStyle,
                        onTextStyle = {
                            textStyle = it
                            styleChanges++
                        },
                        onAddText = { text, _, _, style -> added += text to style },
                        onEditText = { layer, text, style -> edited += Triple(layer, text, style) },
                        rasterizeAsk = rasterizeAsk,
                        onConfirmRasterize = {
                            confirms++
                            rasterizeAsk = null
                        },
                        onDismissRasterize = { rasterizeAsk = null }
                    )
                }
            }
        }
    }

    private fun openTextTool() {
        // 탭이 커버 폭보다 많아 글자 탭은 옆으로 밀어야 보인다
        composeRule.chooseCoverTool(EditTool.Text)
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun tappingTheTextBox_opensItForEditing_andApplySendsTheNewText() {
        show()
        openTextTool()

        // 캔버스 가운데(문서 가운데)는 'Hello' 글자 상자 안이다
        composeRule.onNodeWithTag(EditScreenTags.TEXT_OVERLAY).performTouchInput { click(center) }

        composeRule.onNodeWithText(string(R.string.text_edit_title)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).assert(hasText("Hello"))
        composeRule.runOnIdle { assertEquals(listOf(LayerId(1)), selected) }
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).performTextReplacement("Bye")
        composeRule.onNodeWithText(string(R.string.action_apply)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TEXT_ADD).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(Triple(LayerId(1), "Bye", spec.style)), edited)
            assertTrue(added.isEmpty())
        }
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).assertDoesNotExist()
    }

    @Test
    fun hiddenTextLayer_isNotEdited_soTheTapAddsNewText() {
        show(textVisible = false)
        openTextTool()

        composeRule.onNodeWithTag(EditScreenTags.TEXT_OVERLAY).performTouchInput { click(center) }

        composeRule.onNodeWithText(string(R.string.text_prompt_title)).assertExists()
    }

    @Test
    fun textLayerAtZeroOpacity_isNotEdited_soTheTapAddsNewText() {
        // 불투명도 0%는 보이지 않는 레이어다(눈을 끈 것과 같다)
        show(textOpacity = 0f)
        openTextTool()

        composeRule.onNodeWithTag(EditScreenTags.TEXT_OVERLAY).performTouchInput { click(center) }

        composeRule.onNodeWithText(string(R.string.text_prompt_title)).assertExists()
    }

    /** 새 글자 창을 열고 'Hi'를 적은 뒤 색 칸을 눌러 같은 시트를 팔레트로 바꾼다(커버) */
    private fun typeHiThenOpenThePalette() {
        show()
        openTextTool()
        composeRule.onNodeWithTag(EditScreenTags.TEXT_OVERLAY).performTouchInput { click(Offset(width * 0.1f, height * 0.1f)) }
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).performTextInput("Hi")
        composeRule.onNodeWithTag(EditScreenTags.TEXT_PROMPT_COLOR).performClick()
        composeRule.onNodeWithText(string(R.string.text_color_title)).assertExists()
    }

    /** 시트가 올라와 글자 쪽이 'Hi' 그대로 보이고, 넣으면 그 글자가 간다 */
    private fun assertBackOnTheTextWithHi() {
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).assertIsDisplayed().assert(hasText("Hi"))
        composeRule.onNodeWithTag(EditScreenTags.TEXT_ADD).performClick()
        composeRule.runOnIdle { assertEquals("Hi", added.single().first) }
    }

    @Test
    fun cover_backOnThePalette_goesBackToTheText_withTheSheetStillUp() {
        typeHiThenOpenThePalette()

        Espresso.pressBack()

        assertBackOnTheTextWithHi()
    }

    @Test
    fun cover_tappingOutsideThePalette_goesBackToTheText_withTheSheetUpAgain() {
        // 창 밖을 누르면 M3 시트가 먼저 내려가지만, 팔레트 쪽이면 글자 쪽으로 바꿔 다시 올린다(보이지 않는 시트가 터치를 막지 않게)
        typeHiThenOpenThePalette()

        val aboveTheSheet = CoordinatesProvider { view ->
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            floatArrayOf(location[0] + view.width / 2f, location[1] + view.height * 0.05f)
        }
        Espresso.onView(isRoot()).inRoot(isDialog()).perform(GeneralClickAction(Tap.SINGLE, aboveTheSheet, Press.FINGER, InputDevice.SOURCE_UNKNOWN, MotionEvent.BUTTON_PRIMARY))

        assertBackOnTheTextWithHi()
    }

    @Test
    fun newText_startsWithTheOptions_andTheDialogSizeAndColorDoNotChangeTheOptions() {
        show()
        openTextTool()
        composeRule.onNodeWithTag(EditScreenTags.TEXT_OVERLAY).performTouchInput { click(Offset(width * 0.1f, height * 0.1f)) }
        composeRule.onNodeWithText(string(R.string.text_prompt_title)).assertExists()

        // 창의 크기 슬라이더로 바꾸고, 색 칸을 누르면 같은 창이 팔레트로 바뀐다
        val fraction = textSizeToFraction(200f)
        composeRule.onNodeWithTag(EditScreenTags.TEXT_PROMPT_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
        composeRule.onNodeWithTag(EditScreenTags.TEXT_PROMPT_COLOR).performClick()
        composeRule.onNodeWithText(string(R.string.text_color_title)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).assertDoesNotExist()
        // 네모의 오른쪽 위(채도·밝기 가장 높음)를 누르면 검정에서 진한 색이 된다
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_SQUARE).performTouchInput { click(Offset(width - 2f, 2f)) }
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_OK).performClick()
        // 확인하면 글자 쪽으로 돌아온다
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).performTextInput("Hi")
        composeRule.onNodeWithTag(EditScreenTags.TEXT_ADD).performClick()

        composeRule.runOnIdle {
            val (text, style) = added.single()
            assertEquals("Hi", text)
            assertEquals(fractionToTextSize(fraction), style.size, 0.5f)
            assertNotEquals(AndroidColor.BLACK, style.color)
            // 창에서 고른 크기·색은 글자 옵션(전경색)을 바꾸지 않는다(사용자 피드백 8차 1-6)
            assertEquals(0, styleChanges)
            assertEquals(TextStyle(size = 60f, color = AndroidColor.BLACK), textStyle)
        }
    }

    @Test
    fun paletteCancel_goesBackToTheTextWithTheSameColor() {
        show()
        openTextTool()
        composeRule.onNodeWithTag(EditScreenTags.TEXT_OVERLAY).performTouchInput { click(Offset(width * 0.1f, height * 0.1f)) }
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).performTextInput("Hi")

        composeRule.onNodeWithTag(EditScreenTags.TEXT_PROMPT_COLOR).performClick()
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_SQUARE).performTouchInput { click(Offset(width - 2f, 2f)) }
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_CANCEL).performClick()

        // 적던 글자가 그대로 남아 있다
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).assert(hasText("Hi"))
        composeRule.onNodeWithTag(EditScreenTags.TEXT_ADD).performClick()
        composeRule.runOnIdle { assertEquals(AndroidColor.BLACK, added.single().second.color) }
    }

    @Test
    fun rasterizeAsk_showsTheQuestion_andConvertSendsIt() {
        show()
        composeRule.onNodeWithText(string(R.string.rasterize_title)).assertDoesNotExist()

        composeRule.runOnIdle { rasterizeAsk = LayerId(1) }

        composeRule.onNodeWithText(string(R.string.rasterize_title)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.RASTERIZE_CONFIRM).performClick()
        composeRule.runOnIdle { assertEquals(1, confirms) }
        composeRule.onNodeWithText(string(R.string.rasterize_title)).assertDoesNotExist()
    }
}
