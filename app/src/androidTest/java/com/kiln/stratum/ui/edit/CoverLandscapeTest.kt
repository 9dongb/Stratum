package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
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
 * 커버를 가로로 돌린 배치(사용자 피드백 12차 3: "커버화면을 가로로 돌리면 펼친화면 배치가 아닌, 커버화면 배치가 돌아간형태로 보여주는 걸로 하세요",
 * 질문에서 "막대를 오른쪽 세로로"): 짧은 쪽이 600dp보다 작은 가로 창은 [캔버스 | 세로 한 줄 막대 | 세로 탭 줄]이고, 도구를 골라도 캔버스 크기가 그대로다.
 * 칩은 위아래로 쌓이고, 칩을 누르면 같은 기둥이 세로 슬라이더(위로 끌면 커짐)가 되며 ✓로 돌아온다. 더보기 줄은 막대 자리에 세로로,
 * 레이어는 캔버스 오른쪽 패널(도구가 없으면 막대 자리까지 덮음), 하위 도구 메뉴는 탭 왼쪽에 뜬다.
 * 자르기·회전 새 창은 [캔버스 | 옵션 기둥 | ✓ · 탭 · X 기둥]이다(유동화 창은 [LiquifyModeTest]).
 */
@RunWith(AndroidJUnit4::class)
class CoverLandscapeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var brush by mutableStateOf(BrushSettings(size = 40f, hardness = 0.5f, color = 0xFF000000.toInt()))
    private var size by mutableStateOf(COVER_LANDSCAPE)
    private val canvasSizes = mutableListOf<Pair<Int, Int>>()

    private val layerActions = object : LayerActions {
        override fun select(id: LayerId) = Unit
        override fun add() = Unit
        override fun delete() = Unit
        override fun move(id: LayerId, toIndex: Int) = Unit
        override fun previewOpacity(opacity: Float) = Unit
        override fun commitOpacity() = Unit
        override fun setBlendMode(mode: BlendMode) = Unit
        override fun duplicate() = Unit
        override fun mergeDown() = Unit
        override fun mergeVisible() = Unit
        override fun setVisible(id: LayerId, visible: Boolean) = Unit
    }

    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    private fun show(screen: DpSize = COVER_LANDSCAPE) {
        size = screen
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                StratumTheme {
                    EditScreen(
                        state = EditorState.Ready(
                            PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                            document,
                            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
                            activeLayer = LayerId(0)
                        ),
                        onBack = {},
                        onCanvasSize = { w, h -> canvasSizes += w to h },
                        onViewport = {},
                        onChooseSize = {},
                        brush = brush,
                        onBrush = { brush = it },
                        layerActions = layerActions
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()

    private fun dp(tag: String) = with(composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.density) { 1.dp.toPx() }

    /** 탭 줄은 오른쪽 끝 세로, 막대 자리(도구가 없어 더보기 줄, 2026-10-23)는 그 왼쪽 세로, 캔버스는 막대 왼쪽이다 */
    private fun assertRotatedCoverLayout() {
        val tabs = bounds(EditScreenTags.TOOL_TAB_ROW)
        val bar = bounds(EditScreenTags.COVER_MORE_TOOLS)
        val canvas = bounds(EditScreenTags.CANVAS)
        assertTrue("tabs $tabs should be a tall column", tabs.height > tabs.width * 2)
        assertTrue("bar $bar should be a tall column", bar.height > bar.width * 2)
        assertTrue("bar $bar should be left of the tabs $tabs", bar.right <= tabs.left + 1f)
        assertTrue("canvas $canvas should be left of the bar $bar", canvas.right <= bar.left + 1f)
        // 탭은 위부터 이동·선택·흐림·리터칭·유동화, 맨 아래 더보기
        val tops = EditTool.coverTabs.map { bounds(EditScreenTags.tabTool(it)).top } + bounds(EditScreenTags.MORE_TAB).top
        assertEquals(tops.sorted(), tops)
        // 막대와 탭 줄 폭은 가로 막대의 높이(56dp)와 같다
        val dp = dp(EditScreenTags.COVER_MORE_TOOLS)
        assertEquals(56 * dp, bar.width, 2 * dp)
    }

    @Test
    fun wideShortWindows_putTheBarAndTheTabsOnTheRight_asColumns() {
        // 에뮬레이터 커버 가로(797×411)·폴드8 커버 가로(약 751×475)·가로로 눕힌 폰(900×320)
        show(COVER_LANDSCAPE)
        assertRotatedCoverLayout()
        for (screen in listOf(DpSize(751.dp, 475.dp), DpSize(900.dp, 320.dp))) {
            composeRule.runOnIdle { size = screen }
            composeRule.waitForIdle()
            assertRotatedCoverLayout()
        }
        // 펼친 화면 배치(도구 막대)는 없다
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).assertDoesNotExist()
    }

    @Test
    fun choosingATool_keepsTheCanvasSize_andShowsItsCellsTopToBottom() {
        show()
        val before = bounds(EditScreenTags.CANVAS)
        composeRule.chooseCoverTool(EditTool.Brush)
        composeRule.waitForIdle()
        assertEquals(before, bounds(EditScreenTags.CANVAS))
        // 색 버튼 아래 크기·경도·불투명도 칸(기둥 밖으로 밀려 있어도 잘리지 않은 자리로 본다)
        val cells = listOf("colors", "size", "hardness", "opacity").map {
            composeRule.onNodeWithTag(EditScreenTags.bar(it)).fetchSemanticsNode().positionInWindow.y
        }
        assertEquals(cells.sorted(), cells)
        // 펼친 화면의 띠는 없다
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
    }

    @Test
    fun anExpandedValueColumn_putsTheNameAboveAndTheValueBelow_centeredInTheirSpaces() {
        show()
        composeRule.chooseCoverTool(EditTool.Brush)
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).performScrollTo().performClick()

        // 세로 커버의 [이름 | 슬라이더 | 값 | ✓]를 돌린 차례: 이름은 기둥 위 끝과 슬라이더 사이, 값은 슬라이더 끝과 ✓ 사이의 가운데(2026-10-23)
        val column = bounds(EditScreenTags.COVER_TOOL_BAR)
        val slider = bounds(EditScreenTags.BRUSH_SIZE)
        val done = bounds(EditScreenTags.BAR_DONE)
        val (caption, value) = composeRule.sliderCaptionAndValue(EditScreenTags.BRUSH_SIZE)
        val name = composeRule.textCenterInWindow(caption)
        val shown = composeRule.textCenterInWindow(value)
        val dp = dp(EditScreenTags.COVER_TOOL_BAR)
        assertEquals((column.top + slider.top) / 2f, name.y, 8 * dp)
        assertEquals((slider.bottom + done.top) / 2f, shown.y, 8 * dp)
        assertEquals(column.center.x, name.x, 4 * dp)
        assertEquals(column.center.x, shown.x, 4 * dp)
    }

    @Test
    fun aValueCell_dragLeftGrowsIt_andUpAndDownLeaveIt() {
        show()
        composeRule.chooseCoverTool(EditTool.Brush)
        val before = composeRule.runOnIdle { brush.size }
        // 가로 커버 기둥은 캔버스 쪽(왼쪽)으로 끌면 커진다(세로 커버의 위로 끌기를 돌린 것, 2026-10-23). 위아래는 기둥을 밀어 보는 몫이다.
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).performScrollTo().performTouchInput {
            down(center)
            moveBy(Offset(0f, 30.dp.toPx()))
            up()
        }
        composeRule.runOnIdle { assertEquals(before, brush.size, 0f) }
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).performScrollTo().performTouchInput {
            down(center)
            moveBy(Offset(-30.dp.toPx(), 0f))
            moveBy(Offset(-40.dp.toPx(), 0f))
            up()
        }
        composeRule.runOnIdle { assertTrue("${brush.size} should grow from $before", brush.size > before) }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_SIZE).assertDoesNotExist()
    }

    @Test
    fun aValueCell_becomesAVerticalSlider_draggingUpGrowsIt_andDoneReturns() {
        show()
        composeRule.chooseCoverTool(EditTool.Brush)
        // 접은 에뮬레이터에서는 강제 크기를 줄여 넣어 기둥이 낮아져 경도 칸이 밀려 있다(기술 주의점). 밀어 보이게 한 뒤 누른다.
        composeRule.onNodeWithTag(EditScreenTags.bar("hardness")).performScrollTo().performClick()

        val slider = bounds(EditScreenTags.BRUSH_HARDNESS)
        assertTrue("$slider should be tall", slider.height > slider.width)
        val bar = bounds(EditScreenTags.COVER_TOOL_BAR)
        assertTrue("$slider should be in the bar $bar", slider.left >= bar.left - 1f && slider.right <= bar.right + 1f)
        // 띠의 세로 슬라이더 창과 같은 얇은 슬라이더(사용자 결정): 누른 자리로 가고 끌면 따라온다
        val before = brush.hardness
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).performTouchInput {
            down(center)
            moveBy(Offset(0f, -height / 8f))
            moveBy(Offset(0f, -height / 8f))
            up()
        }
        composeRule.runOnIdle { assertTrue("${brush.hardness} should be above $before", brush.hardness > before + 0.1f) }

        composeRule.onNodeWithTag(EditScreenTags.BAR_DONE).performClick()
        composeRule.onNodeWithTag(EditScreenTags.bar("hardness")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_HARDNESS).assertDoesNotExist()
    }

    @Test
    fun more_isAVerticalColumnInTheBarsPlace_shownWhileNoToolIsChosen() {
        show()
        // 도구를 고르지 않았으면 막대 자리(탭 줄 바로 왼쪽)에 더보기 줄이 세로로 늘 보인다(2026-10-23 사용자 요청)
        val more = bounds(EditScreenTags.COVER_MORE_TOOLS)
        val tabs = bounds(EditScreenTags.TOOL_TAB_ROW)
        assertEquals(tabs.left, more.right, 2f)
        assertTrue("$more should be a tall column", more.height > more.width * 2)
        val tops = EditTool.coverMoreTools.map { bounds(EditScreenTags.moreTool(it)).top }
        assertEquals(tops.sorted(), tops)
        composeRule.onNodeWithTag(EditScreenTags.moreTool(EditTool.Eraser)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.COVER_MORE_TOOLS).assertDoesNotExist()
        // 도구를 고르면 같은 자리가 그 도구의 막대다
        val bar = bounds(EditScreenTags.COVER_TOOL_BAR)
        assertEquals(more.left, bar.left, 2f)
        assertEquals(more.right, bar.right, 2f)
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
    }

    @Test
    fun layers_openAPanelLeftOfTheBarOrTheMoreRow() {
        show()
        val canvas = bounds(EditScreenTags.CANVAS)
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).performClick()
        composeRule.waitForIdle()

        // 도구가 없으면 막대 자리는 더보기 줄이고(2026-10-23), 레이어 패널은 그 왼쪽에 붙는다(전에는 안내 글 막대까지 덮었다)
        val besideMore = bounds(EditScreenTags.COVER_LAYER_SHEET)
        assertEquals(bounds(EditScreenTags.COVER_MORE_TOOLS).left, besideMore.right, 2f)
        assertTrue("$besideMore should overlap the canvas $canvas", besideMore.left < canvas.right)

        // 도구를 골라도 막대 왼쪽에 붙고, 자리는 그대로다
        composeRule.chooseCoverTool(EditTool.Brush)
        composeRule.waitForIdle()
        val beside = bounds(EditScreenTags.COVER_LAYER_SHEET)
        val bar = bounds(EditScreenTags.COVER_TOOL_BAR)
        assertEquals(bar.left, beside.right, 2f)
        assertEquals(besideMore.left, beside.left, 2f)
        // 캔버스 크기는 그대로다
        assertEquals(canvas, bounds(EditScreenTags.CANVAS))
    }

    @Test
    fun cropWindow_isARightColumn_checkOnTop_xAtTheBottom_andTabsKeepTheCanvasSize() {
        // 새 창도 돌아간 배치다: [캔버스 | 옵션 기둥 | ✓ · 탭 · X 기둥](닫힌 화면 새 창 공통 규칙의 맨 아래 줄을 돌린 것)
        show()
        composeRule.chooseCoverTool(EditTool.Transform)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.COVER_WINDOW_TOP_BAR).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_ROW).assertDoesNotExist()

        val canvas = bounds(EditScreenTags.CANVAS)
        val options = bounds(EditScreenTags.COVER_CROP_OPTIONS)
        val apply = bounds(EditScreenTags.CROP_APPLY)
        assertTrue("canvas $canvas should be left of the options $options", canvas.right <= options.left + 1f)
        assertTrue("options $options should be left of the work column ($apply)", options.right <= apply.left + 1f)
        assertTrue("options $options should be a tall column", options.height > options.width * 2)
        // 위부터 ✓, 자르기·회전 탭, 크기 변경, X(한 기둥)
        val column = listOf(
            apply,
            bounds(EditScreenTags.cropTab(CropTab.CROP)),
            bounds(EditScreenTags.cropTab(CropTab.ROTATE)),
            bounds(EditScreenTags.subToolOption(SubTool.RESIZE)),
            bounds(EditScreenTags.CROP_CANCEL)
        )
        for ((upper, lower) in column.zipWithNext()) assertTrue("$upper should be above $lower", upper.bottom <= lower.top + 1f)
        for (item in column) assertEquals("$item", apply.center.x, item.center.x, 2f)
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.CROP)).assertIsSelected()

        // 비율 칸은 위아래로 쌓이고, 고르면 적용할 것이 생긴다(정사각형 문서라 1:1이 아닌 9:16)
        val ratios = listOf(CropRatio.FREE, CropRatio.ORIGINAL, CropRatio.SQUARE).map { bounds(EditScreenTags.cropRatio(it)).top }
        assertEquals(ratios.sorted(), ratios)
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.cropRatio(CropRatio.STORY_9_16)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.cropRatio(CropRatio.STORY_9_16)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.CROP_APPLY).assertIsEnabled()

        // 회전 탭으로 바꿔도 캔버스 크기가 그대로이고, 돌리기·뒤집기 칸이 위아래로 선다
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.cropTab(CropTab.ROTATE)).assertIsSelected()
        assertEquals(canvas, bounds(EditScreenTags.CANVAS))
        val turns = listOf(EditScreenTags.ROTATE_LEFT, EditScreenTags.ROTATE_RIGHT, EditScreenTags.FLIP_HORIZONTAL, EditScreenTags.FLIP_VERTICAL)
            .map { bounds(it).top }
        assertEquals(turns.sorted(), turns)

        // X는 창을 닫고 돌아간 편집 화면으로 돌아온다
        composeRule.onNodeWithTag(EditScreenTags.CROP_CANCEL).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EditScreenTags.COVER_WINDOW_TOP_BAR).assertDoesNotExist()
        assertRotatedCoverLayout()
    }

    @Test
    fun subToolMenu_opensLeftOfTheTab() {
        show()
        // 고른 도구를 한 번 더 누르면 하위 도구 메뉴
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performClick()

        val menu = bounds(EditScreenTags.SUB_TOOL_MENU)
        val tab = bounds(EditScreenTags.tabTool(EditTool.Retouch))
        assertTrue("menu $menu should be left of the tab $tab", menu.right <= tab.left + 1f)
    }

    private companion object {
        /** Pixel_Fold 에뮬레이터 커버를 가로로 돌린 크기 */
        val COVER_LANDSCAPE = DpSize(797.dp, 411.dp)
    }
}
