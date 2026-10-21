package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.ui.layout.Hinge
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

/** 편집 화면에서 자르기를 시작하고, 비율을 고르고, 적용·취소하는 것을 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditCropTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(400, 300)
    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val commands = mutableListOf<EditCommand>()

    private fun ready(canUndo: Boolean = false): EditorState.Ready {
        val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document, canUndo = canUndo, canRedo = canUndo)
    }

    private fun show(widthDp: Int = 411, heightDp: Int = 800, canUndo: Boolean = false) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        EditScreen(
                            state = ready(canUndo),
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

    private fun startCropOnCover() {
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Transform)).performClick()
    }

    private fun chooseRatio(ratio: CropRatio) {
        composeRule.onNodeWithTag(EditScreenTags.cropRatio(ratio)).performScrollTo().performClick()
    }

    @Test
    fun startingCrop_showsTheFrame_andHidesTheZoomChip() {
        show()

        startCropOnCover()

        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()
        // 사진 전체를 덮은 테두리는 적용해도 바뀌는 것이 없다
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsNotEnabled()
    }

    @Test
    fun squareRatio_thenApply_sendsASquareCropAndEndsCropping() {
        show()
        startCropOnCover()

        chooseRatio(CropRatio.SQUARE)
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf(CropImage(PixelRect(50, 0, 350, 300), document)), commands) }
        composeRule.onNodeWithTag(EditScreenTags.TOOL_OPTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertDoesNotExist()
    }

    @Test
    fun threeToFourRatio_sendsAPortraitCrop() {
        show()
        startCropOnCover()

        chooseRatio(CropRatio.PHOTO_3_4)
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsEnabled().performClick()

        // 4:3 사진에서 세로 300에 맞춘 3:4(폭 225)
        composeRule.runOnIdle { assertEquals(listOf(CropImage(PixelRect(88, 0, 313, 300), document)), commands) }
    }

    @Test
    fun swap_turnsTheRatio() {
        show()
        startCropOnCover()

        chooseRatio(CropRatio.WIDE_16_9)
        composeRule.onNodeWithTag(EditScreenTags.CROP_SWAP).performClick()
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()

        // 9:16으로 세로 300에 맞춘다(폭 168.75를 반올림)
        composeRule.runOnIdle {
            val rect = (commands.single() as CropImage).rect
            assertEquals(169, rect.width)
            assertEquals(300, rect.height)
        }
    }

    @Test
    fun cover_cropIsANewWindow_xBottomLeft_checkBottomRight_andBackIsX() {
        // 닫힌 화면 새 창 공통 규칙(결정 기록 '닫힌 화면 새 창'): 위는 이름 줄, 탭 줄은 숨기고, 맨 아래 왼쪽 X·오른쪽 ✓
        show()
        startCropOnCover()
        composeRule.onNodeWithTag(EditScreenTags.COVER_WINDOW_TOP_BAR).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_ROW).assertDoesNotExist()

        val options = composeRule.onNodeWithTag(EditScreenTags.COVER_CROP_OPTIONS).fetchSemanticsNode().layoutBoundsInWindow()
        val cancel = composeRule.onNodeWithTag(EditScreenTags.CROP_CANCEL).fetchSemanticsNode().layoutBoundsInWindow()
        val apply = composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$cancel / $apply should be below $options", cancel.top >= options.bottom && apply.top >= options.bottom)
        assertTrue("$cancel should be at the left", cancel.center.x < options.left + options.width / 4)
        assertTrue("$apply should be at the right", apply.center.x > options.right - options.width / 4)

        // 뒤로 가기는 X와 같다: 자르기를 닫고 탭 줄이 돌아온다
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(EditScreenTags.COVER_WINDOW_TOP_BAR).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_ROW).assertExists()
        composeRule.runOnIdle { assertTrue(commands.isEmpty()) }
    }

    @Test
    fun cover_bottomBar_putsTheTabsBetweenXAndCheck_andResizeNeedsNoChanges() {
        // 사용자 피드백 8차 1-1: 고르는 탭은 맨 아래 X와 ✓ 사이
        show()
        startCropOnCover()
        val cancel = bounds(EditScreenTags.CROP_CANCEL)
        val cropTab = bounds(EditScreenTags.cropTab(CropTab.CROP))
        val rotateTab = bounds(EditScreenTags.cropTab(CropTab.ROTATE))
        val resize = bounds(EditScreenTags.subToolOption(SubTool.RESIZE))
        val apply = bounds(EditScreenTags.CROP_APPLY)
        val row = listOf(cancel, cropTab, rotateTab, resize, apply)
        for (item in row) assertEquals("$item", cancel.center.y, item.center.y, cancel.height / 2)
        // 분할 버튼의 두 칸은 테두리를 겹쳐 붙이므로(M3) 가운데 순서만 보고, 따로인 버튼끼리는 겹치지 않는지 본다
        for ((left, right) in row.zipWithNext()) assertTrue("$left should be left of $right", left.center.x < right.center.x)
        for ((left, right) in listOf(cancel to cropTab, rotateTab to resize, resize to apply)) {
            assertTrue("$left should end before $right", left.right <= right.left + 1f)
        }

        // 크기 변경은 바꾼 것이 없을 때만 누를 수 있다(크기를 바꾸면 고르던 테두리가 사라지므로)
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RESIZE)).assertIsEnabled()
        chooseRatio(CropRatio.SQUARE)
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RESIZE)).assertIsNotEnabled()
    }

    @Test
    fun cover_rotateTab_turnsOnlyThePreview_andCheckAppliesItWithTheCrop() {
        show()
        startCropOnCover()
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ROTATE_RIGHT).performClick()
        composeRule.runOnIdle { assertTrue(commands.isEmpty()) }
        // 돌렸으니 테두리가 사진 전체여도 적용할 것이 있다
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsEnabled()

        // 돌린 사진(300×400)에서 1:1을 고르면 가운데 300×300이고, 원래 사진으로는 가운데 (50, 0)~(350, 300)이다
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.CROP)).performClick()
        chooseRatio(CropRatio.SQUARE)
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()

        composeRule.runOnIdle { assertEquals(listOf(CropImage(PixelRect(50, 0, 350, 300), document, Orientation.ROTATE_CLOCKWISE)), commands) }
    }

    @Test
    fun cover_x_dropsTheTurnToo() {
        show()
        startCropOnCover()
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.FLIP_HORIZONTAL).performClick()

        composeRule.onNodeWithTag(EditScreenTags.CROP_CANCEL).performClick()
        composeRule.runOnIdle { assertTrue(commands.isEmpty()) }

        // 다시 열면 뒤집기 전 그대로라 적용할 것이 없다
        startCropOnCover()
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsNotEnabled()
    }

    @Test
    fun main_undoIsOff_whileCropping() {
        // 아직 적용하지 않은 테두리·방향을 모르고 잃지 않게 자르는 동안은 실행 취소·다시 실행을 막는다
        // (커버의 자르기·회전 창은 상단 바 대신 이름 줄이라 버튼이 없다)
        show(930, 700, canUndo = true)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.UNDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.REDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.CROP_CANCEL).performClick()
        composeRule.onNodeWithTag(EditScreenTags.UNDO).assertIsEnabled()
    }

    @Test
    fun main_stripTurns_thenAppliesWithTheCrop() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()

        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_RIGHT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).performClick()
        composeRule.runOnIdle { assertTrue(commands.isEmpty()) }
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()

        composeRule.runOnIdle { assertEquals(listOf(CropImage(PixelRect.of(document), document, Orientation.ROTATE_CLOCKWISE)), commands) }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()

    @Test
    fun cancel_endsCroppingWithoutEditing() {
        show()
        startCropOnCover()
        chooseRatio(CropRatio.SQUARE)

        composeRule.onNodeWithTag(EditScreenTags.CROP_CANCEL).performClick()

        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(commands.isEmpty()) }
    }

    @Test
    fun frame_survivesStateRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            StratumTheme {
                EditScreen(
                    state = ready(),
                    onBack = {},
                    onCanvasSize = { _, _ -> },
                    onViewport = {},
                    onChooseSize = {},
                    onEdit = { commands += it }
                )
            }
        }
        // 지금 배치에 맞는 도구 버튼(커버는 탭, 메인은 막대)
        val tabExists = composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(EditScreenTags.tabTool(EditTool.Transform)))
            .fetchSemanticsNodes().isNotEmpty()
        composeRule.onNodeWithTag(
            if (tabExists) EditScreenTags.tabTool(EditTool.Transform) else EditScreenTags.railTool(EditTool.Transform)
        ).performClick()
        // 메인 화면은 띠의 비율 칸을 눌러야 비율 칩이 뜬다(밀어 보지 않는 옵션 창이라 바로 누른다, 펼친 에뮬레이터에서도 돌게)
        if (tabExists) {
            chooseRatio(CropRatio.SQUARE)
        } else {
            composeRule.onNodeWithTag(EditScreenTags.strip("ratio")).performClick()
            composeRule.onNodeWithTag(EditScreenTags.cropRatio(CropRatio.SQUARE)).performClick()
        }

        // 접기·펼치기나 백그라운드 종료로 화면이 다시 만들어지는 것처럼 저장했다가 되살린다
        tester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()

        composeRule.runOnIdle { assertEquals(listOf(CropImage(PixelRect(50, 0, 350, 300), document)), commands) }
    }

    @Test
    fun medium_cropButtonsAvoidHorizontalHinge() {
        show(700, 900)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.CROP_APPLY) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).assertIsDisplayed()
        // 적용 버튼 한가운데를 가로로 지나가는 힌지
        val applyCenter = composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY)
            .fetchSemanticsNode().layoutBoundsInWindow().center.y.roundToInt()
        val screenWidth = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow().right.roundToInt()
        val hinge = Hinge(left = 0, top = applyCenter, right = screenWidth, bottom = applyCenter, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun main_stripChangesTheRatio_andApplies() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        composeRule.waitForIdle()

        // 자르기 칸은 띠에 있다(테두리를 띠를 뺀 자리에 맞추는 계산은 CanvasViewTest.fitInside_*)
        composeRule.onNode(hasTestTag(EditScreenTags.CROP_CANCEL) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).assertIsDisplayed()
        // 비율 칸의 목록에서 1:1을 고르고 적용하면 정사각형으로 자른다
        composeRule.onNodeWithTag(EditScreenTags.strip("ratio")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.cropRatio(CropRatio.SQUARE)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()
        composeRule.runOnIdle { assertEquals(listOf(CropImage(PixelRect(50, 0, 350, 300), document)), commands) }
    }
}
