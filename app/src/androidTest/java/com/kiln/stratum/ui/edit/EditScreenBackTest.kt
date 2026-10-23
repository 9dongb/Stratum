package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.command.EditCommand
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 편집 화면의 뒤로 가기. 메인 화면은 고른 도구부터 닫고(결정 기록 '뒤로 가기로 도구 닫기'), 커버는 떠 있는 것만 닫고 홈으로 간다
 * (2026-10-23 사용자 요청: "도구에서 뒤로가기 시 … 홈으로 가게해주세요"). 커버에서 도구가 없으면 막대 자리는 더보기 줄이다.
 */
@RunWith(AndroidJUnit4::class)
class EditScreenBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 편집 화면 바깥(StratumApp)의 '뒤로 → 홈'이 받은 횟수 */
    private var homeBacks = 0
    private val edits = mutableListOf<EditCommand>()
    private val size = mutableStateOf(COVER)

    private fun ready(): EditorState.Ready {
        val document = PixelSize(1000, 1000)
        val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        return EditorState.Ready(
            PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
    }

    /** 유동화 작업 모드를 연 횟수 */
    private var liquifyStarts = 0

    private fun show(screen: DpSize) {
        size.value = screen
        val state = ready()
        composeRule.setContent {
            // StratumApp처럼 편집 화면보다 먼저 '뒤로 → 홈'을 둔다
            BackHandler { homeBacks++ }
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                StratumTheme {
                    EditScreen(
                        state = state,
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        onEdit = { edits += it },
                        onStartLiquify = { liquifyStarts++ }
                    )
                }
            }
        }
    }

    private fun back() {
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun cover_backFoldsAnOpenChip_thenGoesHome() {
        // 커버 한 줄 막대(결정 기록 '커버 화면 개편'): 칩을 펼쳤으면 뒤로 가기는 칩 줄로 돌아가고, 다음 뒤로 가기는 홈이다
        show(COVER)
        composeRule.chooseCoverTool(EditTool.Brush)
        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertExists()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }
        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun cover_backWithAToolGoesHome() {
        // 도구를 닫는 단계가 없다(사용자 요청: "도구에서 뒤로가기 시 지금은 "아래에서 도구를 골라요"가 뜨는데 … 홈으로 가게해주세요")
        show(COVER)
        composeRule.chooseCoverTool(EditTool.Brush)
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertExists()

        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun cover_backTurnsTheEyedropperOff_beforeGoingHome() {
        // 커버는 도구를 내려놓지 않아, 켠 스포이드는 뒤로 가기로 먼저 끈다(끄지 않으면 홈으로 나갔다, 검토에서 고침)
        show(COVER)
        composeRule.chooseCoverTool(EditTool.Brush)
        composeRule.onNodeWithTag(EditScreenTags.COLOR_EYEDROPPER).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_OVERLAY).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.EYEDROPPER_OVERLAY).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertExists()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }
        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun cover_aMoreRowOpenedOverATool_closesFirst() {
        show(COVER)
        composeRule.chooseCoverTool(EditTool.Select)
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertIsDisplayed()

        back()
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.bar("mode")).assertExists()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }
        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun cover_tappingTheChosenTabAgain_keepsTheTool() {
        show(COVER)
        composeRule.chooseCoverTool(EditTool.Move)
        composeRule.chooseCoverTool(EditTool.Move)

        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Move)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.bar("layerRotateLeft")).assertExists()
    }

    @Test
    fun cover_openingLiquify_comesBackToTheMoreRow() {
        // 유동화는 새 창이라 고른 도구로 남지 않는다. 돌아오면 도구가 없는 기본(더보기 줄)이다(사용자: "기본 값이 첫번째 도구로 옵니다" → 더보기로 바꿈)
        show(COVER)
        composeRule.chooseCoverTool(EditTool.Select)
        composeRule.chooseCoverTool(EditTool.Liquify)

        composeRule.runOnIdle { assertEquals(1, liquifyStarts) }
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertIsDisplayed()
    }

    @Test
    fun cover_adjustPanel_closesFirst() {
        // 커버에서 상단 바 버튼으로 연 조정·레이어는 뒤로 가기로 닫는다(결정 기록 '커버 화면 개편' Q2 A, 메인 패널과 다름)
        show(COVER)
        composeRule.togglePanel(MainPanel.ADJUST)
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }
    }

    @Test
    fun main_railTool_closesFirst() {
        show(MAIN_LANDSCAPE)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }

        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun adjustChosenOnCover_opensThePanelOnTheMainScreen_andBackLeavesItOpen() {
        // 커버에서 조정을 고른 채 펼치면 메인에서는 떠 있는 조정 패널로 열린다.
        // 패널은 상단 바 버튼으로만 닫으므로(사용자 결정) 뒤로 가기는 패널을 그대로 두고 홈으로 간다.
        show(COVER)
        composeRule.togglePanel(MainPanel.ADJUST)
        composeRule.runOnIdle { size.value = MAIN_LANDSCAPE }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertExists()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun crop_backCancelsTheCrop_andTheMoreRowComesBack() {
        show(COVER)
        composeRule.chooseCoverTool(EditTool.Transform)
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertExists()

        // 새 창의 뒤로 가기는 X와 같다(닫고, 도구가 없는 기본인 더보기 줄로 돌아온다)
        back()
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, homeBacks)
            assertTrue("$edits", edits.isEmpty())
        }
    }

    @Test
    fun anOpenDialog_closesBeforeTheTool() {
        // 메인 세로의 창(화면 가운데의 창)이 열려 있으면 창이 먼저 닫힌다
        show(MAIN_PORTRAIT)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT).performClick()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.EXPORT_SAVE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun cover_noTool_showsTheMoreRow_andBackGoesHome() {
        // 도구를 고르지 않았으면 막대 자리에 더보기 줄이 늘 보이고 더보기 칸이 켜진다(2026-10-23 사용자 요청: "늘 고른 도구를 … 더보기 도구로")
        show(COVER)
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.BAR_HINT).assertDoesNotExist()
        // 그때 더보기 칸을 눌러도 닫히지 않는다
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertIsDisplayed()

        back()

        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    private companion object {
        val COVER = DpSize(411.dp, 800.dp)
        val MAIN_PORTRAIT = DpSize(700.dp, 900.dp)
        val MAIN_LANDSCAPE = DpSize(930.dp, 700.dp)
    }
}
