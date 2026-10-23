package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.settings.ColorSlot
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 브러시·지우개·글자 도구의 옵션과 캔버스 조작을 세 배치에서 확인한다(엔진 대신 받은 일만 적는 가짜). */
@RunWith(AndroidJUnit4::class)
class BrushToolsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()


    /** 받은 획 일 */
    private class FakeStrokes : StrokeActions {
        val calls = mutableListOf<String>()
        val points = mutableListOf<Pair<Float, Float>>()

        override fun begin(x: Float, y: Float, erase: Boolean) {
            calls += if (erase) "erase" else "begin"
            points += x to y
        }

        override fun extend(x: Float, y: Float) {
            if (calls.lastOrNull() != "extend") calls += "extend"
            points += x to y
        }

        override fun end() {
            calls += "end"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private val strokes = FakeStrokes()
    private var brush by mutableStateOf(BrushSettings(size = 40f, color = 0xFF000000.toInt()))
    private var textStyle by mutableStateOf(TextStyle(size = 60f))
    private val added = mutableListOf<Triple<String, Float, Float>>()

    /** 바꾸기 버튼을 누른 수 */
    private var swaps = 0

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                StratumTheme {
                    EditScreen(
                        state = EditorState.Ready(
                            PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                            document,
                            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
                            activeLayer = LayerId(0)
                        ),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        brush = brush,
                        onBrush = { brush = it },
                        strokeActions = strokes,
                        textStyle = textStyle,
                        onTextStyle = { textStyle = it },
                        onAddText = { text, x, y, _ -> added += Triple(text, x, y) },
                        onSwapColors = { swaps++ }
                    )
                }
            }
        }
    }

    @Test
    fun compactBrush_showsTheBar_andChangesTheSettings() {
        show(411, 800)
        composeRule.chooseCoverTool(EditTool.Brush)
        // 한 줄 막대(결정 기록 '커버 화면 개편'): 색 버튼들, 크기·경도·불투명도 칩(도구를 닫는 ✕는 없다, 2026-10-23). 넘치는 칩은 옆으로 밀어 본다.
        composeRule.onNodeWithTag(EditScreenTags.bar("colors")).assertIsDisplayed()
        val lefts = composeRule.barChipLefts(listOf("colors", "size", "hardness", "opacity"))
        assertTrue("$lefts should go right", lefts.zipWithNext().all { (a, b) -> a < b })

        // 전경색·배경색과 바꾸기 버튼이 막대에 바로 있다(사용자 피드백 7차, 맞바꾸는 것은 ViewModel이 한다)
        composeRule.onNodeWithTag(EditScreenTags.colorSlot(ColorSlot.FOREGROUND)).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.colorSlot(ColorSlot.BACKGROUND)).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.COLOR_SWAP).performClick()
        // 크기 칩을 펼치면 같은 줄이 크기 슬라이더가 된다
        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(100f)) }

        composeRule.runOnIdle {
            assertEquals(1, swaps)
            assertEquals(100f, brush.size, 0f)
        }
        // ✓를 누르면 칩 줄로 돌아온다
        composeRule.collapseBar()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
    }

    @Test
    fun eraser_hasNoColors() {
        show(411, 800)
        composeRule.chooseCoverTool(EditTool.Eraser)

        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.bar("colors")).assertDoesNotExist()
    }

    @Test
    fun draggingOnTheCanvas_sendsAStrokeInDocumentCoordinates() {
        show(411, 800)
        composeRule.chooseCoverTool(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).performTouchInput {
            swipe(start = center, end = center + Offset(width * 0.2f, 0f), durationMillis = 300)
        }

        composeRule.runOnIdle {
            assertEquals(listOf("begin", "extend", "end"), strokes.calls)
            // 화면에 맞춘 정사각형 문서라 캔버스 가운데는 문서 (500, 500) 근처다
            val (x, y) = strokes.points.first()
            assertTrue("start $x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
            assertTrue("moves right: ${strokes.points.last()}", strokes.points.last().first > x + 50f)
        }
    }

    @Test
    fun aSecondFinger_cancelsTheStroke() {
        show(411, 800)
        composeRule.chooseCoverTool(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).performTouchInput {
            down(0, center)
            moveBy(0, Offset(40f, 0f))
            down(1, center + Offset(0f, 200f))
            moveBy(1, Offset(0f, 50f))
            up(1)
            up(0)
        }

        composeRule.runOnIdle { assertEquals("cancel", strokes.calls.last()) }
    }

    @Test
    fun text_tapThenType_addsAtTheTappedPoint() {
        show(411, 800)
        // 텍스트는 더보기 줄에서 고르고, 그 칸(색·크기)은 한 줄 막대에 보인다(결정 기록 '커버 화면 개편')
        composeRule.chooseCoverTool(EditTool.Text)
        composeRule.onNodeWithTag(EditScreenTags.bar("textSize")).assertExists()

        composeRule.onNodeWithTag(EditScreenTags.TEXT_OVERLAY).performTouchInput { click(center) }
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).performTextInput("Hi")
        composeRule.onNodeWithTag(EditScreenTags.TEXT_ADD).performClick()

        composeRule.runOnIdle {
            val (text, x, y) = added.single()
            assertEquals("Hi", text)
            assertTrue("$x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
        }
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).assertDoesNotExist()
    }

    @Test
    fun mainScreens_showBrushOptionsInTheStrip() {
        // 메인 화면은 패널이 아니라 도구 막대 오른쪽 띠에 보인다(자세한 것은 OptionStripTest)
        show(700, 900)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.strip("size")) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).assertDoesNotExist()
    }

    @Test
    fun expanded_eraserOptionsInTheStrip() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Eraser)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.strip("hardness")) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
            .assertIsDisplayed()
    }
}
