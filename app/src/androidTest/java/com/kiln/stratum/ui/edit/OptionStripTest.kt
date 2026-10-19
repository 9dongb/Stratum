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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.layout.touchBoundsInWindow
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
 * 메인 화면의 도구 옵션 띠와 옵션 창(결정 기록 '도구 옵션 띠'): 띠는 도구 막대 폭에 칸만큼의 높이로 고른 도구 옆에 뜸,
 * 도구마다 칸, 칸을 누르면 옵션 창, 열고 닫기, 떠 있는 동안 캔버스를 누르면 닫기만(그리지 않음), 뒤로 가기 순서, 접히는 선 피하기.
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
    private val recent = listOf(0xFF1E88E5.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    private val chosen = mutableListOf<Pair<ColorTarget, Int>>()
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
                            recentColors = recent,
                            onChooseColor = { target, color -> chosen += target to color },
                            eyedropperActions = object : EyedropperActions {
                                override fun begin(target: ColorTarget, x: Float, y: Float) {
                                    eyedropper += "begin"
                                }

                                override fun move(x: Float, y: Float) = Unit
                                override fun end() = Unit
                                override fun cancel() = Unit
                            }
                        )
                    }
                }
            }
        }
    }

    private fun pick(tool: EditTool) = composeRule.onNodeWithTag(EditScreenTags.railTool(tool)).performClick()

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
    fun withTheStrip_theHintToastAndTheMessageBarAreCenteredOnTheCanvas() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Text)
        composeRule.runOnIdle { messages.trySend(EditorMessage.HiddenLayer) }
        composeRule.waitForIdle()

        // 사용자 요청: 띠가 떠 있어도 살짝 오른쪽이 아니라 캔버스 정중앙
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode().layoutBoundsInWindow()
        val toast = composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).fetchSemanticsNode().layoutBoundsInWindow()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.edit_hidden_layer)).assertExists()
        val bar = composeRule.onNodeWithTag(EditScreenTags.MESSAGE_BAR).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(canvas.center.x, toast.center.x, 2f)
        assertEquals(canvas.center.x, bar.center.x, 2f)
        // 띠는 덮지 않는다
        val strip = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("toast $toast strip $strip", toast.left >= strip.right)
        assertTrue("bar $bar strip $strip", bar.left >= strip.right)
    }

    @Test
    fun brush_showsColorEyedropperAndValueCells_insteadOfThePanelOptions() {
        show(MAIN_PORTRAIT)
        pick(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertIsDisplayed()
        for (key in listOf("color", "size", "hardness", "opacity")) cell(key).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER).assertIsDisplayed()
        // 커버의 옵션(색 줄·슬라이더 목록)은 없다
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_OPTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertDoesNotExist()
    }

    @Test
    fun eraser_hasNoColorCells_andTheStripLeavesWithTheTool() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Eraser)
        cell("size").assertIsDisplayed()
        cell("color").assertDoesNotExist()
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
    fun colorCell_choosesARecentColor_andCloses() {
        show()
        pick(EditTool.Brush)
        cell("color").performClick()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_COLORS).assertIsDisplayed()

        composeRule.onNodeWithTag(EditScreenTags.recentColor(0)).performClick()

        composeRule.runOnIdle { assertEquals(listOf(ColorTarget.BRUSH to recent[0]), chosen) }
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
    }

    @Test
    fun colorCell_currentColorOpensThePalette_andClosesTheFlyout() {
        show()
        pick(EditTool.Brush)
        cell("color").performClick()

        composeRule.onNodeWithTag(EditScreenTags.COLOR_CURRENT).performClick()

        composeRule.onNodeWithTag(EditScreenTags.PALETTE).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
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
    fun text_hasColorAndSize_andItsHintShowsOnce() {
        show()
        composeRule.mainClock.autoAdvance = false
        pick(EditTool.Text)
        composeRule.mainClock.advanceTimeBy(300)
        cell("color").assertExists()
        cell("textSize").assertExists()
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertExists()

        composeRule.mainClock.advanceTimeBy(TOOL_HINT_MILLIS + 1_000)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
        // 내려놓았다가 다시 골라도 같은 안내는 다시 띄우지 않는다
        pick(EditTool.Text)
        pick(EditTool.Text)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
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

    @Test
    fun strip_isAsWideAsTheRail_hugsItsCells_andSitsBesideItsTool() {
        show(MAIN_LANDSCAPE)
        val rail = composeRule.onNodeWithTag(EditScreenTags.TOOL_RAIL).fetchSemanticsNode().layoutBoundsInWindow()
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode()
        val dp = with(canvas.layoutInfo.density) { 1.dp.toPx() }

        // 위아래 자리가 넉넉한 도구(명암): 띠 가운데가 도구 버튼 가운데다. 폭은 도구 막대와 같고, 높이는 칸만큼이다.
        pick(EditTool.Tone)
        val tone = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Tone)).fetchSemanticsNode().layoutBoundsInWindow()
        var strip = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(rail.width, strip.width, 2 * dp)
        // 칸만큼의 높이: 첫 칸(범위) 위와 마지막 칸(경도) 아래에 안쪽 여백만 남는다(빈 곳까지 차지하지 않음)
        val first = cell("range").fetchSemanticsNode().layoutBoundsInWindow()
        val last = cell("hardness").fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$strip should hug $first..$last", first.top - strip.top <= 8 * dp && strip.bottom - last.bottom <= 8 * dp)
        assertTrue("$strip should be shorter than ${canvas.layoutBoundsInWindow()}", strip.height < canvas.layoutBoundsInWindow().height)
        assertEquals(tone.center.y, strip.center.y, 2 * dp)
        assertTrue("$strip should float right of the rail $rail", strip.left > rail.right)

        // 위쪽 자리가 모자란 도구(자르기·변형, 맨 위): 띠가 도구 버튼(누르는 영역 48dp) 위 끝에서 시작한다
        pick(EditTool.Transform)
        val crop = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).fetchSemanticsNode().layoutBoundsInWindow()
        strip = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(crop.center.y - 24 * dp, strip.top, 2 * dp)
    }

    @Test
    fun mainPortrait_stripStaysOnItsToolsSideOfAHorizontalHinge() {
        show(MAIN_PORTRAIT)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        // 리터칭 버튼 바로 아래를 지나는 가로 힌지: 리터칭은 힌지 위에 남고, 바로 아래 명암은 도구 막대가 힌지 아래로 비켜 놓는다.
        // (도구 막대 자리는 화면마다 다르다. 접은 에뮬레이터에서는 카메라 구멍 때문에 상단 바가 높아진다)
        val retouch = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Retouch)).fetchSemanticsNode()
        val margin = with(retouch.layoutInfo.density) { HingeMargin.roundToPx() }
        val y = retouch.touchBoundsInWindow().bottom.roundToInt() + margin + 2
        val hinge = Hinge(left = 0, top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }
        composeRule.waitForIdle()

        pick(EditTool.Retouch)
        var node = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode()
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        assertTrue("${node.layoutBoundsInWindow()} should end above $band", node.layoutBoundsInWindow().bottom <= band.start + 1f)
        composeRule.assertClickablesAvoid(hinge)

        pick(EditTool.Tone)
        node = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode()
        assertTrue("${node.layoutBoundsInWindow()} should start below $band", node.layoutBoundsInWindow().top >= band.end - 1f)
        composeRule.assertClickablesAvoid(hinge)
        // 힌지 아래 띠의 옵션 창도 힌지 아래 조각에 뜬다
        cell("range").performClick()
        val flyout = composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).fetchSemanticsNode()
        assertTrue("${flyout.layoutBoundsInWindow()} should be below $band", flyout.layoutBoundsInWindow().top >= band.end - 1f)
    }

    @Test
    fun mainLandscape_flyoutStaysLeftOfAVerticalHinge() {
        show(MAIN_LANDSCAPE)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }
        pick(EditTool.Brush)

        cell("size").performClick()

        val node = composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).fetchSemanticsNode()
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        assertTrue("${node.layoutBoundsInWindow()} crosses $band", node.layoutBoundsInWindow().right <= band.start + 1f)
        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun lowScreen_stripScrollsToItsLastCell() {
        // 가로로 눕힌 큰 폰처럼 낮고 넓은 메인 배치
        show(DpSize(900.dp, 320.dp))
        pick(EditTool.Brush)

        cell("opacity").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun retouch_healWithoutASource_isPicking_andHasNoOpacity_cloneHasOpacity() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Retouch)
        chooseSubTool(EditTool.Retouch, SubTool.RETOUCH_HEAL)

        // 가져올 곳이 없으면 정하는 중(켜진 모양)이다
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE).assertIsOn()
        cell("opacity").assertDoesNotExist()
        cell("size").performClick()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.runOnIdle { assertEquals(BrushSettings.MIN_SIZE, retouch.size, 0.5f) }

        composeRule.runOnIdle { cloneSource = CloneSource(10f, 10f) }
        chooseSubTool(EditTool.Retouch, SubTool.RETOUCH_CLONE)
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE).assertIsOff()
        cell("opacity").assertIsDisplayed()
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
