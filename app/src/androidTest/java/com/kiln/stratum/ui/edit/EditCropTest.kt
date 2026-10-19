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

    private fun ready(): EditorState.Ready {
        val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document)
    }

    private fun show(widthDp: Int = 411, heightDp: Int = 800) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
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
    fun cover_choosingRotate_leavesTheCropWindow() {
        show()
        startCropOnCover()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.ROTATE)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.COVER_WINDOW_TOP_BAR).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.ROTATE_LEFT).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_ROW).assertExists()
    }

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
        chooseRatio(CropRatio.SQUARE)

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
