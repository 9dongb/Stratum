package com.kiln.stratum.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.canvas.MAX_CANVAS_SIDE
import com.kiln.stratum.ui.layout.HINGE_AWARE_DIALOG_CARD
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.HingeMargin
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 홈의 새 캔버스 창을 확인한다. 커버 화면 폭은 아래 시트, 메인 화면 폭은 힌지를 피하는 창이다.
 * 시트는 별도 창이라 화면 크기 강제가 적용되지 않으므로, 시트에서는 동작만 본다.
 */
@RunWith(AndroidJUnit4::class)
class NewCanvasTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 창이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    /** '만들기'로 넘어온 값들 */
    private val created = mutableListOf<Triple<Int, Int, CanvasFill>>()

    private fun string(id: Int, vararg args: Any): String = composeRule.activity.getString(id, *args)

    private fun showHome(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        HomeScreen(
                            onOpenPhoto = {},
                            onCreateCanvas = { width, height, fill -> created += Triple(width, height, fill) }
                        )
                    }
                }
            }
        }
    }

    /** 홈의 '새 캔버스' 버튼을 누른다. 창 제목과 글자가 같아서 누를 수 있는 것으로 찾는다. */
    private fun openNewCanvas() {
        composeRule.onNode(hasText(string(R.string.home_new_canvas)) and hasClickAction()).performClick()
    }

    @Test
    fun compactSheet_createsChosenPresetAndBackground() {
        showHome(411, 800)
        openNewCanvas()

        composeRule.onNodeWithTag(NewCanvasTags.preset(CanvasPreset.LANDSCAPE)).performScrollTo().performClick()
        composeRule.onNodeWithTag(NewCanvasTags.background(CanvasBackground.TRANSPARENT)).performScrollTo().performClick()
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).performClick()

        composeRule.runOnIdle { assertEquals(listOf(Triple(3840, 2160, CanvasFill.Transparent)), created) }
    }

    @Test
    fun invalidSize_disablesCreateAndShowsReason() {
        showHome(411, 800)
        openNewCanvas()

        composeRule.onNodeWithTag(NewCanvasTags.WIDTH).performScrollTo().performTextReplacement("0")
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.new_canvas_error_range, MAX_CANVAS_SIDE)).assertExists()

        composeRule.onNodeWithTag(NewCanvasTags.WIDTH).performTextReplacement("16383")
        composeRule.onNodeWithTag(NewCanvasTags.HEIGHT).performTextReplacement("3052")
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.new_canvas_error_too_large, 50)).assertExists()
    }

    @Test
    fun swap_flipsSizeAndSelectedPresetFollows() {
        showHome(411, 800)
        openNewCanvas()
        composeRule.onNodeWithTag(NewCanvasTags.preset(CanvasPreset.LANDSCAPE)).performScrollTo().performClick()

        composeRule.onNodeWithTag(NewCanvasTags.SWAP).performScrollTo().performClick()

        composeRule.onNodeWithTag(NewCanvasTags.WIDTH).assert(hasText("2160"))
        composeRule.onNodeWithTag(NewCanvasTags.HEIGHT).assert(hasText("3840"))
        composeRule.onNodeWithTag(NewCanvasTags.preset(CanvasPreset.STORY)).assertIsSelected()
    }

    @Test
    fun expandedDialog_avoidsVerticalHinge() {
        showHome(930, 700)
        openNewCanvas()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        val card = composeRule.onNodeWithTag(HINGE_AWARE_DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()
        val margin = with(composeRule.onRoot().fetchSemanticsNode().layoutInfo.density) { HingeMargin.roundToPx() }
        assertTrue("card $card is not right of the band", card.left >= centerX + margin)
    }

    @Test
    fun mediumDialog_avoidsHorizontalHinge() {
        showHome(700, 900)
        openNewCanvas()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerY = screen.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = centerY, right = screen.right.roundToInt(), bottom = centerY, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        val card = composeRule.onNodeWithTag(HINGE_AWARE_DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()
        val margin = with(composeRule.onRoot().fetchSemanticsNode().layoutInfo.density) { HingeMargin.roundToPx() }
        assertTrue("card $card is not above the band", card.bottom <= centerY - margin)
        // 창 높이가 화면 반쪽으로 줄어도 '만들기'는 보인다
        composeRule.onNodeWithTag(NewCanvasTags.CREATE).assertExists()
    }
}
