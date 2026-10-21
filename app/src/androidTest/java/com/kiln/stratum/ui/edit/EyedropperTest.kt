package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.kiln.stratum.ui.components.LongPressTimes
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 스포이드(결정 기록 '스포이드'): 색 줄의 버튼을 켜고 캔버스를 누르기, 브러시로 꾹 누르기, 손가락 둘레 고리.
 * 엔진 대신 받은 일만 적는 가짜로 본다.
 */
@RunWith(AndroidJUnit4::class)
class EyedropperTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()


    /** 받은 획·스포이드 일 */
    private val calls = mutableListOf<String>()
    private val points = mutableListOf<Pair<Float, Float>>()

    private val strokes = object : StrokeActions {
        override fun begin(x: Float, y: Float, erase: Boolean) {
            calls += if (erase) "erase" else "stroke"
        }

        override fun extend(x: Float, y: Float) = Unit

        override fun end() {
            calls += "stroke end"
        }

        override fun cancel() {
            calls += "stroke cancel"
        }
    }

    private val eyedropperActions = object : EyedropperActions {
        override fun begin(target: ColorTarget, x: Float, y: Float) {
            calls += "pick $target"
            points += x to y
        }

        override fun move(x: Float, y: Float) {
            if (calls.lastOrNull() != "pick move") calls += "pick move"
        }

        override fun end() {
            calls += "pick end"
        }

        override fun cancel() {
            calls += "pick cancel"
        }
    }

    private var brush by mutableStateOf(BrushSettings(size = 40f, color = 0xFF000000.toInt()))
    private var eyedropper by mutableStateOf<EyedropperState?>(null)

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show(tool: EditTool) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
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
                        eyedropper = { eyedropper },
                        eyedropperActions = eyedropperActions
                    )
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(tool)).performScrollTo().performClick()
    }

    @Test
    fun button_picksWhereTheCanvasIsPressed_thenTurnsOff() {
        show(EditTool.Brush)
        val button = composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER)

        button.assertIsOff().performClick()
        button.assertIsOn()
        // 켜는 동안은 칠하는 손가락 대신 스포이드 손가락이다
        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_OVERLAY).performTouchInput { click(center) }

        button.assertIsOff()
        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).assertExists()
        composeRule.runOnIdle {
            assertEquals(listOf("pick BRUSH", "pick move", "pick end"), calls)
            val (x, y) = points.single()
            assertTrue("$x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
        }
    }

    @Test
    fun holdingTheBrushStill_turnsTheStrokeIntoTheEyedropper() {
        show(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).performTouchInput {
            down(center)
            // 길게 누르기 시간(약 0.4초)보다 오래 움직이지 않는다
            advanceEventTime(800)
            moveBy(Offset(40f, 0f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("stroke", "stroke cancel", "pick BRUSH", "pick move", "pick end"), calls)
        }
    }

    @Test
    fun holdingForTheMenuTime_isNotYetTheEyedropper() {
        show(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).performTouchInput {
            // 편집 묶음(캔버스 안 꾹 누르기)은 휴대폰 설정(기본 0.4초)이라, 메뉴 묶음(0.3초)만큼 눌러서는 아직 칠하기다
            assumeTrue(viewConfiguration.longPressTimeoutMillis > LongPressTimes.MENU_MILLIS + 40)
            down(center)
            advanceEventTime(LongPressTimes.MENU_MILLIS + 20)
            up()
        }

        composeRule.runOnIdle { assertEquals(listOf("stroke", "stroke end"), calls) }
    }

    @Test
    fun movingTheBrushRightAway_stillPaints() {
        show(EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(120f, 0f))
            advanceEventTime(800)
            moveBy(Offset(0f, 120f))
            up()
        }

        composeRule.runOnIdle { assertEquals(listOf("stroke", "stroke end"), calls) }
    }

    @Test
    fun eraser_doesNotPickColors() {
        show(EditTool.Eraser)

        composeRule.onNodeWithTag(EditScreenTags.STROKE_OVERLAY).performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }

        composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("erase", "stroke end"), calls) }
    }

    @Test
    fun text_buttonPicksForTheText() {
        show(EditTool.Text)

        composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_OVERLAY).performTouchInput { click(center) }

        composeRule.runOnIdle { assertEquals("pick TEXT", calls.first()) }
        // 글자 창은 뜨지 않는다(누른 것은 스포이드였다)
        composeRule.onNodeWithTag(EditScreenTags.TEXT_FIELD).assertDoesNotExist()
    }

    @Test
    fun loupe_showsWhilePicking() {
        show(EditTool.Brush)
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_LOUPE).assertDoesNotExist()

        composeRule.runOnIdle { eyedropper = EyedropperState(ColorTarget.BRUSH, 500f, 500f, Color.RED) }

        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_LOUPE).assertExists()
    }
}
