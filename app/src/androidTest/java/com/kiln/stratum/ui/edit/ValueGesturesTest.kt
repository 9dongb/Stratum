package com.kiln.stratum.ui.edit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * 수치 칸·칩의 공통 손가락 처리([valueGestures], 2026-10-23 커버 막대): 톡은 안쪽 누르기, 끄는 축으로 끌면 값, 다른 축은 놓아 둠,
 * 0으로 되돌릴 값은 움직이지 않고 0.3초 눌렀다 떼면 0.
 */
@RunWith(AndroidJUnit4::class)
class ValueGesturesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var fraction by mutableFloatStateOf(0.5f)
    private val calls = mutableListOf<String>()
    private val toast = GestureToastState()

    private fun show(axis: ScrubAxis = ScrubAxis.VERTICAL, resettable: Boolean = false, showValue: Boolean = false) {
        composeRule.setContent {
            StratumTheme {
                CompositionLocalProvider(LocalValueFeedback provides toast) {
                    Box(Modifier.fillMaxSize()) {
                        val slider = StripSlider(
                            fraction = fraction,
                            onFraction = { fraction = it },
                            tag = "slider",
                            onFinished = { calls += "finished" },
                            onReset = if (resettable) {
                                {
                                    calls += "reset"
                                    fraction = 0.5f
                                }
                            } else {
                                null
                            }
                        )
                        // 칸처럼 누르기 안쪽에 둔다(CellBox와 같은 차례)
                        Box(
                            Modifier
                                .size(160.dp)
                                .align(Alignment.Center)
                                .testTag(CELL)
                                .clickable { calls += "click" }
                                .valueGestures(slider, axis, CAPTION, "${(fraction * 100).roundToInt()}", showValue)
                        )
                        GestureToast(toast, Modifier.fillMaxSize())
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private val cell get() = composeRule.onNodeWithTag(CELL)

    @Test
    fun tap_isLeftToThePress() {
        show(resettable = true)
        cell.performTouchInput { click(center) }
        composeRule.runOnIdle {
            assertEquals(listOf("click"), calls)
            assertEquals(0.5f, fraction, 0f)
        }
    }

    @Test
    fun draggingUp_growsTheValue_andNoPressHappens() {
        show()
        cell.performTouchInput {
            down(center)
            moveBy(Offset(0f, -40.dp.toPx()))
            moveBy(Offset(0f, -60.dp.toPx()))
            up()
        }
        // 200dp가 처음~끝이라 100dp(터치 허용 거리를 뺀 만큼)면 반쯤 늘어난다
        composeRule.runOnIdle {
            assertTrue("fraction $fraction", fraction > 0.85f)
            assertEquals(listOf("finished"), calls)
        }
    }

    @Test
    fun draggingAcross_leavesTheValue() {
        show()
        cell.performTouchInput {
            down(center)
            moveBy(Offset(-40.dp.toPx(), 0f))
            moveBy(Offset(-30.dp.toPx(), 2f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(0.5f, fraction, 0f)
            assertTrue("$calls", "finished" !in calls)
        }
    }

    @Test
    fun horizontalAxis_growsToTheLeft_andIgnoresUpAndDown() {
        show(axis = ScrubAxis.HORIZONTAL)
        cell.performTouchInput {
            down(center)
            moveBy(Offset(0f, -60.dp.toPx()))
            up()
        }
        composeRule.runOnIdle { assertEquals(0.5f, fraction, 0f) }

        cell.performTouchInput {
            down(center)
            moveBy(Offset(-40.dp.toPx(), 0f))
            moveBy(Offset(-40.dp.toPx(), 0f))
            up()
        }
        composeRule.runOnIdle { assertTrue("fraction $fraction", fraction > 0.75f) }
    }

    @Test
    fun holdingStill_thenLifting_resetsToZero_withABuzzAndANotice() {
        show(resettable = true)
        composeRule.runOnIdle { fraction = 0.9f }
        cell.performTouchInput {
            down(center)
            // 메뉴 묶음 시간(0.3초)보다 오래 누른다
            advanceEventTime(400)
            moveBy(Offset(0f, 1f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("reset"), calls)
            assertEquals(0.5f, fraction, 0f)
        }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST)
            .assertTextEquals(composeRule.activity.getString(R.string.value_reset_to_zero, CAPTION))
    }

    @Test
    fun liftingBeforeThreeTenths_isAPress() {
        show(resettable = true)
        cell.performTouchInput {
            down(center)
            advanceEventTime(250)
            up()
        }
        composeRule.runOnIdle { assertEquals(listOf("click"), calls) }
    }

    @Test
    fun aValueThatDoesNotReset_isAPressEvenAfterAHold() {
        // 크기·경도처럼 0~100 값은 꾹 눌러도 0이 아니다(사용자 결정: -100~100 값만)
        show(resettable = false)
        cell.performTouchInput {
            down(center)
            advanceEventTime(400)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("click"), calls)
            assertEquals(0.5f, fraction, 0f)
        }
    }

    @Test
    fun draggingAfterTheHold_changesTheValueInsteadOfResetting() {
        show(resettable = true)
        cell.performTouchInput {
            down(center)
            advanceEventTime(400)
            moveBy(Offset(0f, -40.dp.toPx()))
            moveBy(Offset(0f, -40.dp.toPx()))
            up()
        }
        composeRule.runOnIdle {
            assertTrue("fraction $fraction", fraction > 0.75f)
            assertEquals(listOf("finished"), calls)
        }
    }

    @Test
    fun showValue_keepsTheValueOnTheNoticeUntilTheFingerLifts() {
        show(showValue = true)
        cell.performTouchInput {
            down(center)
            moveBy(Offset(0f, -40.dp.toPx()))
            moveBy(Offset(0f, -40.dp.toPx()))
        }
        val shown = composeRule.runOnIdle { "${(fraction * 100).roundToInt()}" }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST)
            .assertTextEquals(composeRule.activity.getString(R.string.strip_choice_description, CAPTION, shown))

        cell.performTouchInput { up() }
        composeRule.runOnIdle { assertTrue(toast.scrubText == null) }
    }

    private companion object {
        const val CELL = "cell"
        const val CAPTION = "Size"
    }
}
