package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.CropImage
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
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
    private val commands = mutableListOf<EditCommand>()

    private fun ready(canUndo: Boolean = false): EditorState.Ready {
        val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document, canUndo = canUndo, canRedo = canUndo)
    }

    /** 화면에 넘길 상태. 정하지 않으면 [ready]다(테스트가 바쁨·문서 번호를 바꿔 편집 흐름을 흉내 낼 때 정한다). */
    private var shown by mutableStateOf<EditorState.Ready?>(null)

    private fun show(widthDp: Int = 411, heightDp: Int = 800, canUndo: Boolean = false) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                StratumTheme {
                    EditScreen(
                        state = shown ?: ready(canUndo),
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

    private fun startCropOnCover() {
        composeRule.chooseCoverTool(EditTool.Transform)
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
        // 자르기·회전 창이 닫히고 편집 화면(한 줄 막대)으로 돌아온다
        composeRule.onNodeWithTag(EditScreenTags.COVER_CROP_OPTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertDoesNotExist()
    }

    @Test
    fun threeToFourRatio_sendsAPortraitCrop() {
        show()
        startCropOnCover()

        chooseRatio(CropRatio.PORTRAIT_3_4)
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsEnabled().performClick()

        // 4:3 사진에서 세로 300에 맞춘 3:4(폭 225)
        composeRule.runOnIdle { assertEquals(listOf(CropImage(PixelRect(88, 0, 313, 300), document)), commands) }
    }

    @Test
    fun swap_turnsTheRatio() {
        show()
        startCropOnCover()

        // 세로가 기본(사용자 피드백 10차): 9:16을 고르고 바꾸면 16:9이고, 칩 이름도 뒤집혀 보인다
        chooseRatio(CropRatio.STORY_9_16)
        composeRule.onNodeWithTag(EditScreenTags.CROP_SWAP).performClick()
        composeRule.onNodeWithTag(EditScreenTags.cropRatio(CropRatio.STORY_9_16)).assert(hasText("16:9"))
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()

        // 16:9로 폭 400에 맞춘다(높이 225)
        composeRule.runOnIdle {
            val rect = (commands.single() as CropImage).rect
            assertEquals(400, rect.width)
            assertEquals(225, rect.height)
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

    /**
     * 캔버스가 알리는 배율(화면 읽기 기능, 사용자 피드백 10차 8부터 배율 표시는 배율을 바꿀 때만 보인다)이 캔버스에 [size] 사진을 맞춘 배율인지 본다.
     * 테스트가 잰 캔버스 크기와 앱의 계산이 반올림 한 자리(1%) 다를 수 있어 1.5% 안이면 같다고 본다(돌린 사진과 원래 사진의 맞춤 배율은 그보다 훨씬 다르다).
     */
    private fun assertZoomFits(size: PixelSize) {
        val canvas = bounds(EditScreenTags.CANVAS)
        val expected = minOf(canvas.width / size.width, canvas.height / size.height).toDouble() * 100
        val description = composeRule.canvasImage().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val percent = checkNotNull(Regex("""(\d+(?:\.\d+)?)%""").find(description)) { description }.groupValues[1].toDouble()
        assertEquals("$description should be the fit zoom of $size", expected, percent, 1.5)
    }

    @Test
    fun main_aTurnAndCropThatFails_doesNotLeaveTheTurnedLookBehind() {
        // ✓를 누른 뒤 결과가 올 때까지는 돌린 모습(300×400)이다. 하지 못하고 끝나면(메모리 부족 등) 원래 모습으로 돌아가고,
        // 같은 문서로 다시 바쁠 때(다른 편집을 기록하는 동안)도 돌린 모습이 보이지 않는다
        shown = ready()
        show(930, 700)
        val turned = PixelSize(document.height, document.width)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_RIGHT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).performClick()
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).performClick()
        composeRule.runOnIdle { shown = ready().copy(busy = true) }
        assertZoomFits(turned)

        // 하지 못하고 끝났다(문서 번호 그대로)
        composeRule.runOnIdle { shown = ready() }
        assertZoomFits(document)
        // 같은 문서로 다시 바쁘다
        composeRule.runOnIdle { shown = ready().copy(busy = true) }
        assertZoomFits(document)
    }

    @Test
    fun main_cancellingAfterTurningAndZooming_showsTheWholeOriginalPhoto() {
        // 돌린 채 확대한 보기는 돌린 사진의 좌표라, 취소하면 원래 사진에 맞춘 보기로 돌아간다
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.ROTATE_RIGHT) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).performClick()
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(200f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(200f, 0f)
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EditScreenTags.CROP_CANCEL).performClick()

        assertZoomFits(document)
        // 맞춤이면 화면에 맞추는 동작이 없다
        val actions = composeRule.canvasImage().fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertTrue("$actions", actions.none { it.label == composeRule.activity.getString(R.string.canvas_fit) })
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
        // 지금 배치에 맞는 도구 버튼(커버는 더보기 줄, 메인은 막대)
        val onCover = composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(EditScreenTags.TOOL_TAB_BAR))
            .fetchSemanticsNodes().isNotEmpty()
        if (onCover) {
            composeRule.chooseCoverTool(EditTool.Transform)
        } else {
            composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        }
        // 메인 화면은 띠의 비율 칸을 눌러야 비율 칩이 뜬다(밀어 보지 않는 옵션 창이라 바로 누른다, 펼친 에뮬레이터에서도 돌게)
        if (onCover) {
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
    fun medium_theCropStripShowsTheApplyButton() {
        show(700, 900)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.CROP_APPLY) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).assertIsDisplayed()
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
