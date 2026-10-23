package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.DIALOG_CARD
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 이미지 크기 변경 창을 열고, 픽셀·퍼센트·비율 유지로 크기를 정해 적용하는 것을 확인한다. */
@RunWith(AndroidJUnit4::class)
class ResizeDialogTest {

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

    /** 지금 배치의 자르기·변형 도구를 고르고, 크기 변경 버튼(커버는 자르기·회전 창의 칸)으로 창을 연다. */
    private fun openFromOptions() {
        val onCover = composeRule.onAllNodes(hasTestTag(EditScreenTags.TOOL_TAB_BAR)).fetchSemanticsNodes().isNotEmpty()
        if (onCover) {
            // 자르기·변형은 커버 더보기 줄에 있다(2026-10-23)
            composeRule.chooseCoverTool(EditTool.Transform)
            composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RESIZE)).performClick()
        } else {
            val button = composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Transform))
            button.performClick()
            // 메인 화면은 고른 도구를 다시 눌러 연 하위 도구 메뉴에서 고른다(띠에는 하위 도구 칸이 없다)
            button.performClick()
            composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.RESIZE)).performClick()
        }
    }

    private val widthField get() = composeRule.onNodeWithTag(EditScreenTags.RESIZE_WIDTH)
    private val heightField get() = composeRule.onNodeWithTag(EditScreenTags.RESIZE_HEIGHT)

    @Test
    fun lockedWidth_updatesHeight_andApplySendsTheSize() {
        show()
        openFromOptions()

        widthField.performTextReplacement("200")
        heightField.assertTextContains("150")
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_APPLY).performClick()

        composeRule.runOnIdle { assertEquals(listOf(ResizeImage(200, 150)), commands) }
        widthField.assertDoesNotExist()
    }

    @Test
    fun longPressMenu_opensTheDialog() {
        show()

        // 커버 더보기 줄(도구가 없으면 늘 보임)의 자르기·변형 칸을 꾹 누르면 하위 도구 메뉴가 뜬다(2026-10-23, 막대에 하위 도구 칩이 없어서)
        composeRule.onNodeWithTag(EditScreenTags.moreTool(EditTool.Transform)).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.RESIZE)).performClick()

        widthField.assertTextContains("400")
        heightField.assertTextContains("300")
    }

    @Test
    fun percent_appliesTheSamePercentToBothSides() {
        show()
        openFromOptions()

        composeRule.onNodeWithTag(EditScreenTags.resizeUnit(ResizeUnit.PERCENT)).performClick()
        widthField.assertTextContains("100")
        widthField.performTextReplacement("50")
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_APPLY).performClick()

        composeRule.runOnIdle { assertEquals(listOf(ResizeImage(200, 150)), commands) }
    }

    @Test
    fun unlocked_changesOnlyOneSide() {
        show()
        openFromOptions()

        composeRule.onNodeWithTag(EditScreenTags.RESIZE_LOCK).performClick()
        heightField.performTextReplacement("100")
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_APPLY).performClick()

        composeRule.runOnIdle { assertEquals(listOf(ResizeImage(400, 100)), commands) }
    }

    @Test
    fun tooLarge_showsWhy_andCannotApply() {
        show()
        openFromOptions()

        // 비율 유지로 9000×6750(약 61MP)이 되어 50MP를 넘는다
        widthField.performTextReplacement("9000")

        composeRule.onNodeWithTag(EditScreenTags.RESIZE_APPLY).assertIsNotEnabled()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.resize_error_too_large, 50)).assertExists()
    }

    @Test
    fun expanded_dialogIsCentered_evenAcrossAVerticalHinge() {
        // 사용자 피드백 11차 1: 전에는 세로 힌지가 있으면 선 오른쪽에 띄웠다
        show(930, 700)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = screen.center.x.roundToInt()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)) }

        openFromOptions()

        widthField.assertExists()
        val card = composeRule.onNodeWithTag(DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(screen.center.x, card.center.x, 2f)
    }

    @Test
    fun medium_statusAndControlsStayInView() {
        show(701, 841)
        openFromOptions()

        // 비율 유지로 9000×6750(약 61MP)이 되어 50MP를 넘는다
        widthField.performTextReplacement("9000")

        // 스크롤하지 않아도 까닭과 고치는 칸이 다 보인다. 위의 단위·비율 유지 줄도 화면에 남는다
        assertFullyVisible(composeRule.onNodeWithText(composeRule.activity.getString(R.string.resize_error_too_large, 50)))
        assertFullyVisible(widthField)
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_LOCK).assertIsDisplayed()
    }

    /** 노드 내용이 스크롤 영역 등 부모에 잘리지 않고 다 보이는지. 잘린 창 좌표의 높이와 원래 높이를 비교한다. */
    private fun assertFullyVisible(interaction: SemanticsNodeInteraction) {
        val coordinates = interaction.fetchSemanticsNode().layoutInfo.coordinates
        assertEquals("visible height", coordinates.size.height.toFloat(), coordinates.boundsInWindow().height, 1f)
    }

    @Test
    fun input_survivesStateRestore() {
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
        openFromOptions()
        widthField.performTextReplacement("200")

        // 접기·펼치기나 백그라운드 종료로 화면이 다시 만들어지는 것처럼 저장했다가 되살린다
        tester.emulateSavedInstanceStateRestore()

        heightField.assertTextContains("150")
        composeRule.onNodeWithTag(EditScreenTags.RESIZE_APPLY).performClick()
        composeRule.runOnIdle { assertEquals(listOf(ResizeImage(200, 150)), commands) }
    }
}
