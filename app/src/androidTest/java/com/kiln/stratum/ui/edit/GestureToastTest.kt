package com.kiln.stratum.ui.edit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 제스처 알림(결정 기록 '제스처 알림'): 1초 보이고 사라지며, 다시 띄우면 다시 1초, 세로 힌지가 지나도 가운데에 뜨고 가로 힌지는 피한다 */
@RunWith(AndroidJUnit4::class)
class GestureToastTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val toast = GestureToastState()

    private fun show() {
        composeRule.setContent {
            CompositionLocalProvider(LocalHinges provides hinges.value) {
                StratumTheme {
                    Box(Modifier.fillMaxSize()) { GestureToast(toast, Modifier.fillMaxSize()) }
                }
            }
        }
    }

    @Test
    fun showsTheText_thenDisappearsAfterASecond() {
        show()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle { toast.show("Undo") }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals("Undo")

        composeRule.mainClock.advanceTimeBy(GESTURE_TOAST_MILLIS + 1_000)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun showingAgain_keepsItLonger() {
        show()
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnIdle { toast.show("Undo") }
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.runOnIdle { toast.show("Redo") }
        // 처음 띄운 지 1초가 지나도 다시 띄운 것이 보인다
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals("Redo")

        composeRule.mainClock.advanceTimeBy(GESTURE_TOAST_MILLIS + 1_000)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun staysInTheMiddle_evenAcrossAVerticalHinge() {
        show()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)
        composeRule.runOnIdle {
            hinges.value = listOf(hinge)
            toast.show("Nothing to undo")
        }

        // 사용자: "중앙에 떠도 됩니다" — 접히는 선을 피해 한쪽으로 가지 않는다
        val bounds = composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(screen.center.x, bounds.center.x, 2f)
    }

    @Test
    fun movesBelowAHorizontalHinge() {
        show()
        composeRule.runOnIdle { toast.show("Undo") }
        val first = composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).fetchSemanticsNode()
        val before = first.layoutBoundsInWindow()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        // 알림이 있던 높이를 지나는 가로 힌지
        val y = before.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        val node = composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).fetchSemanticsNode()
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        assertTrue("${node.layoutBoundsInWindow()} should be below $band", node.layoutBoundsInWindow().top >= band.end - 1f)
    }
}
