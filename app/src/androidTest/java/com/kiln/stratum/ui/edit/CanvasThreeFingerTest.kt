package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
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
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 캔버스의 세 손가락 제스처(설정의 '세 손가락 제스처', 결정 기록 '세 손가락 제스처')를 확인한다. */
@RunWith(AndroidJUnit4::class)
class CanvasThreeFingerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(2000, 1000)
    private val viewState = CanvasViewState(CanvasView.Fit)
    private var threeTaps = 0
    private var twoTaps = 0
    private var toolTaps = 0
    private val swipes = mutableListOf<Pair<SwipeAxis, Float>>()
    private var swipeEnds = 0

    /** 받은 세 손가락 제스처를 적는다 */
    private val recorder = object : ThreeFingerActions {
        override fun tap() {
            threeTaps++
        }

        override fun swipe(axis: SwipeAxis, distance: Dp) {
            swipes += axis to distance.value
        }

        override fun swipeEnd() {
            swipeEnds++
        }
    }

    /** 캔버스 위에 한 손가락 톡을 받는 도구 화면(글자 도구처럼)을 겹친다 */
    private fun show(enabled: Boolean = true) {
        val preview = Bitmap.createBitmap(250, 125, Bitmap.Config.ARGB_8888)
        val ready = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 3)), document)
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(400.dp, 300.dp)) {
                    CanvasViewport(
                        ready,
                        viewState,
                        Modifier.fillMaxSize().testTag(TAG),
                        onTwoFingerTap = { twoTaps++ },
                        threeFinger = recorder.takeIf { enabled }
                    ) {
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toolTaps++ } })
                    }
                }
            }
        }
    }

    /** 화면 픽셀을 dp 숫자로 */
    private fun dp(pixels: Float): Float = with(composeRule.density) { pixels.toDp().value }

    @Test
    fun threeFingerTap_isReportedOnce_andIsNotATwoFingerTap_orAToolTap() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { threeFingerTap() }

        composeRule.runOnIdle {
            assertEquals(1, threeTaps)
            assertEquals(0, twoTaps)
            assertEquals(0, toolTaps)
            assertTrue(swipes.isEmpty())
            assertEquals(0, swipeEnds)
            assertEquals(CanvasView.Fit, viewState.view)
        }
    }

    @Test
    fun holdingThreeFingers_isNotATap() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { threeFingerTap(holdMillis = THREE_FINGER_TAP_MILLIS + 200) }

        composeRule.runOnIdle {
            assertEquals(0, threeTaps)
            assertEquals(0, toolTaps)
        }
    }

    @Test
    fun fourFingers_isNotATap() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput {
            for (id in 0..3) down(id, center + Offset(-120f + id * 80f, 0f))
            advanceEventTime(80)
            for (id in 0..3) up(id)
        }

        composeRule.runOnIdle {
            assertEquals(0, threeTaps)
            assertEquals(0, twoTaps)
        }
    }

    @Test
    fun swipingUp_reportsAVerticalSwipe_withUpAsPlus() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { threeFingerSwipe(Offset(0f, -300f)) }

        composeRule.runOnIdle {
            assertTrue(swipes.all { it.first == SwipeAxis.VERTICAL })
            // 방향을 정하는 순간 0으로 한 번, 그 뒤로 움직인 만큼(허용 거리까지의 움직임은 빼고)
            assertEquals(0f, swipes.first().second, 0f)
            val last = swipes.last().second
            assertTrue("$last", last > dp(180f) && last <= dp(300f) + 0.5f)
            assertEquals(1, swipeEnds)
            assertEquals(0, threeTaps)
            assertEquals(0, toolTaps)
            // 세 손가락으로 밀어도 캔버스는 움직이지 않는다
            assertEquals(CanvasView.Fit, viewState.view)
        }
    }

    @Test
    fun swipingLeft_reportsAHorizontalSwipe_withLeftAsMinus() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { threeFingerSwipe(Offset(-200f, 20f)) }

        composeRule.runOnIdle {
            assertTrue(swipes.all { it.first == SwipeAxis.HORIZONTAL })
            assertTrue("${swipes.last()}", swipes.last().second < -dp(100f))
            assertEquals(1, swipeEnds)
        }
    }

    @Test
    fun theAxis_staysTheSame_whenTheFingersTurn() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput {
            threeFingerSwipe(Offset(0f, -120f), lift = false)
            // 위로 밀기 시작한 뒤 옆으로 가도 위아래 밀기다(옆 움직임은 넣지 않는다)
            moveThreeFingers(Offset(250f, 0f))
            for (id in 0..2) up(id)
        }

        composeRule.runOnIdle {
            assertTrue(swipes.all { it.first == SwipeAxis.VERTICAL })
            assertTrue("${swipes.last()}", swipes.last().second in dp(60f)..dp(120f) + 0.5f)
            assertEquals(1, swipeEnds)
        }
    }

    @Test
    fun liftingSomeFingers_keepsTheSwipeGoing_withTheRest() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput {
            threeFingerSwipe(Offset(0f, -100f), lift = false)
            up(2)
            // 남은 두 손가락으로 더 민다. 손가락을 뗀 순간 가운데가 옮겨진 것은 움직임이 아니다.
            repeat(5) {
                for (id in 0..1) updatePointerBy(id, Offset(0f, -20f))
                move()
            }
            up(0)
            up(1)
        }

        composeRule.runOnIdle {
            val last = swipes.last().second
            assertTrue("$last", last > dp(150f) && last <= dp(200f) + 0.5f)
            assertEquals(1, swipeEnds)
            assertEquals(CanvasView.Fit, viewState.view)
            assertEquals(0, twoTaps)
        }
    }

    @Test
    fun aThirdFinger_duringAPinch_keepsZooming() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            repeat(6) {
                updatePointerBy(0, Offset(-15f, 0f))
                updatePointerBy(1, Offset(15f, 0f))
                move()
            }
            down(2, center + Offset(0f, 60f))
            repeat(4) {
                updatePointerBy(0, Offset(-15f, 0f))
                updatePointerBy(1, Offset(15f, 0f))
                move()
            }
            for (id in 0..2) up(id)
        }

        composeRule.runOnIdle {
            assertTrue(swipes.isEmpty())
            assertEquals(0, threeTaps)
            assertTrue(viewState.view is CanvasView.Free)
        }
    }

    @Test
    fun turnedOff_threeFingersMoveTheCanvasAsBefore() {
        show(enabled = false)

        composeRule.onNodeWithTag(TAG).performTouchInput { threeFingerSwipe(Offset(0f, -150f)) }

        composeRule.runOnIdle {
            assertTrue(swipes.isEmpty())
            assertTrue(viewState.view is CanvasView.Free)
        }
    }

    // --- 편집 화면 ---

    private var brush by mutableStateOf(BrushSettings(size = 40f, hardness = 0.5f))
    private var eraser by mutableStateOf(BrushSettings(size = 80f, hardness = 0.5f))
    private var retouch by mutableStateOf(BrushSettings(size = 60f, hardness = 0.5f))
    private var tone by mutableStateOf(ToneOptions())
    private var textStyle by mutableStateOf(TextStyle())
    private var redos = 0

    private fun ready(canRedo: Boolean = false, busy: Boolean = false): EditorState.Ready {
        val size = PixelSize(1000, 1000)
        val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(
            PreviewImage(preview, PreviewLevels.wholeRegion(size, 1)),
            size,
            canRedo = canRedo,
            busy = busy,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
    }

    /** 커버 크기 편집 화면. [tool]이 있으면 그 도구를 고른다. */
    private fun showEditScreen(state: EditorState = ready(), threeFingerGestures: Boolean = true, tool: EditTool? = null) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = state,
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        onRedo = { redos++ },
                        brush = brush,
                        onBrush = { brush = it },
                        eraser = eraser,
                        onEraser = { eraser = it },
                        textStyle = textStyle,
                        onTextStyle = { textStyle = it },
                        threeFingerGestures = threeFingerGestures,
                        retouch = retouch,
                        onRetouch = { retouch = it },
                        toneOptions = tone,
                        onToneOptions = { tone = it }
                    )
                }
            }
        }
        if (tool != null) composeRule.chooseCoverTool(tool)
    }

    @Test
    fun editScreen_threeFingerTap_redoes_andTellsSo() {
        showEditScreen(ready(canRedo = true))

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { threeFingerTap() }

        composeRule.runOnIdle { assertEquals(1, redos) }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.action_redo))
    }

    @Test
    fun editScreen_threeFingerTap_tellsThereIsNothingToRedo_andDoesNothingWhileBusy() {
        val state = mutableStateOf<EditorState>(ready(canRedo = false))
        composeRule.setContent {
            StratumTheme {
                EditScreen(
                    state = state.value,
                    onBack = {},
                    onCanvasSize = { _, _ -> },
                    onViewport = {},
                    onChooseSize = {},
                    onRedo = { redos++ },
                    threeFingerGestures = true
                )
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { threeFingerTap() }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.gesture_nothing_to_redo))
        composeRule.runOnIdle { state.value = ready(canRedo = true, busy = true) }
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { threeFingerTap() }

        composeRule.runOnIdle { assertEquals(0, redos) }
    }

    @Test
    fun editScreen_turnedOff_threeFingersDoNothing() {
        showEditScreen(ready(canRedo = true), threeFingerGestures = false, tool = EditTool.Brush)

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { threeFingerTap() }
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { threeFingerSwipe(Offset(0f, -200f)) }

        composeRule.runOnIdle {
            assertEquals(0, redos)
            assertEquals(40f, brush.size, 0f)
        }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
    }

    @Test
    fun editScreen_brush_swipingUp_growsTheSize_andShowsOnlyTheSizePreview() {
        showEditScreen(tool = EditTool.Brush)
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS)
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()

        canvas.performTouchInput { threeFingerSwipe(Offset(0f, -240f), lift = false) }

        // 미는 동안: 크기가 커지고, 캔버스 가운데에 크기 원만 보인다(알림 없음)
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue("${brush.size}", brush.size > 40f)
            assertEquals(0.5f, brush.hardness, 0f)
        }

        // 손을 떼면 크기 원이 사라지고 바꾼 크기는 남는다
        canvas.performTouchInput { for (id in 0..2) up(id) }
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(brush.size > 40f) }
    }

    @Test
    fun editScreen_brush_swipingDown_shrinks_andRightHardens() {
        showEditScreen(tool = EditTool.Brush)
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS)

        canvas.performTouchInput { threeFingerSwipe(Offset(0f, 200f)) }
        composeRule.runOnIdle { assertTrue("${brush.size}", brush.size < 40f) }

        val sized = brush.size
        canvas.performTouchInput { threeFingerSwipe(Offset(200f, 0f)) }
        composeRule.runOnIdle {
            assertTrue("${brush.hardness}", brush.hardness > 0.5f)
            assertEquals(sized, brush.size, 0f)
        }
    }

    @Test
    fun editScreen_swipes_changeTheChosenToolsOwnSettings() {
        showEditScreen(tool = EditTool.Eraser)
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS)
        // 캔버스 위쪽에서 민다(어느 도구든 옵션은 캔버스 아래 한 줄 막대에 있다)
        canvas.performTouchInput { threeFingerSwipe(Offset(0f, -200f), at = Offset(centerX, height * 0.3f)) }
        composeRule.runOnIdle {
            assertTrue("${eraser.size}", eraser.size > 80f)
            assertEquals(40f, brush.size, 0f)
        }

        composeRule.chooseCoverTool(EditTool.Tone)
        canvas.performTouchInput { threeFingerSwipe(Offset(-200f, 0f), at = Offset(centerX, height * 0.2f)) }
        composeRule.runOnIdle { assertTrue("${tone.brush.hardness}", tone.brush.hardness < 0.3f) }

        composeRule.chooseCoverTool(EditTool.Retouch)
        canvas.performTouchInput { threeFingerSwipe(Offset(0f, 200f), at = Offset(centerX, height * 0.2f)) }
        composeRule.runOnIdle { assertTrue("${retouch.size}", retouch.size < 60f) }
    }

    @Test
    fun editScreen_aiRemove_swipesChangeOnlyTheSize() {
        showEditScreen(tool = EditTool.Retouch)
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_REMOVE)
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS)

        // AI 제거는 경도가 없어 좌우로 밀어도 리터칭의 경도(다른 하위 도구가 함께 씀)는 그대로다
        canvas.performTouchInput { threeFingerSwipe(Offset(200f, 0f), at = Offset(centerX, height * 0.2f)) }
        composeRule.runOnIdle { assertEquals(BrushSettings(size = 60f, hardness = 0.5f), retouch) }

        canvas.performTouchInput { threeFingerSwipe(Offset(0f, -200f), at = Offset(centerX, height * 0.2f)) }
        composeRule.runOnIdle {
            assertTrue("${retouch.size}", retouch.size > 60f)
            assertEquals(0.5f, retouch.hardness, 0f)
        }
    }

    @Test
    fun editScreen_textTool_swipesChangeNothing() {
        showEditScreen(tool = EditTool.Text)
        val before = textStyle

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { threeFingerSwipe(Offset(0f, -200f)) }

        composeRule.runOnIdle {
            assertEquals(before, textStyle)
            assertEquals(BrushSettings(size = 40f, hardness = 0.5f), brush)
        }
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()
    }

    private companion object {
        const val TAG = "viewport"
    }
}

/** 세 손가락을 [holdMillis] 동안 대었다가 뗀다(세 손가락 톡) */
internal fun TouchInjectionScope.threeFingerTap(holdMillis: Long = 80) {
    down(0, center - Offset(80f, 0f))
    down(1, center)
    down(2, center + Offset(80f, 0f))
    advanceEventTime(holdMillis)
    up(0)
    up(1)
    up(2)
}

/** 세 손가락을 대고 함께 [by]만큼 민다. [lift]면 끝에 모두 뗀다. */
internal fun TouchInjectionScope.threeFingerSwipe(by: Offset, lift: Boolean = true, at: Offset = center) {
    down(0, at - Offset(80f, 0f))
    down(1, at)
    down(2, at + Offset(80f, 0f))
    moveThreeFingers(by)
    if (lift) {
        up(0)
        up(1)
        up(2)
    }
}

/** 닿아 있는 세 손가락을 함께 [by]만큼 10번에 나눠 옮긴다 */
internal fun TouchInjectionScope.moveThreeFingers(by: Offset) {
    repeat(10) {
        for (id in 0..2) updatePointerBy(id, by / 10f)
        move()
    }
}
