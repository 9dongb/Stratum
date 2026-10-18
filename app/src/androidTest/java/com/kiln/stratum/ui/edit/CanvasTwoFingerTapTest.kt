package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 캔버스를 두 손가락으로 톡 누르기(설정의 '두 손가락으로 톡: 실행 취소')를 확인한다. */
@RunWith(AndroidJUnit4::class)
class CanvasTwoFingerTapTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(2000, 1000)
    private val viewState = CanvasViewState(CanvasView.Fit)
    private var taps = 0
    private var toolTaps = 0

    /** 캔버스 위에 한 손가락 톡을 받는 도구 화면(글자 도구처럼)을 겹친다 */
    private fun show(enabled: Boolean = true) {
        // 그림 내용은 보지 않으므로, 3단계(1/8) 크기의 빈 축소본이면 된다
        val preview = Bitmap.createBitmap(250, 125, Bitmap.Config.ARGB_8888)
        val ready = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 3)), document)
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(400.dp, 300.dp)) {
                    CanvasViewport(
                        ready,
                        viewState,
                        Modifier.fillMaxSize().testTag(TAG),
                        onTwoFingerTap = if (enabled) ({ taps++ }) else null
                    ) {
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toolTaps++ } })
                    }
                }
            }
        }
    }

    /** 두 손가락을 [holdMillis] 동안 대었다가 뗀다 */
    private fun TouchInjectionScope.twoFingerTap(holdMillis: Long = 80) {
        down(0, center - Offset(60f, 0f))
        down(1, center + Offset(60f, 0f))
        advanceEventTime(holdMillis)
        up(0)
        up(1)
    }

    @Test
    fun twoFingerTap_isReported_andTheToolDoesNotSeeATap() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { twoFingerTap() }

        composeRule.runOnIdle {
            assertEquals(1, taps)
            assertEquals(0, toolTaps)
            assertEquals(CanvasView.Fit, viewState.view)
        }
    }

    @Test
    fun oneFingerTap_goesToTheTool() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { click(center) }

        composeRule.runOnIdle {
            assertEquals(0, taps)
            assertEquals(1, toolTaps)
        }
    }

    @Test
    fun pinch_zooms_andIsNotATap() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(150f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(150f, 0f)
            )
        }

        composeRule.runOnIdle {
            assertEquals(0, taps)
            assertTrue(viewState.view is CanvasView.Free)
        }
    }

    @Test
    fun holdingTwoFingers_isNotATap() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { twoFingerTap(holdMillis = TWO_FINGER_TAP_MILLIS + 200) }

        composeRule.runOnIdle { assertEquals(0, taps) }
    }

    @Test
    fun threeFingers_isNotATwoFingerTap() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center)
            down(2, center + Offset(60f, 0f))
            advanceEventTime(80)
            up(0)
            up(1)
            up(2)
        }

        composeRule.runOnIdle { assertEquals(0, taps) }
    }

    @Test
    fun turnedOff_twoFingerTapDoesNothing() {
        show(enabled = false)

        composeRule.onNodeWithTag(TAG).performTouchInput { twoFingerTap() }

        composeRule.runOnIdle { assertEquals(0, taps) }
    }

    /** 편집 화면: 설정이 켜져 있고 실행 취소 버튼이 켜져 있을 때만 되돌린다 */
    private fun showEditScreen(state: EditorState, twoFingerTapUndo: Boolean, onUndo: () -> Unit) {
        composeRule.setContent {
            StratumTheme {
                EditScreen(
                    state = state,
                    onBack = {},
                    onCanvasSize = { _, _ -> },
                    onViewport = {},
                    onChooseSize = {},
                    onUndo = onUndo,
                    twoFingerTapUndo = twoFingerTapUndo
                )
            }
        }
    }

    private fun ready(canUndo: Boolean, busy: Boolean = false): EditorState.Ready {
        val size = PixelSize(400, 300)
        val preview = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(size, 0)), size, canUndo = canUndo, busy = busy)
    }

    @Test
    fun editScreen_twoFingerTap_undoes_andTellsSo() {
        var undos = 0
        showEditScreen(ready(canUndo = true), twoFingerTapUndo = true) { undos++ }

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { twoFingerTap() }

        composeRule.runOnIdle { assertEquals(1, undos) }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.action_undo))
    }

    @Test
    fun editScreen_twoFingerTap_doesNothing_whenTurnedOff() {
        var undos = 0
        showEditScreen(ready(canUndo = true), twoFingerTapUndo = false) { undos++ }

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { twoFingerTap() }

        composeRule.runOnIdle { assertEquals(0, undos) }
    }

    @Test
    fun editScreen_twoFingerTap_tellsThereIsNothingToUndo_andDoesNothingWhileBusy() {
        var undos = 0
        val state = mutableStateOf<EditorState>(ready(canUndo = false))
        composeRule.setContent {
            StratumTheme {
                EditScreen(
                    state = state.value,
                    onBack = {},
                    onCanvasSize = { _, _ -> },
                    onViewport = {},
                    onChooseSize = {},
                    onUndo = { undos++ },
                    twoFingerTapUndo = true
                )
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { twoFingerTap() }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.gesture_nothing_to_undo))
        composeRule.runOnIdle { state.value = ready(canUndo = true, busy = true) }
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput { twoFingerTap() }

        composeRule.runOnIdle { assertEquals(0, undos) }
    }

    private companion object {
        const val TAG = "viewport"
    }
}
