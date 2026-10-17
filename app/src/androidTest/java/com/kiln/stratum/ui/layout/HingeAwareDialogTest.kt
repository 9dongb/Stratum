package com.kiln.stratum.ui.layout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 힌지를 피하는 창이 힌지 한쪽에 뜨는지, 닫기가 맞게 동작하는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class HingeAwareDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 창이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    private var dismissCount = 0

    private fun showDialog(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        Box(Modifier.fillMaxSize()) {
                            HingeAwareDialog(
                                onDismissRequest = { dismissCount++ },
                                title = "Title",
                                buttons = {
                                    Row {
                                        TextButton(onClick = {}) { Text("Cancel") }
                                        TextButton(onClick = {}) { Text("OK") }
                                    }
                                }
                            ) {
                                Text("Body")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun screen(): Rect = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()

    private fun card(): Rect = composeRule.onNodeWithTag(HINGE_AWARE_DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()

    /** 띠의 반쪽 폭. 화면 루트가 아니라 화면 크기 강제 안쪽 노드(카드)의 밀도로 잰다. */
    private fun marginPx(): Int =
        with(composeRule.onNodeWithTag(HINGE_AWARE_DIALOG_CARD).fetchSemanticsNode().layoutInfo.density) {
            HingeMargin.roundToPx()
        }

    @Test
    fun noHinge_centersCardHorizontally() {
        showDialog(930, 700)

        assertTrue("card ${card()} not centered in ${screen()}", abs(card().center.x - screen().center.x) <= 2f)
    }

    @Test
    fun verticalHinge_putsCardOnTheRightSide() {
        showDialog(930, 700)
        val centerX = screen().center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen().bottom.roundToInt(), isVertical = true)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        assertTrue("card ${card()} is not right of the band", card().left >= centerX + marginPx())
    }

    @Test
    fun horizontalHinge_putsCardOnTheTopSide() {
        showDialog(700, 900)
        val centerY = screen().center.y.roundToInt()
        val hinge = Hinge(left = 0, top = centerY, right = screen().right.roundToInt(), bottom = centerY, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        assertTrue("card ${card()} is not above the band", card().bottom <= centerY - marginPx())
    }

    @Test
    fun tapOnCardKeepsDialog_tapOutsideDismisses() {
        showDialog(930, 700)

        // 카드 안의 글자(누를 수 없는 곳)를 눌러도 닫히지 않는다
        composeRule.onNodeWithText("Title").performClick()
        composeRule.runOnIdle { assertEquals(0, dismissCount) }

        // 카드 밖(화면 왼쪽 위 구석)을 누르면 닫힌다
        composeRule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun backDismisses() {
        showDialog(930, 700)

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }
}
