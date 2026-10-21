package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        // 처음에는 자르기부터 시작하고, 닫힌 화면에서 자르기·회전은 새 창이다(결정 기록 '닫힌 화면 새 창')
        composeRule.onNodeWithTag(EditScreenTags.COVER_CROP_OPTIONS).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.CROP)).assertIsSelected()
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

        // 자르기·변형의 하위 도구는 자르기·회전과 크기 변경이다(회전·뒤집기는 자르기와 한 화면, 사용자 피드백 8차 1)
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.CROP)).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.RESIZE)).assertIsDisplayed()
    }

    @Test
    fun rotateButtons_onlyTurnThePreview_andCheckAppliesThemWithTheCrop() {
        show(411, 800)
        transformTab.performClick()
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).performClick()

        // 사용자 피드백 8차 1: 누르는 동안은 보이는 사진만 돌리고 편집하지 않는다
        for (tag in listOf(
            EditScreenTags.ROTATE_LEFT,
            EditScreenTags.ROTATE_RIGHT,
            EditScreenTags.FLIP_HORIZONTAL,
            EditScreenTags.FLIP_VERTICAL
        )) {
            composeRule.onNodeWithTag(tag).performClick()
        }
        composeRule.runOnIdle { assertTrue(commands.isEmpty()) }

        // ✓를 누르면 자르기와 함께 실행 취소 한 단계로 보낸다(왼쪽·오른쪽 90°는 서로 지우고, 좌우·상하 뒤집기는 180°)
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf<EditCommand>(CropImage(PixelRect(0, 0, 400, 300), PixelSize(400, 300), Orientation.ROTATE_180)), commands)
        }
    }

    @Test
    fun busy_disablesRotateButtons() {
        show(411, 800, ready(busy = true))
        transformTab.performClick()
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).performClick()

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
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.CROP)).performClick()

        // 메인 화면의 옵션은 띠에 있다. 자르기·회전의 띠에는 돌리기 버튼도 있다(사용자 피드백 8차 1)
        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_LEFT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
            .assertIsDisplayed()
    }

    @Test
    fun main_tappingTheToolAgain_opensTheSubToolMenu_andResizeOpensTheWindow() {
        show(930, 700)
        transformRail.performClick()

        // 사용자 요청: 고른 도구를 한 번 더 누르면 하위 도구 메뉴가 뜬다
        transformRail.performClick()
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.CROP)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertDoesNotExist()
        // 띠의 돌리기 버튼은 보이는 사진만 돌린다(✓를 누를 때 적용)
        composeRule.onNodeWithTag(EditScreenTags.ROTATE_LEFT).performClick()
        composeRule.runOnIdle { assertTrue(commands.isEmpty()) }

        // 돌려 둔 채 크기 변경을 고르면 먼저 적용하거나 취소하라고 알리고 창은 띄우지 않는다(크기를 바꾸면 돌린 것이 사라지므로)
        chooseSubTool(SubTool.RESIZE)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.crop_resize_first), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_WIDTH).assertDoesNotExist()

        // 취소한 뒤에는 크기 변경 창이 뜬다
        composeRule.onNodeWithTag(EditScreenTags.CROP_CANCEL).performClick()
        transformRail.performClick()
        chooseSubTool(SubTool.RESIZE)
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_WIDTH).assertExists()
        // 메뉴에서 고르는 하위 도구는 띠에 두지 않는다(사용자 요청)
        composeRule.onNodeWithTag(EditScreenTags.strip("subTool")).assertDoesNotExist()
    }

    /** 메인 화면: 고른 자르기·변형 도구를 다시 눌러 연 하위 도구 메뉴에서 [subTool]을 고른다 */
    private fun chooseSubTool(subTool: SubTool) {
        transformRail.performClick()
        composeRule.onNodeWithTag(EditScreenTags.subTool(subTool)).performClick()
    }

    @Test
    fun main_holdThenDragOntoAnItem_choosesItWhenTheFingerLifts() {
        show(930, 700)
        val rail = transformRail.fetchSemanticsNode().layoutBoundsInWindow()
        transformRail.performTouchInput {
            down(center)
            // 메뉴 묶음 시간(0.3초)보다 오래 누른다
            advanceEventTime(400)
            moveBy(Offset(0f, 1f))
        }
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertIsDisplayed()
        val item = composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.CROP)).fetchSemanticsNode().layoutBoundsInWindow()

        // 사용자 요청: 손가락을 떼지 않고 항목 위로 끌고 가서 떼면 그 항목을 고른다
        transformRail.performTouchInput {
            moveTo(item.center - rail.topLeft)
            up()
        }

        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertDoesNotExist()
        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_LEFT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
            .assertIsDisplayed()
    }

    @Test
    fun main_releasingOffTheItems_keepsTheMenuOpen_andTappingOutsideClosesIt() {
        show(930, 700)
        transformRail.performTouchInput {
            down(center)
            advanceEventTime(400)
            moveBy(Offset(0f, 2f))
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.CROP)).assertIsSelected()

        // 메뉴 밖을 누르면 닫히기만 한다(그 터치는 캔버스로 가지 않는다)
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU_DISMISS).performTouchInput { click(Offset(width * 0.8f, height * 0.8f)) }
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(emptyList<EditCommand>(), commands) }
    }

    @Test
    fun menuLongPress_opensAfterThreeTenthsOfASecond() {
        show(930, 700)
        val selectRail = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select))
        val retouchRail = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Retouch))

        // 0.3초보다 짧게 누르면 그냥 누르기(도구를 고름)
        selectRail.performTouchInput {
            down(center)
            advanceEventTime(250)
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertDoesNotExist()
        // 사용자 요청: 메뉴 묶음은 0.3초(휴대폰 설정 0.4초보다 빠름). 고르지 않은 도구라 다시 누르기로 열린 것이 아니다.
        retouchRail.performTouchInput {
            down(center)
            advanceEventTime(320)
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.RETOUCH_SPOT)).assertIsDisplayed()
    }

    @Test
    fun compact_tappingTheToolAgain_opensTheMenuAboveTheTab() {
        show(411, 800)
        selectTab.performClick()
        selectTab.performClick()

        val menu = composeRule.onNodeWithTag(EditScreenTags.SUB_TOOL_MENU).fetchSemanticsNode().layoutBoundsInWindow()
        val tab = selectTab.fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("menu $menu should be above $tab", menu.bottom <= tab.top + 1f)
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.SELECT_LASSO)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.SELECT_LASSO)).assertIsSelected()
    }

    @Test
    fun expanded_subToolMenuChoosesCrop_andTheStripShowsTheRotateCells() {
        show(930, 700)
        transformRail.performClick()
        transformRail.performClick()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.CROP)).performClick()

        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_LEFT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
            .assertIsDisplayed()
    }

    @Test
    fun selection_survivesStateRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            StratumTheme {
                EditScreen(state = ready(), onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {}, onEdit = { commands += it })
            }
        }
        // 지금 배치에 맞는 도구 버튼(커버는 탭, 메인은 막대)을 눌러 자르기·회전을 시작하고, 커버는 회전 탭을 연다
        val tab = composeRule.onAllNodesWithTagOrNull(EditScreenTags.tabTool(EditTool.Transform))
        (tab ?: transformRail).performClick()
        if (tab != null) composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).performClick()
        // 오른쪽으로 돌려 둔다(아직 적용하지 않음)
        composeRule.onNodeWithTag(EditScreenTags.ROTATE_RIGHT).performClick()

        // 접기·펼치기나 백그라운드 종료로 화면이 다시 만들어지는 것처럼 저장했다가 되살린다
        tester.emulateSavedInstanceStateRestore()

        // 고른 도구·탭과 돌린 방향이 남아, 적용하면 함께 보낸다
        composeRule.onNodeWithTag(EditScreenTags.ROTATE_LEFT).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf<EditCommand>(CropImage(PixelRect(0, 0, 400, 300), PixelSize(400, 300), Orientation.ROTATE_CLOCKWISE)), commands)
        }
    }

    /** [tag] 노드가 있으면 그 노드, 없으면 null. */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagOrNull(tag: String) =
        onNodeWithTag(tag).takeIf { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
}
