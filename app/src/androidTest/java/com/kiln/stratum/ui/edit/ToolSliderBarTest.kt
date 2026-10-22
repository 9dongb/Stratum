package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 펼친 화면의 도구 수치 바(사용자 피드백 12차: "펼친 화면 기준 도구 수치 조절을 오른쪽에 바에서 일괄 조절합니다. (최대 3개까지)"):
 * 캔버스 오른쪽 가장자리 세로 가운데, 위부터 크기·경도·불투명도, 누른 자리로 튀지 않고 끈 만큼 움직임, 끄는 동안 이름·값 말풍선,
 * 카드를 눌러도 칠하지 않음, 끌기 시작하면 띠의 옵션 창을 닫음, 수치가 없는 도구는 바가 없음.
 */
@RunWith(AndroidJUnit4::class)
class ToolSliderBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var brush by mutableStateOf(BrushSettings(size = 40f, hardness = 0.5f, color = 0xFF000000.toInt()))
    private var toneOptions by mutableStateOf(ToneOptions())

    /** 받은 획 일 */
    private val strokes = mutableListOf<String>()
    private val strokeActions = object : StrokeActions {
        override fun begin(x: Float, y: Float, erase: Boolean) {
            strokes += "begin"
        }

        override fun extend(x: Float, y: Float) = Unit
        override fun end() {
            strokes += "end"
        }

        override fun cancel() {
            strokes += "cancel"
        }
    }

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show(size: DpSize) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
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
                        strokeActions = strokeActions,
                        toneOptions = toneOptions,
                        onToneOptions = { toneOptions = it }
                    )
                }
            }
        }
    }

    private fun pick(tool: EditTool) = composeRule.onNodeWithTag(EditScreenTags.railTool(tool)).performScrollTo().performClick()

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()

    private fun dp(tag: String) = with(composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.density) { 1.dp.toPx() }

    @Test
    fun landscape_barSitsOnTheRightEdge_centered_withSizeHardnessOpacityFromTheTop() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        barIsOnTheRightEdge()
    }

    @Test
    fun portrait_barSitsOnTheRightEdge_centered_withSizeHardnessOpacityFromTheTop() {
        show(MAIN_PORTRAIT)
        pick(EditTool.Brush)
        barIsOnTheRightEdge()
    }

    private fun barIsOnTheRightEdge() {
        val canvas = bounds(EditScreenTags.CANVAS)
        val bar = bounds(EditScreenTags.TOOL_SLIDER_BAR)
        val dp = dp(EditScreenTags.TOOL_SLIDER_BAR)
        // 캔버스 오른쪽 끝에서 8dp 안, 세로 가운데. 카드 폭은 도구 막대와 같은 56dp다.
        assertEquals(canvas.right - 8 * dp, bar.right, 2 * dp)
        assertEquals(canvas.center.y, bar.center.y, 2 * dp)
        assertEquals(56 * dp, bar.width, 2 * dp)
        // 사용자: "슬라이더 우선순위는 1. 크기 2. 경도 3. 불투명도"
        val size = bounds(EditScreenTags.BRUSH_SIZE)
        val hardness = bounds(EditScreenTags.BRUSH_HARDNESS)
        val opacity = bounds(EditScreenTags.BRUSH_OPACITY)
        assertTrue("$size $hardness $opacity", size.bottom <= hardness.top && hardness.bottom <= opacity.top)
        for (slider in listOf(size, hardness, opacity)) {
            assertTrue("$slider should be inside $bar", slider.left >= bar.left && slider.right <= bar.right && slider.top >= bar.top && slider.bottom <= bar.bottom)
            // 누르는 영역은 48dp 폭, 세로로 길다
            assertEquals(48 * dp, slider.width, 2 * dp)
            assertTrue("$slider should be tall", slider.height > slider.width)
        }
    }

    @Test
    fun aTapDoesNotMoveTheThumb_aDragMovesItByTheDistance() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        val slider = bounds(EditScreenTags.BRUSH_HARDNESS)
        val dp = dp(EditScreenTags.BRUSH_HARDNESS)
        val slop = android.view.ViewConfiguration.get(composeRule.activity).scaledTouchSlop.toFloat()

        // 위쪽 끝을 톡 눌러도 손잡이가 그 자리로 가지 않는다(프로크리에이트처럼 끈 만큼만)
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput {
            down(topCenter + Offset(0f, 4f))
            up()
        }
        composeRule.runOnIdle { assertEquals(0.5f, brush.hardness, 0.001f) }

        // 아래쪽에서 시작해 위로 40dp 끌면, 터치 허용 거리를 넘은 만큼 손잡이가 움직일 수 있는 길이에 대한 비율로 커진다
        val travel = slider.height - 2 * 4 * dp - 14 * dp
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput {
            down(bottomCenter - Offset(0f, 10f))
            moveBy(Offset(0f, -20 * dp))
            moveBy(Offset(0f, -20 * dp))
            up()
        }
        composeRule.runOnIdle { assertEquals(0.5f + (40 * dp - slop) / travel, brush.hardness, 0.02f) }

        // 끝을 넘게 끌면 1에서 멈춘다
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput {
            down(center)
            moveBy(Offset(0f, -300 * dp))
            moveBy(Offset(0f, -300 * dp))
            up()
        }
        composeRule.runOnIdle { assertEquals(1f, brush.hardness, 0.001f) }
    }

    @Test
    fun whileDragging_aBubbleShowsTheNameAndValue_andGoesAwayOnRelease() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        composeRule.onNodeWithTag(EditScreenTags.TOOL_SLIDER_VALUE).assertDoesNotExist()

        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput {
            down(center)
            moveBy(Offset(0f, -height / 6f))
        }
        val value = composeRule.activity.getString(R.string.layer_percent, (brush.hardness * 100).roundToInt())
        val name = composeRule.activity.getString(R.string.brush_hardness)
        composeRule.onNodeWithTag(EditScreenTags.TOOL_SLIDER_VALUE)
            .assertIsDisplayed()
            .assertTextEquals(composeRule.activity.getString(R.string.strip_slider_value, name, value))
        // 말풍선은 바 왼쪽에 뜬다
        val bubble = bounds(EditScreenTags.TOOL_SLIDER_VALUE)
        val bar = bounds(EditScreenTags.TOOL_SLIDER_BAR)
        assertTrue("$bubble should be left of $bar", bubble.right <= bar.left)

        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput { up() }
        composeRule.onNodeWithTag(EditScreenTags.TOOL_SLIDER_VALUE).assertDoesNotExist()
    }

    @Test
    fun touchingTheBar_doesNotPaint() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Brush)
        // 슬라이더 사이 카드 위를 끌어도 캔버스에 칠하지 않는다
        val hardness = bounds(EditScreenTags.BRUSH_HARDNESS)
        val opacity = bounds(EditScreenTags.BRUSH_OPACITY)
        val gap = Offset(hardness.center.x, (hardness.bottom + opacity.top) / 2)
        val canvas = bounds(EditScreenTags.CANVAS)
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            // 캔버스 노드 안 좌표로 바꿔 누른다(그 자리 맨 위의 바 카드가 받는다)
            down(gap - Offset(canvas.left, canvas.top))
            moveBy(Offset(-40f, 0f))
            up()
        }
        composeRule.runOnIdle { assertTrue("$strokes", strokes.isEmpty()) }
    }

    @Test
    fun startingADrag_closesTheStripFlyout() {
        show(MAIN_LANDSCAPE)
        pick(EditTool.Tone)
        composeRule.onNodeWithTag(EditScreenTags.strip("range")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertExists()

        composeRule.onNodeWithTag(EditScreenTags.TONE_SIZE).performTouchInput {
            down(center)
            moveBy(Offset(0f, -height / 6f))
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
    }

    @Test
    fun toolsWithoutNumbers_haveNoBar() {
        show(MAIN_LANDSCAPE)
        composeRule.onNodeWithTag(EditScreenTags.TOOL_SLIDER_BAR).assertDoesNotExist()
        pick(EditTool.Move)
        composeRule.onNodeWithTag(EditScreenTags.TOOL_SLIDER_BAR).assertDoesNotExist()
        pick(EditTool.Text)
        composeRule.onNodeWithTag(EditScreenTags.TOOL_SLIDER_BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.TEXT_SIZE).assertIsDisplayed()
    }

    private companion object {
        val MAIN_PORTRAIT = DpSize(700.dp, 900.dp)
        val MAIN_LANDSCAPE = DpSize(930.dp, 700.dp)
    }
}
