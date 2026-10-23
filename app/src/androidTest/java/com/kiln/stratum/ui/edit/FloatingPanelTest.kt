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
import androidx.compose.ui.test.assertIsSelected
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
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 메인 화면의 떠 있는 레이어·조정 패널(결정 기록 '떠 있는 레이어·조정 패널'): 상단 바 버튼으로 열고 닫기, 패널은 하나만 뜸,
 * 버튼으로 끄기 전까지 남음(캔버스 터치·도구·옵션 창·뒤로 가기·창), 자르기 중에는 숨김, 접히는 선은 보지 않음(사용자 피드백 11차 1),
 * 커버의 조정·레이어 탭과 잇기, 끄는 도중 닫혀도 한 번 기록.
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
    fun onlyOnePanel_switches_andToolsAndFlyoutsKeepIt() {
        show()
        toggle(MainPanel.ADJUST).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()

        // 패널은 하나만: 다른 쪽 버튼을 누르면 그 패널로 바뀐다(사용자 결정 '하나씩')
        toggle(MainPanel.LAYERS).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
        toggle(MainPanel.ADJUST).assertIsOff()

        // 도구 막대에서 도구를 골라도, 띠의 칸으로 옵션 창을 열어도 패널은 남는다(직접 끄기 전까지 유지)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.strip("size")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()

        // 패널 버튼을 누르면 옵션 창은 닫히고(다른 곳을 눌렀으므로) 패널이 바뀐다
        toggle(MainPanel.ADJUST).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()
        toggle(MainPanel.ADJUST).performClick()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
    }

    @Test
    fun canvasTouchBackAndDialogs_keepThePanel() {
        show()
        toggle(MainPanel.LAYERS).performClick()
        // 패널만 열려 있으면 캔버스를 덮는 바탕이 없어 캔버스를 바로 쓴다
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_DISMISS).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(center)
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()

        // 창(내보내기)이 떴다 닫혀도 남는다
        composeRule.onNodeWithTag(EditScreenTags.EXPORT).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).assertExists()
        back()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()

        // 뒤로 가기는 패널을 닫지 않는다(사용자 결정): 고른 도구를 닫은 뒤 홈으로 간다
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        back()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }
        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
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

        // 손을 떼지 않은 채 상단 바 버튼으로 패널이 닫혀도 끌던 값은 한 번 기록한다(끄는 손가락은 그대로라 버튼은 화면 읽기 동작으로 누른다)
        toggle(MainPanel.ADJUST).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, adjust.committed.size) }
    }

    @Test
    fun crop_hidesThePanel_turnsOffTheButtons_andItComesBack() {
        show()
        toggle(MainPanel.ADJUST).performClick()

        // 자르기 중에는 조정·레이어를 막으므로 패널을 숨기고 버튼을 끈다
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        toggle(MainPanel.ADJUST).assertIsNotEnabled()
        toggle(MainPanel.LAYERS).assertIsNotEnabled()

        // 자르기가 끝나면(뒤로 가기로 도구를 닫음) 끄지 않은 패널이 다시 보인다
        back()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()
        toggle(MainPanel.ADJUST).assertIsOn()
    }

    @Test
    fun foldingWithAPanelAndATool_keepsBoth() {
        show(MAIN_LANDSCAPE)
        toggle(MainPanel.LAYERS).performClick()
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()

        // 조정·레이어의 켬·끔은 커버와 메인 화면이 같은 상태다(결정 기록 '커버 화면 개편'): 커버에서도 레이어가 보이고 버튼이 켜져 있으며,
        // 고른 도구(브러시)는 그대로다. 다시 펼치면 패널과 띠가 그대로 보인다.
        composeRule.runOnIdle { size.value = COVER }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
        toggle(MainPanel.LAYERS).assertIsOn()
        // 브러시는 커버 더보기 줄의 도구라 더보기 칸이 브러시로 켜진다(2026-10-23)
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).assertIsSelected()

        composeRule.runOnIdle { size.value = MAIN_LANDSCAPE }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertIsDisplayed()
    }

    @Test
    fun mainPortrait_aHorizontalHinge_doesNotShortenThePanel() {
        // 사용자 피드백 11차 1: 접히는 선을 비키지 않는다(전에는 메인 세로에서 선 위쪽 절반까지만 썼다)
        show(MAIN_PORTRAIT)
        toggle(MainPanel.ADJUST).performClick()
        val before = composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).fetchSemanticsNode().layoutBoundsInWindow()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val middle = screen.center.y.roundToInt()

        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = 0, top = middle, right = screen.right.roundToInt(), bottom = middle, isVertical = false)) }

        val after = composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(before, after)
        assertTrue("$after should reach past the middle $middle", after.bottom > middle)
    }

    @Test
    fun mainLandscape_aVerticalHinge_doesNotMoveThePanel() {
        show(MAIN_LANDSCAPE)
        toggle(MainPanel.LAYERS).performClick()
        val before = composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).fetchSemanticsNode().layoutBoundsInWindow()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val x = before.center.x.roundToInt()

        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = x, top = 0, right = x, bottom = screen.bottom.roundToInt(), isVertical = true)) }

        assertEquals(before, composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).fetchSemanticsNode().layoutBoundsInWindow())
    }

    @Test
    fun adjustOpenedOnTheCover_staysOpenWhenUnfoldedAndFolded() {
        show(COVER)
        // 커버도 상단 바의 조정 버튼으로 연다(결정 기록 '커버 화면 개편')
        composeRule.togglePanel(MainPanel.ADJUST)
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertIsDisplayed()
        toggle(MainPanel.ADJUST).assertIsOn()

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
