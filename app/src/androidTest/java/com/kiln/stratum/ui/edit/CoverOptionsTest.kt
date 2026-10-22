package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 닫힌 화면 공통 규칙(결정 기록 '커버 화면 개편', 전 '닫힌 화면 도구 옵션'): 아래는 늘 한 줄 막대 + 탭 줄이고 캔버스는 그 위만 쓴다.
 * 그래서 도구·조정·레이어를 고르거나 바꾸거나 닫아도 캔버스 크기(보던 이미지의 크기·자리)는 그대로다.
 * 잠깐 뜨는 것(더보기 줄, 레이어 시트)만 캔버스 아래쪽 위에 겹쳐 뜬다. 자르기(새 창)는 빠진다.
 */
@RunWith(AndroidJUnit4::class)
class CoverOptionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(1000, 1000)
    private val canvasSizes = mutableListOf<Pair<Int, Int>>()

    private fun show() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { w, h -> canvasSizes += w to h }, onViewport = {}, onChooseSize = {})
                }
            }
        }
    }

    private fun tab(tool: EditTool) = composeRule.chooseCoverTool(tool)

    private fun canvasBounds() = composeRule.onNodeWithTag(EditScreenTags.CANVAS).fetchSemanticsNode().layoutBoundsInWindow()

    private fun assertOverlapsTheCanvasBottom(name: String, tag: String, canvas: androidx.compose.ui.geometry.Rect) {
        val sheet = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$name $sheet should overlap $canvas", sheet.top < canvas.bottom && sheet.bottom <= canvas.bottom + 1f)
    }

    @Test
    fun theBarSitsBelowTheCanvas_andTheCanvasKeepsItsSize() {
        show()
        // 첫 화면이 자리 잡은 뒤(상단 바가 카메라 구멍을 피해 내려오는 등)부터 본다
        composeRule.waitForIdle()
        composeRule.runOnIdle { canvasSizes.clear() }
        val before = canvasBounds()
        // 막대는 캔버스 바로 아래(겹치지 않음)이고, 도구가 없으면 안내 글이다
        val bar = composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(before.bottom, bar.top, 1f)
        composeRule.onNodeWithTag(EditScreenTags.BAR_HINT).assertIsDisplayed()

        // 도구는 막대만 바뀐다(이동은 옵션이 없다)
        for (tool in listOf(EditTool.Brush, EditTool.Select, EditTool.Retouch, EditTool.Tone, EditTool.Mosaic, EditTool.Text, EditTool.Move)) {
            tab(tool)
            composeRule.waitForIdle()
            assertEquals("$tool canvas", before, canvasBounds())
        }
        // 조정은 같은 막대가 조정 칩이 된다(겹치지 않음, Q1 A)
        composeRule.togglePanel(MainPanel.ADJUST)
        composeRule.waitForIdle()
        assertEquals("adjust canvas", before, canvasBounds())
        assertEquals(bar, composeRule.onNodeWithTag(EditScreenTags.ADJUST_PANEL).fetchSemanticsNode().layoutBoundsInWindow())
        // 레이어는 막대 위에 시트로 겹쳐 뜬다(Q2 A)
        composeRule.togglePanel(MainPanel.LAYERS)
        composeRule.waitForIdle()
        assertEquals("layers canvas", before, canvasBounds())
        assertOverlapsTheCanvasBottom("layers", EditScreenTags.COVER_LAYER_SHEET, before)
        composeRule.togglePanel(MainPanel.LAYERS)
        // 더보기 줄은 사진을 가리지 않고 막대 자리에 뜨고(막대가 잠깐 더보기 줄로 바뀜, 2026-10-22 사용자 요청), 더보기 칸을 다시 누르면 닫힌다
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
        composeRule.waitForIdle()
        assertEquals(bar, composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).fetchSemanticsNode().layoutBoundsInWindow())
        composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).assertDoesNotExist()
        assertEquals("more canvas", before, canvasBounds())
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).assertIsDisplayed()
        assertEquals(before, canvasBounds())
        // 고르고 바꾸고 닫는 동안 캔버스 크기가 한 번도 바뀌지 않았다(축소본을 다시 그리지 않음)
        composeRule.runOnIdle { assertTrue("canvas sizes $canvasSizes", canvasSizes.isEmpty()) }
    }

    @Test
    fun crop_isANewWindow_withoutTheBarOrTheTabs() {
        show()
        tab(EditTool.Transform)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_BAR).assertDoesNotExist()
        val canvas = canvasBounds()
        val options = composeRule.onNodeWithTag(EditScreenTags.COVER_CROP_OPTIONS).fetchSemanticsNode()
        val bounds = options.layoutBoundsInWindow()
        assertTrue("$canvas should end above the crop options $bounds", canvas.bottom <= bounds.top + 1f)
        // 옵션 줄은 한 줄 막대와 같은 높이이고, 회전 탭으로 바꿔도 같아 사진 크기가 그대로다
        val dp = with(options.layoutInfo.density) { 1.dp.toPx() }
        assertEquals(56 * dp, bounds.height, 1f)
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).performClick()
        composeRule.waitForIdle()
        assertEquals(bounds, composeRule.onNodeWithTag(EditScreenTags.COVER_CROP_OPTIONS).fetchSemanticsNode().layoutBoundsInWindow())
        assertEquals(canvas, canvasBounds())
        composeRule.onNodeWithTag(EditScreenTags.FLIP_VERTICAL).performScrollTo().assertIsDisplayed()
    }
}
