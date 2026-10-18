package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
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
 * 메인 화면의 떠 있는 레이어·조정 패널(결정 기록 '떠 있는 레이어·조정 패널'): 상단 바 버튼으로 열고 닫기, 하나만 뜸,
 * 캔버스를 누르면 닫힘, 뒤로 가기, 자르기, 접히는 선 피하기, 커버의 조정·레이어 탭과 잇기, 끄는 도중 닫혀도 한 번 기록.
 */
@RunWith(AndroidJUnit4::class)
class FloatingPanelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val size = mutableStateOf(MAIN_LANDSCAPE)
    private var homeBacks = 0

    /** 받은 보정 일: 미리 보기 횟수와 기록한 값 */
    private class FakeAdjust : AdjustActions {
        var live: Adjustments? = null
        val committed = mutableListOf<Adjustments>()
        var base = Adjustments.NONE

        override fun preview(change: (Adjustments) -> Adjustments) {
            live = change(live ?: base)
        }

        override fun commit() {
            live?.let { committed += it }
            live = null
        }

        override fun record(change: (Adjustments) -> Adjustments) {
            committed += change(base)
        }
    }

    private val adjust = FakeAdjust()
    private val layerCalls = mutableListOf<String>()
    private val layerActions = object : LayerActions {
        override fun select(id: LayerId) {
            layerCalls += "select ${id.value}"
        }

        override fun add() {
            layerCalls += "add"
        }

        override fun delete() {
            layerCalls += "delete"
        }

        override fun move(id: LayerId, toIndex: Int) {
            layerCalls += "move ${id.value} $toIndex"
        }

        override fun previewOpacity(opacity: Float) {
            layerCalls += "preview"
        }

        override fun commitOpacity() {
            layerCalls += "commitOpacity"
        }

        override fun setBlendMode(mode: BlendMode) = Unit
        override fun duplicate() = Unit
        override fun mergeDown() = Unit
        override fun mergeVisible() = Unit
        override fun setVisible(id: LayerId, visible: Boolean) = Unit
    }

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun ready() = EditorState.Ready(
        PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
        document,
        layers = listOf(
            LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE),
            LayerSummary(LayerId(1), "Layer 1", 1f, BlendMode.NORMAL, Adjustments.NONE)
        ),
        activeLayer = LayerId(1)
    )

    private fun show(screen: DpSize = MAIN_LANDSCAPE) {
        size.value = screen
        val state = ready()
        composeRule.setContent {
            BackHandler { homeBacks++ }
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        EditScreen(
                            state = state,
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {},
                            liveAdjustments = { adjust.live },
                            adjust = adjust,
                            layerActions = layerActions
                        )
                    }
                }
            }
        }
    }

    private fun toggle(panel: MainPanel) = composeRule.onNodeWithTag(EditScreenTags.panelToggle(panel))

    private fun back() {
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun mainScreens_haveNoFixedPanel_andTheButtonsOpenAndClose() {
        show(MAIN_PORTRAIT)
        for (screen in listOf(MAIN_PORTRAIT, MAIN_LANDSCAPE)) {
            composeRule.runOnIdle { size.value = screen }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
            composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertDoesNotExist()

            toggle(MainPanel.LAYERS).performClick()
            composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
            toggle(MainPanel.LAYERS).assertIsOn()
            toggle(MainPanel.LAYERS).performClick()
            composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        }
    }

    @Test
    fun onlyOneFloats_panelsSwitch_andAFlyoutClosesThePanel() {
        show()
        toggle(MainPanel.ADJUST).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()

        toggle(MainPanel.LAYERS).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
        toggle(MainPanel.ADJUST).assertIsOff()

        // 도구 막대에서 도구를 고르면 패널이 닫힌다
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        toggle(MainPanel.ADJUST).performClick()
        // 띠의 칸을 누르면 패널이 닫히고 옵션 창이 뜬다
        composeRule.onNodeWithTag(EditScreenTags.strip("size")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertExists()
    }

    @Test
    fun canvasTouch_closesThePanel_andBackClosesItBeforeGoingHome() {
        show()
        toggle(MainPanel.LAYERS).performClick()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(center)
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()

        toggle(MainPanel.LAYERS).performClick()
        back()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }
        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun layersPanel_sendsItsWork() {
        show(MAIN_PORTRAIT)
        toggle(MainPanel.LAYERS).performClick()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD).performClick()
        composeRule.onNodeWithTag(EditScreenTags.layerRow(LayerId(0))).performClick()

        composeRule.runOnIdle { assertEquals(listOf("add", "select 0"), layerCalls) }
    }

    @Test
    fun closingTheAdjustPanelMidDrag_recordsOnce() {
        show()
        toggle(MainPanel.ADJUST).performClick()
        composeRule.onNodeWithTag(EditScreenTags.adjustSlider(AdjustItem.EXPOSURE)).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(width / 6f, 0f))
        }
        composeRule.runOnIdle { assertTrue("${adjust.live}", adjust.live != null) }

        // 손을 떼지 않은 채 뒤로 가기로 패널이 닫혀도 끌던 값은 한 번 기록한다
        back()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, adjust.committed.size) }
    }

    @Test
    fun crop_closesThePanel_andTurnsOffTheButtons() {
        show()
        toggle(MainPanel.ADJUST).performClick()

        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        toggle(MainPanel.ADJUST).assertIsNotEnabled()
        toggle(MainPanel.LAYERS).assertIsNotEnabled()
    }

    @Test
    fun mainPortrait_panelStaysAboveAHorizontalHinge() {
        show(MAIN_PORTRAIT)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val middle = screen.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = middle, right = screen.right.roundToInt(), bottom = middle, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        toggle(MainPanel.ADJUST).performClick()

        val node = composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).fetchSemanticsNode()
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        assertTrue("${node.layoutBoundsInWindow()} should end above $band", node.layoutBoundsInWindow().bottom <= band.start)
        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun mainLandscape_panelStaysRightOfAVerticalHinge() {
        show(MAIN_LANDSCAPE)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        toggle(MainPanel.LAYERS).performClick()

        val node = composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).fetchSemanticsNode()
        val band = hinge.band(with(node.layoutInfo.density) { HingeMargin.roundToPx() })
        assertTrue("${node.layoutBoundsInWindow()} should be right of $band", node.layoutBoundsInWindow().left >= band.end)
        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun adjustChosenOnTheCover_opensTheAdjustPanelWhenUnfolded_andFoldingBringsTheTabBack() {
        show(COVER)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Adjust)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()

        composeRule.runOnIdle { size.value = MAIN_LANDSCAPE }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertIsDisplayed()
        toggle(MainPanel.ADJUST).assertIsOn()

        composeRule.runOnIdle { size.value = COVER }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()
    }

    @Test
    fun openPanel_survivesStateRestore() {
        val tester = StateRestorationTester(composeRule)
        val state = ready()
        tester.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(MAIN_LANDSCAPE)) {
                StratumTheme {
                    EditScreen(state = state, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
                }
            }
        }
        toggle(MainPanel.LAYERS).performClick()

        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
    }

    @Test
    fun adjustSlider_inThePanel_previewsAndRecords() {
        show(MAIN_PORTRAIT)
        toggle(MainPanel.ADJUST).performClick()

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(hasTestTag(EditScreenTags.adjustSlider(AdjustItem.CONTRAST))))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }

        composeRule.runOnIdle { assertTrue("${adjust.live} ${adjust.committed}", adjust.live != null || adjust.committed.isNotEmpty()) }
    }

    private companion object {
        val COVER = DpSize(411.dp, 800.dp)
        val MAIN_PORTRAIT = DpSize(700.dp, 900.dp)
        val MAIN_LANDSCAPE = DpSize(930.dp, 700.dp)
    }
}
