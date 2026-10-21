package com.kiln.stratum.ui.edit

import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.layout.workBarContentPx
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 편집 화면이 화면 폭마다 맞는 배치를 고르는지, 도구 막대가 접히는 부분(힌지)을 피하는지 확인한다.
 *
 * 화면 크기는 ForcedSize로 정해서 어느 에뮬레이터에서 돌려도 같다(화면보다 크면 밀도를 낮춰 맞춘다).
 * 계속 도는 '여는 중' 표시는 테스트가 끝나기를 기다리다 멈추므로, 빈 편집 화면으로 본다.
 */
@RunWith(AndroidJUnit4::class)
class EditScreenLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 화면이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    private fun showEditScreen(widthDp: Int, heightDp: Int, state: EditorState = EditorState.Empty) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        EditScreen(
                            state = state,
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {}
                        )
                    }
                }
            }
        }
    }

    @Test
    fun compactWidth_showsCanvasAndToolTabs() {
        showEditScreen(411, 800)

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_BAR).assertExists()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertCountEquals(EditTool.tabTools.size)
        composeRule.onNodeWithTag(EditScreenTags.TOOL_RAIL).assertDoesNotExist()
        // 커버는 조정·레이어가 탭이라 상단 바의 패널 버튼이 없다
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).assertDoesNotExist()
    }

    @Test
    fun mediumWidth_showsToolRailAndPanelButtons_withoutAFixedPanel() {
        showEditScreen(700, 900)

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_RAIL).assertExists()
        for (tool in EditTool.railTools) {
            composeRule.onNodeWithTag(EditScreenTags.railTool(tool)).assertExists()
        }
        for (panel in MainPanel.entries) composeRule.onNodeWithTag(EditScreenTags.panelToggle(panel)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_BAR).assertDoesNotExist()
    }

    @Test
    fun expandedWidth_showsToolRailAndPanelButtons_withoutAFixedPanel() {
        showEditScreen(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.CANVAS).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_RAIL).assertExists()
        for (panel in MainPanel.entries) composeRule.onNodeWithTag(EditScreenTags.panelToggle(panel)).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_BAR).assertDoesNotExist()
    }

    @Test
    fun topBarAndToolRail_leaveRoomForTheCanvas() {
        showEditScreen(700, 900)
        val rail = composeRule.onNodeWithTag(EditScreenTags.TOOL_RAIL).fetchSemanticsNode()
        val bar = composeRule.onNodeWithTag(EditScreenTags.TOP_BAR).fetchSemanticsNode()
        val density = rail.layoutInfo.density.density
        val insets = composeRule.runOnIdle { composeRule.activity.window.decorView.rootWindowInsets }
        val statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top
        val safeTop = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        val start = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).left

        // 도구 막대: 버튼 48dp에 양쪽 4dp(56dp), 그리고 옆쪽 시스템 바·카메라 구멍 여백
        assertEquals(56 * density + start, rail.layoutBoundsInWindow().width, 1f)
        // 상단 바: 내용 줄(상태 표시줄 높이, 사용자 피드백 10차)에 상태 표시줄(또는 버튼에 걸리는 카메라 구멍) 높이만 더한다
        val content = workBarContentPx(insets, density)
        val height = bar.layoutBoundsInWindow().height
        assertTrue("top bar $height px (content $content)", height >= content + statusTop - 1f && height <= content + safeTop + 1f)
        // 바 버튼은 가로 48dp, 세로는 바 내용 줄 높이다(사용자 결정)
        val undo = composeRule.onNodeWithTag(EditScreenTags.UNDO).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(48 * density, undo.width, 1f)
        assertEquals(content, undo.height, 1f)
    }

    @Test
    fun toolRail_movesButtonsOffHorizontalHinge() {
        showEditScreen(700, 900)
        val railTags = EditTool.railTools.map { EditScreenTags.railTool(it) }

        // 브러시 버튼 한가운데를 화면 끝에서 끝까지 가로로 지나가는 힌지(두께 0인 선, 완전히 펼친 폴더블)
        val brushCenter = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush))
            .fetchSemanticsNode().layoutBoundsInWindow().center.y.roundToInt()
        val screenWidth = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow().right.roundToInt()
        val hinge = Hinge(left = 0, top = brushCenter, right = screenWidth, bottom = brushCenter, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
        // 비켜 놓아도 도구 순서는 그대로이고, 마지막 도구도 막대 안에 있다
        val bounds = railTags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().layoutBoundsInWindow() }
        assertEquals(bounds.map { it.top }.sorted(), bounds.map { it.top })
        val rail = composeRule.onNodeWithTag(EditScreenTags.TOOL_RAIL).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("${bounds.last()} is outside the rail $rail", bounds.last().bottom <= rail.bottom)
    }

    @Test
    fun largePhotoDialog_avoidsVerticalHinge() {
        // 펼친 폴드의 안쪽 화면처럼 세로 힌지가 가운데를 지날 때, 큰 사진 선택 창의 버튼이 힌지에 놓이지 않는다
        showEditScreen(930, 700, EditorState.AskSize(originalMegapixels = 200, originalAllowed = false, reducedMegapixels = 50))
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
    }
}
