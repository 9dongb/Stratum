package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.engine.tone.ToneRange
import com.kiln.stratum.settings.ColorSlot
import com.kiln.stratum.settings.PaintColors
import com.kiln.stratum.settings.StripPosition
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 메인 화면의 도구 옵션 띠와 옵션 창(결정 기록 '도구 옵션 띠'): 띠는 도구 막대 폭에 칸만큼의 높이로 캔버스 왼쪽 위(옮겨 놓은 자리)에 뜸,
 * 도구마다 칸, 칸을 누르면 옵션 창, 열고 닫기, 떠 있는 동안 캔버스를 누르면 닫기만(그리지 않음), 뒤로 가기 순서,
 * 접히는 선은 보지 않음(사용자 피드백 11차 1: 선이 지나도 자리가 그대로).
 */
@RunWith(AndroidJUnit4::class)
class OptionStripTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val size = mutableStateOf(MAIN_PORTRAIT)

    /** 받은 획 일 */
    private val strokes = mutableListOf<String>()
    private val strokeActions = object : StrokeActions {
        override fun begin(x: Float, y: Float, erase: Boolean) {
            strokes += "begin"
        }

        override fun extend(x: Float, y: Float) {
            if (strokes.lastOrNull() != "extend") strokes += "extend"
        }

        override fun end() {
            strokes += "end"
        }

        override fun cancel() {
            strokes += "cancel"
        }
    }

    private var brush by mutableStateOf(BrushSettings(size = 40f, color = 0xFF000000.toInt()))
    private var eraser by mutableStateOf(BrushSettings(size = 40f))
    private var textStyle by mutableStateOf(TextStyle(size = 60f))
    private var retouch by mutableStateOf(BrushSettings(size = 60f, hardness = 0.5f))
    private var cloneSource by mutableStateOf<CloneSource?>(null)
    private var toneOptions by mutableStateOf(ToneOptions())
    private var paintColors by mutableStateOf(PaintColors.DEFAULT)
    private var stripPosition by mutableStateOf<StripPosition?>(null)
    private val chosen = mutableListOf<Pair<ColorSlot, Int>>()
    private var homeBacks = 0
    private var undos = 0
    private val eyedropper = mutableListOf<String>()

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    /** 편집 화면에 보내는 알림(알림 막대) */
    private val messages = Channel<EditorMessage>(Channel.UNLIMITED)

    private fun show(screen: DpSize = MAIN_PORTRAIT) {
        size.value = screen
        composeRule.setContent {
            // StratumApp처럼 편집 화면보다 먼저 '뒤로 → 홈'을 둔다
            BackHandler { homeBacks++ }
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        EditScreen(
                            state = EditorState.Ready(
                                PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                                document,
                                layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
                                activeLayer = LayerId(0),
                                canUndo = true
                            ),
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {},
                            onUndo = { undos++ },
                            messages = messages.receiveAsFlow(),
                            brush = brush,
                            onBrush = { brush = it },
                            eraser = eraser,
                            onEraser = { eraser = it },
                            strokeActions = strokeActions,
                            textStyle = textStyle,
                            onTextStyle = { textStyle = it },
                            twoFingerTapUndo = true,
                            retouch = retouch,
                            onRetouch = { retouch = it },
                            cloneSource = cloneSource,
                            toneOptions = toneOptions,
                            onToneOptions = { toneOptions = it },
                            paintColors = paintColors,
                            onChooseColor = { slot, color -> chosen += slot to color },
                            onSwapColors = { paintColors = paintColors.swapped() },
                            eyedropperActions = object : EyedropperActions {
                                override fun begin(target: ColorTarget, x: Float, y: Float) {
                                    eyedropper += "begin"
                                }

                                override fun move(x: Float, y: Float) = Unit
                                override fun end() = Unit
                                override fun cancel() = Unit
                            },
                            stripPosition = stripPosition,
                            onStripPosition = { stripPosition = it }
                        )
                    }
                }
            }
        }
    }

    /** 도구 막대에서 [tool]을 누른다. 낮은 화면에서는 도구 막대를 밀어 그 버튼이 보이게 한 뒤 누른다(도구가 11개라 넘칠 수 있다). */
    private fun pick(tool: EditTool) = composeRule.onNodeWithTag(EditScreenTags.railTool(tool)).performScrollTo().performClick()

    private fun cell(key: String) = composeRule.onNodeWithTag(EditScreenTags.strip(key))

    /** 고른 [tool]을 다시 눌러 연 하위 도구 메뉴에서 [subTool]을 고른다(띠에는 하위 도구 칸이 없다, 사용자 피드백 7차) */
    private fun chooseSubTool(tool: EditTool, subTool: SubTool) {
        pick(tool)
        composeRule.onNodeWithTag(EditScreenTags.subTool(subTool)).performClick()
    }

    private fun back() {
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun withTheStrip_theToastAndTheMessageBarAreCenteredOnTheScreen() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Text)
        // 두 손가락 톡(실행 취소)으로 알림을 띄운다(도구를 고를 때 뜨던 안내 알림은 없앴다, 사용자 피드백 8차 1-7)
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            up(1)
            up(0)
        }
        composeRule.runOnIdle { messages.trySend(EditorMessage.HiddenLayer) }
        composeRule.waitForIdle()

        // 사용자 요청: 알림은 도구 막대·띠와 상관없이 화면 정중앙("도구바나 띠 생각하지 말고 화면 정중앙에 띄우세요")
        val screen = composeRule.onNodeWithTag(EditScreenTags.TOP_BAR).fetchSemanticsNode().layoutBoundsInWindow()
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode().layoutBoundsInWindow()
        val toast = composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(screen.center.x, toast.center.x, 2f)
        // 세로는 캔버스 위쪽(배율 표시 아래)이다
        assertTrue("toast $toast canvas $canvas", toast.top > canvas.top && toast.bottom < canvas.center.y)
        // 알림 막대('보기' 버튼)도 화면 가로 정중앙이다(사용자 피드백 9차: 띠가 왼쪽 위로 가서 가릴 일이 거의 없음)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.edit_hidden_layer)).assertExists()
        val bar = composeRule.onNodeWithTag(EditScreenTags.MESSAGE_BAR).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(screen.center.x, bar.center.x, 2f)
    }

    @Test
    fun brush_showsColorEyedropperAndValueCells_insteadOfThePanelOptions() {
        show(MAIN_PORTRAIT)
        pick(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertIsDisplayed()
        // 전경색·배경색·바꾸기·스포이드, 크기·경도·불투명도
        for (key in listOf("foreground", "background", "size", "hardness", "opacity")) cell(key).assertIsDisplayed()
        composeRule.onNode(hasTestTag(EditScreenTags.COLOR_SWAP) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER).assertIsDisplayed()
        // 커버의 한 줄 막대는 없다
        composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertDoesNotExist()
    }

    @Test
    fun eraser_hasNoColorCells_andTheStripLeavesWithTheTool() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Eraser)
        cell("size").assertIsDisplayed()
        cell("foreground").assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER).assertDoesNotExist()

        pick(EditTool.Eraser)
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
    }

    @Test
    fun valueCell_opensItsSlider_andTheSliderChangesTheSetting() {
        show()
        pick(EditTool.Brush)
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()

        cell("size").performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }

        composeRule.runOnIdle { assertEquals(BrushSettings.MAX_SIZE, brush.size, 0.5f) }
    }

    @Test
    fun sliderCell_opensAThinVerticalSlider_withItsValue_andDraggingUpRaisesIt() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        cell("hardness").performClick()

        val slider = composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).fetchSemanticsNode()
        val bounds = slider.layoutBoundsInWindow()
        val cellBounds = cell("hardness").fetchSemanticsNode().layoutBoundsInWindow()
        val dp = with(slider.layoutInfo.density) { 1.dp.toPx() }
        // 칸 오른쪽의 얇은 세로 슬라이더(폭 48dp)와 그 옆 값 표시
        assertEquals(48 * dp, bounds.width, 2 * dp)
        assertTrue("$bounds should be tall", bounds.height > bounds.width * 3)
        assertTrue("$bounds should be right of $cellBounds", bounds.left > cellBounds.right)
        composeRule.onNodeWithTag(EditScreenTags.STRIP_SLIDER_VALUE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, composeRule.activity.getString(com.kiln.stratum.R.string.layer_percent, (brush.hardness * 100).roundToInt())))

        // 아래 끝에서 위 끝으로 끌면 가장 커지고, 위에서 아래로 끌면 가장 작아진다
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput {
            down(bottomCenter - Offset(0f, 2f))
            moveTo(topCenter + Offset(0f, 2f))
            up()
        }
        composeRule.runOnIdle { assertEquals(1f, brush.hardness, 0.01f) }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput {
            down(topCenter + Offset(0f, 2f))
            moveTo(bottomCenter - Offset(0f, 2f))
            up()
        }
        composeRule.runOnIdle { assertEquals(0f, brush.hardness, 0.01f) }
    }

    @Test
    fun sliderCell_draggingTheNumber_changesTheValue_withoutOpeningTheSlider() {
        // 사용자 피드백 9차: "수치를 터치하고 위나 아래로 슬라이딩하면 해당 옵션값(100~0)이 조절되도록 해주세요."
        brush = brush.copy(hardness = 0.5f)
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        val dp = with(cell("hardness").fetchSemanticsNode().layoutInfo.density) { 1.dp.toPx() }
        val slop = android.view.ViewConfiguration.get(composeRule.activity).scaledTouchSlop.toFloat()

        // 위로 50dp 끈다: 터치 허용 거리를 넘은 만큼, 200dp에 처음부터 끝까지의 비율로 커진다
        cell("hardness").performTouchInput {
            down(center)
            moveBy(Offset(0f, -30 * dp))
            moveBy(Offset(0f, -20 * dp))
            up()
        }
        composeRule.runOnIdle { assertEquals(0.5f + (50 * dp - slop) / (200 * dp), brush.hardness, 0.02f) }
        // 끈 터치로는 옵션 창이 뜨지 않는다(톡 누르면 뜬다)
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()

        // 아래로 끌면 작아지고 0에서 멈춘다
        cell("hardness").performTouchInput {
            down(center)
            moveBy(Offset(0f, 150 * dp))
            moveBy(Offset(0f, 150 * dp))
            up()
        }
        composeRule.runOnIdle { assertEquals(0f, brush.hardness, 0.001f) }

        // 크기도 같다(로그 눈금이라 슬라이더 눈금으로 커진다)
        val size = brush.size
        cell("size").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40 * dp))
            moveBy(Offset(0f, -40 * dp))
            up()
        }
        composeRule.runOnIdle { assertTrue("size $size -> ${brush.size}", brush.size > size) }
    }

    @Test
    fun sameCellCloses_andAnotherCellSwitches() {
        show()
        pick(EditTool.Brush)

        cell("size").performClick()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertExists()
        cell("hardness").performClick()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).assertExists()
        cell("hardness").performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
    }

    @Test
    fun canvasTouch_closesTheFlyout_withoutDrawing_thenTheNextDragDraws() {
        show()
        pick(EditTool.Brush)
        cell("opacity").performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertExists()

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue("$strokes", strokes.isEmpty()) }

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        composeRule.runOnIdle { assertEquals(listOf("begin", "extend", "end"), strokes) }
    }

    @Test
    fun twoFingerTap_stillUndoesWhileTheFlyoutIsOpen() {
        show()
        pick(EditTool.Brush)
        cell("size").performClick()

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            up(1)
            up(0)
        }

        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, undos)
            assertTrue("$strokes", strokes.isEmpty())
        }
    }

    @Test
    fun back_closesTheFlyout_thenTheTool_thenGoesHome() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        cell("size").performClick()

        back()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }

        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun colorCells_openThatColorsPalette_rightAway() {
        show()
        pick(EditTool.Brush)
        // 사용자 요청: 도구를 고르면 전경색·배경색이 바로 보이고, 색을 누르면 옵션 창 없이 바로 색 고르기 창이 뜬다
        cell("size").performClick()
        cell("foreground").performClick()
        composeRule.onNodeWithTag(EditScreenTags.PALETTE).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.color_choose_foreground)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_OK).performClick()

        cell("background").performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.color_choose_background)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.PALETTE_OK).performClick()

        composeRule.runOnIdle { assertEquals(listOf(ColorSlot.FOREGROUND to PaintColors.BLACK, ColorSlot.BACKGROUND to PaintColors.WHITE), chosen) }
    }

    @Test
    fun swapCell_tradesTheTwoColors() {
        show()
        pick(EditTool.Brush)
        cell("foreground").assertContentDescriptionContains("#000000", substring = true)

        composeRule.onNode(hasTestTag(EditScreenTags.COLOR_SWAP) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).performClick()

        cell("foreground").assertContentDescriptionContains("#FFFFFF", substring = true)
        cell("background").assertContentDescriptionContains("#000000", substring = true)
    }

    @Test
    fun eyedropperCell_turnsOnTheEyedropper() {
        show()
        pick(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_OVERLAY).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(center)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("begin"), eyedropper)
            assertTrue("$strokes", strokes.isEmpty())
        }
    }

    @Test
    fun text_hasSizeAndColors_andNoHintToast() {
        show()
        composeRule.mainClock.autoAdvance = false
        pick(EditTool.Text)
        composeRule.mainClock.advanceTimeBy(300)
        // 글자도 브러시와 같은 두 색(전경색·배경색) 칸이 있다
        cell("foreground").assertExists()
        cell("background").assertExists()
        cell("textSize").assertExists()
        // 글자는 안내 알림이 없다(리터칭·명암만 처음 고를 때 알림, 사용자 피드백 10차 5)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
        cell("help").assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun textSize_changesFromTheFlyout() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Text)
        cell("textSize").performClick()

        composeRule.onNodeWithTag(EditScreenTags.TEXT_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

        composeRule.runOnIdle { assertEquals(TextStyle.MIN_SIZE, textStyle.size, 0.5f) }
    }

    /** 띠 손잡이를 ([dx], [dy]) 픽셀만큼 끌어 놓는다 */
    private fun dragHandle(dx: Float, dy: Float) {
        composeRule.onNodeWithTag(EditScreenTags.STRIP_HANDLE).performTouchInput {
            down(center)
            moveBy(Offset(dx / 2, dy / 2))
            moveBy(Offset(dx / 2, dy / 2))
            up()
        }
        composeRule.waitForIdle()
    }

    private fun stripBounds() = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode().layoutBoundsInWindow()

    @Test
    fun strip_isAsWideAsTheRail_hugsItsCells_andSitsAtTheCanvasTopLeft() {
        show(MAIN_LANDSCAPE)
        val rail = composeRule.onNodeWithTag(EditScreenTags.TOOL_RAIL).fetchSemanticsNode().layoutBoundsInWindow()
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode()
        val dp = with(canvas.layoutInfo.density) { 1.dp.toPx() }
        val canvasBounds = canvas.layoutBoundsInWindow()

        // 사용자 피드백 9차: 도구를 고르면 옵션은 캔버스 왼쪽 위에 뜬다(고른 도구의 높이와 상관없이)
        pick(EditTool.Tone)
        var strip = stripBounds()
        assertEquals(rail.width, strip.width, 2 * dp)
        assertEquals(canvasBounds.left + 4 * dp, strip.left, 2 * dp)
        assertEquals(canvasBounds.top + 8 * dp, strip.top, 2 * dp)
        // 칸만큼의 높이: 맨 위 손잡이(48dp) 아래 첫 칸, 마지막 칸 아래에는 안쪽 여백만 남는다(빈 곳까지 차지하지 않음)
        val handle = composeRule.onNodeWithTag(EditScreenTags.STRIP_HANDLE).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(strip.top, handle.top, 2 * dp)
        assertEquals(48 * dp, handle.height, 2 * dp)
        // 명암의 마지막 칸은 노출이다(수치 칸이 아래, 사용자 피드백 10차 9)
        val last = cell("exposure").fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$strip should hug down to $last", strip.bottom - last.bottom <= 8 * dp)
        assertTrue("$strip should be shorter than $canvasBounds", strip.height < canvasBounds.height)

        // 다른 도구(맨 아래 쪽 모자이크)도 같은 자리다
        pick(EditTool.Mosaic)
        strip = stripBounds()
        assertEquals(canvasBounds.left + 4 * dp, strip.left, 2 * dp)
        assertEquals(canvasBounds.top + 8 * dp, strip.top, 2 * dp)
    }

    @Test
    fun otherCellsComeFirst_thenTheNumbers_sizeHardnessOpacityStrength() {
        // 사용자 피드백 10차 9: "수치 조절 외의 기능이 있을 시 ... 수치가 아래로 오도록 우선순위 변경해주세요.(공통 규칙)"
        // 수치 칸 순서는 9차: "1.크기 2.경도 3. 투명도.4 강도 순서로 배치해주세요(없을 땐 제외)"
        show(MAIN_LANDSCAPE)
        fun tops(vararg keys: String) = keys.map { cell(it).fetchSemanticsNode().layoutBoundsInWindow().top }
        fun assertDownward(values: List<Float>) = assertTrue("$values should go down", values.zipWithNext().all { (a, b) -> a < b })

        pick(EditTool.Brush)
        val brushColors = composeRule.onNodeWithTag(EditScreenTags.strip("foreground")).fetchSemanticsNode().layoutBoundsInWindow().top
        assertDownward(listOf(brushColors) + tops("size", "hardness", "opacity"))

        pick(EditTool.Tone)
        assertDownward(tops("range", "size", "hardness", "exposure"))
    }

    @Test
    fun handle_dragsTheStrip_andTheSpotIsKeptForOtherTools() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        val before = stripBounds()
        val canvasNode = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode()
        val dp = with(canvasNode.layoutInfo.density) { 1.dp.toPx() }
        val canvas = canvasNode.layoutBoundsInWindow()
        // 아래로는 캔버스 아래 여백(8dp) 위까지만 옮길 수 있다. 큰 화면을 접은 화면에 줄여 넣으면(접은 상태로 돌린 테스트)
        // 시스템 막대가 상대적으로 커서 캔버스가 낮으므로, 남은 자리만큼만 끈다
        val dy = minOf(120f, canvas.bottom - 8 * dp - before.bottom - 1f).coerceAtLeast(0f)

        dragHandle(300f, dy)

        val after = stripBounds()
        assertEquals(before.left + 300f, after.left, 3f)
        assertEquals(before.top + dy, after.top, 3f)
        // 놓은 자리를 설정에 알린다(캔버스 왼쪽 위에서의 dp)
        composeRule.runOnIdle {
            val saved = checkNotNull(stripPosition)
            assertEquals(after.left - canvas.left, saved.x * dp, 2f)
            assertEquals(after.top - canvas.top, saved.y * dp, 2f)
        }
        // 도구를 바꿔도 그 자리다
        pick(EditTool.Retouch)
        assertEquals(after.left, stripBounds().left, 2f)
        assertEquals(after.top, stripBounds().top, 2f)
    }

    @Test
    fun handle_cannotDragTheStripOutOfTheCanvas() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Eraser)
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode().layoutBoundsInWindow()

        dragHandle(5_000f, 5_000f)
        val strip = stripBounds()
        assertTrue("$strip should stay inside $canvas", strip.right <= canvas.right + 1f && strip.bottom <= canvas.bottom + 1f)

        // 밖으로 끈 만큼은 쌓이지 않아 되돌아올 때 바로 따라온다
        dragHandle(-200f, 0f)
        assertEquals(strip.left - 200f, stripBounds().left, 3f)
    }

    @Test
    fun mainPortrait_aHorizontalHinge_doesNotMoveOrShortenTheStripAndItsFlyout() {
        // 사용자 피드백 11차 1: 접히는 선을 비키지 않는다(전에는 띠를 선 위 조각 높이로 줄이고, 옵션 창도 그 조각 안에 두었다)
        show(MAIN_PORTRAIT)
        pick(EditTool.Retouch)
        cell("size").performClick()
        val strip = stripBounds()
        val flyout = composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).fetchSemanticsNode().layoutBoundsInWindow()
        // 띠 한가운데를 지나는 가로 힌지
        val y = strip.center.y.roundToInt()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = 0, top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)) }
        composeRule.waitForIdle()

        assertEquals(strip, stripBounds())
        assertEquals(flyout, composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).fetchSemanticsNode().layoutBoundsInWindow())
    }

    @Test
    fun mainLandscape_theStripCanSitOnAVerticalHinge_andItsFlyoutOpensBesideIt() {
        // 사용자 피드백 11차 1: 띠를 접히는 선 위에 놓으면 그 자리에 남는다(전에는 선 한쪽으로 밀었다)
        show(MAIN_LANDSCAPE)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)) }
        pick(EditTool.Brush)
        val strip = stripBounds()

        dragHandle(centerX - strip.center.x, 0f)

        val moved = stripBounds()
        assertEquals(centerX.toFloat(), moved.center.x, 4f)
        // 옵션 창은 칸 바로 오른쪽에 뜬다(선을 건너 왼쪽으로 가지 않음)
        cell("size").performClick()
        val flyout = composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$flyout should be right of $moved", flyout.left >= moved.right - 1f)
    }

    @Test
    fun flyout_opensOnTheLeft_whenTheStripIsAtTheRightEdge() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        dragHandle(5_000f, 0f)
        val strip = stripBounds()

        cell("hardness").performClick()

        val flyout = composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$flyout should be left of $strip", flyout.right <= strip.left + 1f)
        // 값 표시는 슬라이더 카드의 칸 반대쪽(왼쪽)이다
        val value = composeRule.onNodeWithTag(EditScreenTags.STRIP_SLIDER_VALUE).fetchSemanticsNode().layoutBoundsInWindow()
        val slider = composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$value should be left of $slider", value.right <= slider.left + 1f)
    }

    @Test
    fun retouch_healHasNoSourceCellOrOpacity_cloneHasOpacity() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Retouch)
        chooseSubTool(EditTool.Retouch, SubTool.RETOUCH_HEAL)

        // '가져올 곳 정하기' 칸은 없다(사용자 피드백 10차 4: 표시가 늘 보이고 끌어 옮긴다)
        cell("source").assertDoesNotExist()
        cell("opacity").assertDoesNotExist()
        cell("size").performClick()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.runOnIdle { assertEquals(BrushSettings.MIN_SIZE, retouch.size, 0.5f) }

        composeRule.runOnIdle { cloneSource = CloneSource(10f, 10f) }
        chooseSubTool(EditTool.Retouch, SubTool.RETOUCH_CLONE)
        cell("source").assertDoesNotExist()
        cell("opacity").assertIsDisplayed()
    }

    @Test
    fun retouch_aiRemove_hasOnlyTheSize_andTellsHowToUseIt() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Retouch)
        chooseSubTool(EditTool.Retouch, SubTool.RETOUCH_REMOVE)

        // 하위 도구 메뉴(4번째)에서 고르고, 띠에는 크기와 [지우기] 칸(톡 눌러 고른 물체가 있을 때만 켜짐)만 있다
        cell("size").assertIsDisplayed()
        for (key in listOf("hardness", "opacity", "source")) cell(key).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).assertIsDisplayed().assertIsNotEnabled()
        // 물음표 칸은 없고(사용자 피드백 10차 5), 처음 고르면 안내 글이 알림으로 뜬다
        cell("help").assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.retouch_hint_remove))
    }

    @Test
    fun tone_rangeExposureAndSubTool_changeFromTheStrip() {
        show(MAIN_PORTRAIT)
        pick(EditTool.Tone)

        cell("range").performClick()
        composeRule.onNodeWithTag(EditScreenTags.toneRange(ToneRange.HIGHLIGHTS)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        cell("exposure").performClick()
        composeRule.onNodeWithTag(EditScreenTags.TONE_EXPOSURE).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.runOnIdle {
            assertEquals(ToneRange.HIGHLIGHTS, toneOptions.range)
            // 0%면 아무것도 바뀌지 않아 1%에서 멈춘다
            assertEquals(0.01f, toneOptions.exposure, 0.001f)
        }
        chooseSubTool(EditTool.Tone, SubTool.TONE_BURN)
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertDoesNotExist()
        // 메뉴를 다시 열면 번이 지금 하위 도구다
        pick(EditTool.Tone)
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.TONE_BURN)).assertIsSelected()
    }

    private companion object {
        val MAIN_PORTRAIT = DpSize(700.dp, 900.dp)
        val MAIN_LANDSCAPE = DpSize(930.dp, 700.dp)
    }
}
