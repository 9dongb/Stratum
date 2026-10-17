package com.kiln.stratum.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 아래쪽 알림 자리가 접히는 선의 띠를 피하는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class HingeAvoidingBottomTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    private fun show() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(700.dp, 400.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        Box(Modifier.fillMaxSize()) {
                            HingeAvoidingBottom(Modifier.fillMaxSize(), margin = 8.dp) {
                                Text(LONG_MESSAGE, Modifier.testTag(TAG))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun message(): Rect = composeRule.onNodeWithTag(TAG).fetchSemanticsNode().layoutBoundsInWindow()

    private fun screen(): Rect = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()

    /** 띠의 반쪽 폭. 화면 크기 강제 안쪽 노드(알림)의 밀도로 잰다. */
    private fun marginPx(): Int =
        with(composeRule.onNodeWithTag(TAG).fetchSemanticsNode().layoutInfo.density) { HingeMargin.roundToPx() }

    @Test
    fun noHinge_centersAtTheBottom() {
        show()

        val message = message()
        val screen = screen()
        assertTrue("$message is not centered in $screen", abs(message.center.x - screen.center.x) <= 1f)
        assertTrue("$message is not at the bottom of $screen", message.bottom <= screen.bottom && message.bottom > screen.bottom - 100)
    }

    @Test
    fun verticalHinge_keepsTheMessageOnOneSide() {
        show()
        val screen = screen()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        val message = message()
        val margin = marginPx()
        assertTrue("$message crosses the band", message.right <= centerX - margin || message.left >= centerX + margin)
        assertTrue("$message is outside $screen", message.left >= screen.left && message.right <= screen.right)
    }

    @Test
    fun horizontalHingeNearTheBottom_movesTheMessageAbove() {
        show()
        val screen = screen()
        val y = screen.bottom.roundToInt() - 30
        val hinge = Hinge(left = 0, top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        val message = message()
        assertTrue("$message is not above the band", message.bottom <= y - marginPx())
    }

    private companion object {
        const val TAG = "message"
        const val LONG_MESSAGE = "Not enough memory to apply this edit. This sentence is long so that it would cross the middle."
    }
}
