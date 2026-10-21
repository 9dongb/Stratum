package com.kiln.stratum.ui.edit

import androidx.compose.ui.test.onNodeWithText
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
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

    /** 커버 한 줄 막대에 명암의 칸을 놓는다(결정 기록 '커버 화면 개편') */
    private fun showBar(mode: SubTool, onChoose: (SubTool) -> Unit = {}) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme { CoverToolBar(toneBar(mode, ToneControls(options) { options = it }, onChoose), rememberCoverBarState()) }
            }
        }
    }

    @Test
    fun bar_hasSubToolRangeSizeHardnessExposure_inOrder_withoutAHelpLine() {
        val chosen = mutableListOf<SubTool>()
        showBar(SubTool.TONE_BURN) { chosen += it }

        // 칸은 하위 도구 → 범위 → 크기 → 경도 → 노출(강도 자리) 순서다(띠와 같은 규칙, 사용자 피드백 9·10차)
        val lefts = composeRule.barChipLefts(listOf("subTool", "range", "size", "hardness", "exposure"))
        assertTrue("$lefts should go right", lefts.zipWithNext().all { (a, b) -> a < b })
        // 설명 줄이 없다(10차 5: 안내는 처음 고를 때 편집 화면의 알림)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.tone_hint_burn)).assertDoesNotExist()
        // 하위 도구 칩을 펼치면 닷지·번이 보이고 지금 하위 도구가 골라져 있다. 고르면 알리고 칩 줄로 돌아온다.
        composeRule.expandBarChip("subTool")
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.TONE_BURN)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.TONE_DODGE)).performClick()
        composeRule.runOnIdle { assertEquals(listOf(SubTool.TONE_DODGE), chosen) }
        composeRule.onNodeWithTag(EditScreenTags.bar("range")).assertExists()
    }

    @Test
    fun bar_changesTheOptions() {
        showBar(SubTool.TONE_DODGE)

        composeRule.expandBarChip("range")
        composeRule.onNodeWithTag(EditScreenTags.toneRange(ToneRange.MIDTONES)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.toneRange(ToneRange.SHADOWS)).performClick()
        composeRule.expandBarChip("exposure")
        composeRule.onNodeWithTag(EditScreenTags.TONE_EXPOSURE).performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        composeRule.collapseBar()
        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.TONE_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(150f)) }
        composeRule.collapseBar()
        composeRule.expandBarChip("hardness")
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
        showBar(SubTool.TONE_DODGE)

        composeRule.expandBarChip("exposure")
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

        composeRule.chooseCoverTool(EditTool.Tone)

        // 한 줄 막대에 명암의 칸이 보인다(결정 기록 '커버 화면 개편')
        composeRule.onNodeWithTag(EditScreenTags.bar("range")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.TONE_OVERLAY).assertExists()
        // 처음 고르면 안내 글이 알림으로 뜬다(사용자 피드백 10차 5)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.tone_hint_dodge))
    }

}
