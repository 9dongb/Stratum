package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
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

/** 편집 화면에서 뒤로 가기가 고른 도구부터 닫는지(결정 기록 '뒤로 가기로 도구 닫기') 확인한다. */
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
                        onEdit = { edits += it }
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
    fun cover_backClosesTheTool_thenGoesHome() {
        show(COVER)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Brush)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_OPTIONS).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_OPTIONS).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, homeBacks) }

        back()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun cover_adjustTab_closesToo() {
        show(COVER)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Adjust)).performScrollTo().performClick()
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
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Adjust)).performScrollTo().performClick()
        composeRule.runOnIdle { size.value = MAIN_LANDSCAPE }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL).assertExists()
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    @Test
    fun crop_backCancelsTheCrop() {
        show(COVER)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Transform)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertExists()

        back()
        composeRule.onNodeWithTag(EditScreenTags.CROP_OVERLAY).assertDoesNotExist()
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
    fun noTool_goesHome() {
        show(COVER)

        back()

        composeRule.runOnIdle { assertEquals(1, homeBacks) }
    }

    private companion object {
        val COVER = DpSize(411.dp, 800.dp)
        val MAIN_PORTRAIT = DpSize(700.dp, 900.dp)
        val MAIN_LANDSCAPE = DpSize(930.dp, 700.dp)
    }
}
