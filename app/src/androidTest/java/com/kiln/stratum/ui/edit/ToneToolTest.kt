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
    private fun showBar(mode: SubTool) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme { CoverToolBar(toneBar(mode, ToneControls(options) { options = it }), rememberCoverBarState()) }
            }
        }
    }

    @Test
    fun bar_hasRangeSizeHardnessExposure_inOrder_withoutASubToolChipOrHelpLine() {
        showBar(SubTool.TONE_BURN)

        // 칸은 범위 → 크기 → 경도 → 노출(강도 자리) 순서다(띠와 같은 규칙, 사용자 피드백 9·10차)
        val lefts = composeRule.barChipLefts(listOf("range", "size", "hardness", "exposure"))
        assertTrue("$lefts should go right", lefts.zipWithNext().all { (a, b) -> a < b })
        // 하위 도구(닷지·번)는 막대에 두지 않고 더보기 줄의 하위 도구 메뉴로 고른다(2026-10-23 사용자 요청)
        composeRule.onNodeWithTag(EditScreenTags.bar("subTool")).assertDoesNotExist()
        // 설명 줄이 없다(10차 5: 안내는 처음 고를 때 편집 화면의 알림)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.tone_hint_burn)).assertDoesNotExist()
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

    /** 커버 편집 화면에 명암 도구를 고르고, 범위는 [options]로 들고 바꾼다 */
    private fun showCoverEditorWithTone() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {},
                        toneActions = actions, toneOptions = options, onToneOptions = { options = it }
                    )
                }
            }
        }
        composeRule.chooseCoverTool(EditTool.Tone)
    }

    @Test
    fun cover_rangeChip_holdThenDrag_picksTheRange() {
        showCoverEditorWithTone()
        val chip = composeRule.onNodeWithTag(EditScreenTags.bar("range"))
        val chipBounds = chip.fetchSemanticsNode().layoutBoundsInWindow()
        // 사용자 요청 'C'(2026-10-23): 고르기 칩을 꾹 누르면 칩 위에 고를 것 목록이 뜬다
        chip.performTouchInput {
            down(center)
            advanceEventTime(400)
            moveBy(Offset(0f, -1f))
        }
        val menu = composeRule.onNodeWithTag(EditScreenTags.CHIP_MENU).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("menu $menu should be above $chipBounds", menu.bottom <= chipBounds.top + 1f)
        composeRule.onNodeWithTag(EditScreenTags.chipMenu(EditScreenTags.toneRange(ToneRange.MIDTONES))).assertIsSelected()
        val highlights = composeRule.onNodeWithTag(EditScreenTags.chipMenu(EditScreenTags.toneRange(ToneRange.HIGHLIGHTS)))
            .fetchSemanticsNode().layoutBoundsInWindow()

        // 누른 채 항목 위로 끌고 가서 떼면 그것을 고르고 목록이 닫힌다(고를 것 줄은 펼치지 않는다)
        chip.performTouchInput {
            moveTo(highlights.center - chipBounds.topLeft)
            up()
        }
        composeRule.runOnIdle { assertEquals(ToneRange.HIGHLIGHTS, options.range) }
        composeRule.onNodeWithTag(EditScreenTags.CHIP_MENU).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.toneRange(ToneRange.SHADOWS)).assertDoesNotExist()
    }

    @Test
    fun cover_rangeChip_releasingOffTheList_keepsItOpen_andTappingAnItemPicksIt() {
        showCoverEditorWithTone()
        composeRule.onNodeWithTag(EditScreenTags.bar("range")).performTouchInput {
            down(center)
            advanceEventTime(400)
            moveBy(Offset(0f, 2f))
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.CHIP_MENU).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.chipMenu(EditScreenTags.toneRange(ToneRange.SHADOWS))).performClick()
        composeRule.runOnIdle { assertEquals(ToneRange.SHADOWS, options.range) }
        composeRule.onNodeWithTag(EditScreenTags.CHIP_MENU).assertDoesNotExist()

        // 화면 읽기 기능의 길게 누르기로도 열리고, 밖을 누르면 닫히기만 한다
        composeRule.onNodeWithTag(EditScreenTags.bar("range")).performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag(EditScreenTags.CHIP_MENU).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.CHIP_MENU_DISMISS).performTouchInput { click(Offset(width * 0.5f, height * 0.3f)) }
        composeRule.onNodeWithTag(EditScreenTags.CHIP_MENU).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(ToneRange.SHADOWS, options.range) }
    }

}
