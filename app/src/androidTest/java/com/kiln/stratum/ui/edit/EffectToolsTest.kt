package com.kiln.stratum.ui.edit

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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.effect.EffectKind
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 흐림 도구(흐림·선명·스머지)·모자이크 도구의 옵션·캔버스 끌기와 편집 화면에서 여는 것을 확인한다(엔진 대신 받은 일만 적는 가짜). */
@RunWith(AndroidJUnit4::class)
class EffectToolsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()


    /** 받은 일 */
    private class FakeEffect : EffectActions {
        val calls = mutableListOf<String>()
        val points = mutableListOf<Pair<Float, Float>>()
        var kind: EffectKind? = null

        override fun begin(x: Float, y: Float, kind: EffectKind) {
            calls += "begin"
            points += x to y
            this.kind = kind
        }

        override fun beginSmudge(x: Float, y: Float) {
            calls += "smudge"
            points += x to y
            kind = null
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

    private val actions = FakeEffect()
    private val document = PixelSize(1000, 1000)
    private var blur by mutableStateOf(BlurOptions())
    private var mosaic by mutableStateOf(MosaicOptions())

    /** 커버 한 줄 막대에 흐림의 칸을 놓는다(결정 기록 '커버 화면 개편'). 흐림·선명·스머지의 칸은 같다. */
    private fun showBlurOptions() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme { CoverToolBar(blurBar(BlurControls(blur) { blur = it }), rememberCoverBarState()) }
            }
        }
    }

    private fun showMosaicOptions() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme { CoverToolBar(mosaicBar(MosaicControls(mosaic) { mosaic = it }), rememberCoverBarState()) }
            }
        }
    }

    private fun ready() = EditorState.Ready(
        PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
        document,
        layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
        activeLayer = LayerId(0)
    )

    @Test
    fun blurBar_hasSizeHardnessStrength_andNoSubToolChip() {
        showBlurOptions()

        // 칸은 크기 → 경도 → 강도(띠와 같은 규칙, 사용자 피드백 9·10차). 하위 도구는 탭의 하위 도구 메뉴로만 고른다(2026-10-23).
        val lefts = composeRule.barChipLefts(listOf("size", "hardness", "strength"))
        assertTrue("$lefts should go right", lefts.zipWithNext().all { (a, b) -> a < b })
        composeRule.onNodeWithTag(EditScreenTags.bar("subTool")).assertDoesNotExist()
    }

    @Test
    fun blurOptions_change_andStrengthNeverGoesToZero() {
        showBlurOptions()

        composeRule.expandBarChip("strength")
        composeRule.onNodeWithTag(EditScreenTags.BLUR_STRENGTH).performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        composeRule.collapseBar()
        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.BLUR_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(150f)) }
        composeRule.collapseBar()
        composeRule.expandBarChip("hardness")
        composeRule.onNodeWithTag(EditScreenTags.BLUR_HARDNESS).performSemanticsAction(SemanticsActions.SetProgress) { it(0.7f) }
        composeRule.collapseBar()
        composeRule.runOnIdle {
            assertEquals(0.3f, blur.strength, 0.001f)
            assertEquals(150f, blur.brush.size, 0f)
            assertEquals(0.7f, blur.brush.hardness, 0.001f)
        }

        composeRule.expandBarChip("strength")
        composeRule.onNodeWithTag(EditScreenTags.BLUR_STRENGTH).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.runOnIdle { assertEquals(0.01f, blur.strength, 0.0001f) }
    }

    @Test
    fun mosaicOptions_changeTheCellOnALogScale() {
        showMosaicOptions()

        composeRule.expandBarChip("cell")
        composeRule.onNodeWithTag(EditScreenTags.MOSAIC_CELL).performSemanticsAction(SemanticsActions.SetProgress) { it(cellToFraction(40)) }
        composeRule.runOnIdle { assertEquals(40, mosaic.cell) }
        // 양 끝은 2px·512px
        composeRule.onNodeWithTag(EditScreenTags.MOSAIC_CELL).performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.runOnIdle { assertEquals(2, mosaic.cell) }
        composeRule.onNodeWithTag(EditScreenTags.MOSAIC_CELL).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        composeRule.runOnIdle { assertEquals(512, mosaic.cell) }
    }

    @Test
    fun dragging_sendsTheStrokeWithItsEffect() {
        var kind by mutableStateOf(EffectKind.BLUR)
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 400.dp))) {
                StratumTheme {
                    Box(Modifier.fillMaxSize()) {
                        EffectOverlay(view = { CanvasView.Fit }, document = document, kind = kind, actions = actions, tag = EditScreenTags.BLUR_OVERLAY, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.BLUR_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("begin", "extend", "end"), actions.calls)
            assertEquals(EffectKind.BLUR, actions.kind)
            val (x, y) = actions.points.first()
            assertTrue("$x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
            kind = EffectKind.MOSAIC
        }
        composeRule.onNodeWithTag(EditScreenTags.BLUR_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f))
            up()
        }
        composeRule.runOnIdle { assertEquals(EffectKind.MOSAIC, actions.kind) }
    }

    @Test
    fun dragging_withoutAnEffect_sendsASmudgeStroke() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 400.dp))) {
                StratumTheme {
                    Box(Modifier.fillMaxSize()) {
                        EffectOverlay(view = { CanvasView.Fit }, document = document, kind = null, actions = actions, tag = EditScreenTags.BLUR_OVERLAY, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.BLUR_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }

        composeRule.runOnIdle { assertEquals(listOf("smudge", "extend", "end"), actions.calls) }
    }

    @Test
    fun editScreen_coverTabs_openBlurAndMosaic() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(state = ready(), onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {}, effectActions = actions)
                }
            }
        }

        // 한 줄 막대에 그 도구의 칸이 보인다(결정 기록 '커버 화면 개편')
        composeRule.chooseCoverTool(EditTool.Blur)
        composeRule.onNodeWithTag(EditScreenTags.bar("strength")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.BLUR_OVERLAY).assertExists()

        composeRule.chooseCoverTool(EditTool.Mosaic)
        composeRule.onNodeWithTag(EditScreenTags.bar("cell")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.MOSAIC_OVERLAY).assertExists()
    }

    @Test
    fun editScreen_mainScreen_showsTheStripCells() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(900.dp, 700.dp))) {
                StratumTheme {
                    EditScreen(state = ready(), onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {}, effectActions = actions)
                }
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Blur)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.strip("strength")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Mosaic)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.strip("cell")).assertIsDisplayed()
    }

}
