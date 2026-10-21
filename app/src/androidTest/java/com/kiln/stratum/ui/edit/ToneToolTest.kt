package com.kiln.stratum.ui.edit

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.tone.ToneRange
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 명암 도구(닷지·번)의 옵션·캔버스 끌기와 편집 화면에서 여는 것을 확인한다(엔진 대신 받은 일만 적는 가짜). */
@RunWith(AndroidJUnit4::class)
class ToneToolTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())

    /** 받은 일 */
    private class FakeTone : ToneActions {
        val calls = mutableListOf<String>()
        val points = mutableListOf<Pair<Float, Float>>()
        var dodge: Boolean? = null

        override fun begin(x: Float, y: Float, dodge: Boolean) {
            calls += "begin"
            points += x to y
            this.dodge = dodge
        }

        override fun extend(x: Float, y: Float) {
            if (calls.lastOrNull() != "extend") calls += "extend"
        }

        override fun end() {
            calls += "end"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private val actions = FakeTone()
    private val document = PixelSize(1000, 1000)
    private var options by mutableStateOf(ToneOptions())

    private fun showOptions(mode: SubTool, widthDp: Int = 411) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 800.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme { ToneToolOptions(mode, ToneControls(options) { options = it }, onChoose = {}) }
                }
            }
        }
    }

    @Test
    fun options_haveRangeSizeHardnessExposure_andTheHelpFollowsTheMode() {
        showOptions(SubTool.TONE_BURN)

        composeRule.onNodeWithTag(EditScreenTags.toneRange(ToneRange.MIDTONES)).assertIsSelected()
        // 슬라이더는 크기 → 경도 → 노출(강도 자리) 순서다(사용자 피드백 9차)
        val tops = listOf(EditScreenTags.TONE_SIZE, EditScreenTags.TONE_HARDNESS, EditScreenTags.TONE_EXPOSURE).map {
            composeRule.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().layoutBoundsInWindow().top
        }
        assertTrue("$tops should go down", tops.zipWithNext().all { (a, b) -> a < b })
        // 하위 도구 줄은 맨 아래이고(사용자 피드백 8차 1-1), 설명 줄도 물음표도 없다(10차 5: 안내는 처음 고를 때 편집 화면의 알림)
        val burn = composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.TONE_BURN)).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$burn should be below the sliders", burn.top >= tops.last())
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.tone_hint_burn)).assertDoesNotExist()
    }

    @Test
    fun options_change() {
        showOptions(SubTool.TONE_DODGE)

        composeRule.onNodeWithTag(EditScreenTags.toneRange(ToneRange.SHADOWS)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.TONE_EXPOSURE).performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        composeRule.onNodeWithTag(EditScreenTags.TONE_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(150f)) }
        composeRule.onNodeWithTag(EditScreenTags.TONE_HARDNESS).performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }

        composeRule.runOnIdle {
            assertEquals(ToneRange.SHADOWS, options.range)
            assertEquals(0.3f, options.exposure, 0.001f)
            assertEquals(150f, options.brush.size, 0f)
            assertEquals(0.7f, options.brush.hardness, 0.001f)
        }
    }

    @Test
    fun exposure_neverGoesToZero() {
        showOptions(SubTool.TONE_DODGE)

        composeRule.onNodeWithTag(EditScreenTags.TONE_EXPOSURE).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }

        composeRule.runOnIdle { assertEquals(0.01f, options.exposure, 0.0001f) }
    }

    @Test
    fun dragging_sendsTheStroke_withDodgeOrBurn() {
        var dodge by mutableStateOf(false)
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 400.dp))) {
                StratumTheme {
                    Box(Modifier.fillMaxSize()) {
                        ToneOverlay(view = { CanvasView.Fit }, document = document, dodge = dodge, actions = actions, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.TONE_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("begin", "extend", "end"), actions.calls)
            assertEquals(false, actions.dodge)
            val (x, y) = actions.points.first()
            assertTrue("$x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
            dodge = true
        }
        composeRule.onNodeWithTag(EditScreenTags.TONE_OVERLAY).performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(true, actions.dodge) }
    }

    @Test
    fun editScreen_toneTool_opensItsOptions() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {}, toneActions = actions)
                }
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Tone)).performScrollTo().performClick()

        composeRule.onNodeWithTag(EditScreenTags.TONE_OPTIONS).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.TONE_OVERLAY).assertExists()
        // 처음 고르면 안내 글이 알림으로 뜬다(사용자 피드백 10차 5)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.tone_hint_dodge))
    }

    @Test
    fun mainPortrait_optionsAvoidTheHorizontalHinge() {
        showOptions(SubTool.TONE_DODGE, widthDp = 700)
        val exposure = composeRule.onNodeWithTag(EditScreenTags.TONE_EXPOSURE).fetchSemanticsNode().layoutBoundsInWindow()
        val y = exposure.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = y, right = 10_000, bottom = y, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
    }
}
