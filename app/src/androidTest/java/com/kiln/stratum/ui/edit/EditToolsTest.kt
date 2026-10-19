package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.FlipImage
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 편집 도구를 고르는 일(누르기, 길게 눌러 하위 도구 메뉴, 화면 읽기의 길게 누르기)과
 * 자르기·변형 옵션의 자리·회전 버튼을 세 배치에서 확인한다.
 */
@RunWith(AndroidJUnit4::class)
class EditToolsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 화면이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

    /** 받은 편집 명령 */
    private val commands = mutableListOf<EditCommand>()

    private fun ready(busy: Boolean = false): EditorState.Ready {
        val document = PixelSize(400, 300)
        val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document, busy = busy)
    }

    private fun show(widthDp: Int, heightDp: Int, state: EditorState = ready()) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        EditScreen(
                            state = state,
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {},
                            onEdit = { commands += it }
                        )
                    }
                }
            }
        }
    }

    private val transformTab get() = composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Transform))
    private val transformRail get() = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform))
    private val brushTab get() = composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Brush))
    private val selectTab get() = composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Select))

    @Test
    fun compact_tapShowsOptions_tapAgainHides() {
        show(411, 800)

        brushTab.performClick()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_OPTIONS).assertIsDisplayed()

        brushTab.performClick()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_OPTIONS).assertDoesNotExist()
    }

    @Test
    fun compact_transformStartsWithCrop_inItsOwnWindow() {
        show(411, 800)

        transformTab.performClick()

        // 처음에는 자르기부터 시작하고, 닫힌 화면에서 자르기는 새 창이다(결정 기록 '닫힌 화면 새 창')
        composeRule.onNodeWithTag(EditScreenTags.COVER_CROP_OPTIONS).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.CROP)).assertIsSelected()
    }

    @Test
    fun compact_longPressOpensMenu_withoutPuttingTheToolDown() {
        show(411, 800)
        selectTab.performClick()

        selectTab.performTouchInput { longClick() }

        // 길게 누른 뒤에 클릭이 일어났다면 도구가 내려놓여 옵션이 사라졌을 것이다
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OPTIONS).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.SELECT_RECT)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.SELECT_LASSO)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.SELECT_LASSO)).assertIsSelected()
    }

    @Test
    fun accessibilityLongClick_opensMenu() {
        show(411, 800)

        transformTab.performSemanticsAction(SemanticsActions.OnLongClick)

        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.ROTATE)).assertIsDisplayed()
    }

    @Test
    fun rotateButtons_sendCommands() {
        show(411, 800)
        transformTab.performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.ROTATE)).performClick()

        for (tag in listOf(
            EditScreenTags.ROTATE_LEFT,
            EditScreenTags.ROTATE_RIGHT,
            EditScreenTags.FLIP_HORIZONTAL,
            EditScreenTags.FLIP_VERTICAL
        )) {
            composeRule.onNodeWithTag(tag).performClick()
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf(RotateImage(clockwise = false), RotateImage(clockwise = true), FlipImage(horizontal = true), FlipImage(horizontal = false)),
                commands
            )
        }
    }

    @Test
    fun busy_disablesRotateButtons() {
        show(411, 800, ready(busy = true))
        transformTab.performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.ROTATE)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.ROTATE_LEFT).assertIsNotEnabled()
    }

    @Test
    fun noDocument_disablesTools() {
        show(411, 800, EditorState.Empty)

        transformTab.assertIsNotEnabled()
    }

    @Test
    fun medium_railLongPress_choosesTheSubTool_andTheStripShowsItsButtons() {
        show(700, 900)

        transformRail.performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.ROTATE)).performClick()

        // 메인 화면의 옵션은 도구 막대 오른쪽 띠에 있다(결정 기록 '편집 화면 도구 배치')
        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_LEFT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
            .assertIsDisplayed()
    }

    @Test
    fun main_subToolCell_choosesTheSubTool_andResizeOpensTheWindow() {
        show(930, 700)
        transformRail.performClick()

        chooseSubTool(SubTool.ROTATE)
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.ROTATE_LEFT).performClick()
        composeRule.runOnIdle { assertEquals(listOf<EditCommand>(RotateImage(clockwise = false)), commands) }

        chooseSubTool(SubTool.RESIZE)
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_WIDTH).assertExists()
    }

    /** 메인 화면: 띠의 하위 도구 칸을 눌러 연 목록에서 [subTool]을 고른다 */
    private fun chooseSubTool(subTool: SubTool) {
        composeRule.onNodeWithTag(EditScreenTags.strip("subTool")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(subTool)).performClick()
    }

    @Test
    fun medium_optionsMoveOffHorizontalHinge() {
        show(700, 900)
        transformRail.performClick()
        chooseSubTool(SubTool.ROTATE)
        // 회전 버튼 줄 한가운데를 가로로 지나가는 힌지
        val rowCenter = composeRule.onNodeWithTag(EditScreenTags.ROTATE_LEFT)
            .fetchSemanticsNode().layoutBoundsInWindow().center.y.roundToInt()
        val screenWidth = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow().right.roundToInt()
        val hinge = Hinge(left = 0, top = rowCenter, right = screenWidth, bottom = rowCenter, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun expanded_stripAndSubToolList_avoidVerticalHinge() {
        show(930, 700)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        transformRail.performClick()
        composeRule.onNodeWithTag(EditScreenTags.strip("subTool")).performClick()
        composeRule.assertClickablesAvoid(hinge)
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.ROTATE)).performClick()

        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_LEFT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
            .assertIsDisplayed()
        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun selection_survivesStateRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            StratumTheme {
                EditScreen(state = ready(), onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {})
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.ROTATE)).assertDoesNotExist()
        // 지금 배치에 맞는 도구 버튼(커버는 탭, 메인은 막대)을 눌러 회전·뒤집기를 고른다
        val tab = composeRule.onAllNodesWithTagOrNull(EditScreenTags.tabTool(EditTool.Transform))
        (tab ?: transformRail).performClick()
        // 메인 화면은 띠의 하위 도구 칸을 눌러 연 목록에서 고른다
        if (tab == null) composeRule.onNodeWithTag(EditScreenTags.strip("subTool")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.ROTATE)).performClick()

        // 접기·펼치기나 백그라운드 종료로 화면이 다시 만들어지는 것처럼 저장했다가 되살린다
        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(EditScreenTags.ROTATE_LEFT).assertIsDisplayed()
    }

    /** [tag] 노드가 있으면 그 노드, 없으면 null. */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagOrNull(tag: String) =
        onNodeWithTag(tag).takeIf { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
}
