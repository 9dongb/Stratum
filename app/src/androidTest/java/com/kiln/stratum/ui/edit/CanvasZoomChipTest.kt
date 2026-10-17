package com.kiln.stratum.ui.edit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
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

/** 캔버스 왼쪽 위의 배율 표시가 확대했을 때만 보이고, 누르면 맞추고, 접히는 부분을 피하는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class CanvasZoomChipTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val viewState = CanvasViewState(CanvasView.Fit)

    private fun show() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(700.dp, 900.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        Box(Modifier.fillMaxSize()) { CanvasZoomChip(viewState, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }

    private fun chip() = composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).fetchSemanticsNode().layoutBoundsInWindow()

    /** 띠의 반쪽 폭. 화면 루트가 아니라 화면 크기 강제 안쪽 노드(배율 표시)의 밀도로 잰다. */
    private fun marginPx(): Int =
        with(composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).fetchSemanticsNode().layoutInfo.density) {
            HingeMargin.roundToPx()
        }

    @Test
    fun hiddenAtFit_shownWhenZoomed_tapFits() {
        show()
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()

        composeRule.runOnIdle { viewState.view = CanvasView.Free(scale = 2.5, centerX = 100.0, centerY = 100.0) }
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.canvas_zoom_fit_description, "250"))
            .performClick()

        composeRule.runOnIdle { assertEquals(CanvasView.Fit, viewState.view) }
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()
    }

    @Test
    fun movesOffVerticalHinge() {
        show()
        composeRule.runOnIdle { viewState.view = CanvasView.Free(scale = 2.5, centerX = 100.0, centerY = 100.0) }
        // 배율 표시 한가운데를 세로로 지나가는 힌지
        val centerX = chip().center.x.roundToInt()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        assertTrue("chip ${chip()} is not right of the band", chip().left >= centerX + marginPx())
    }

    @Test
    fun movesOffHorizontalHinge() {
        show()
        composeRule.runOnIdle { viewState.view = CanvasView.Free(scale = 2.5, centerX = 100.0, centerY = 100.0) }
        val centerY = chip().center.y.roundToInt()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val hinge = Hinge(left = 0, top = centerY, right = screen.right.roundToInt(), bottom = centerY, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        assertTrue("chip ${chip()} is not below the band", chip().top >= centerY + marginPx())
    }
}
