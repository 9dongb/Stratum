package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.components.LongPressTimes
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 레이어 패널을 커버 레이어 시트(결정 기록 '커버 화면 개편' Q2 A)와 메인 화면의 떠 있는 레이어 패널에서 확인한다.
 * 엔진 대신 레이어 목록만 들고 있는 가짜([FakeLayers])로 버튼·목록·슬라이더·메뉴가 보내는 일을 본다.
 */
@RunWith(AndroidJUnit4::class)
class LayerPanelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()


    /** ViewModel처럼 레이어 목록·고른 레이어·끄는 불투명도를 들고, 받은 일을 적는다 */
    private class FakeLayers(count: Int) : LayerActions {
        var layers by mutableStateOf(List(count) { summary(it) })
        var active by mutableStateOf(LayerId(count - 1L))
        var liveOpacity by mutableStateOf<Float?>(null)
        val calls = mutableListOf<String>()

        override fun select(id: LayerId) {
            calls += "select ${id.value}"
            active = id
        }

        override fun add() {
            calls += "add"
            layers = layers + summary(layers.size)
            active = layers.last().id
        }

        override fun delete() {
            calls += "delete ${active.value}"
        }

        override fun duplicate() {
            calls += "duplicate ${active.value}"
        }

        override fun mergeDown() {
            calls += "mergeDown ${active.value}"
        }

        override fun move(id: LayerId, toIndex: Int, parent: LayerId?) {
            calls += "move ${id.value} to $toIndex" + (parent?.let { " in ${it.value}" } ?: "")
            // 엔진의 MoveLayer처럼 덩어리(그룹이면 자손과 함께)째 옮기고 맨 위 줄의 그룹을 바꾼다
            val from = layers.indexOfFirst { it.id == id }
            val size = LayerRows.blockSize(layers, from)
            val moved = layers.subList(from - size + 1, from + 1).toMutableList()
            moved[moved.lastIndex] = moved.last().copy(parent = parent)
            val rest = layers.toMutableList().apply { subList(from - size + 1, from + 1).clear() }
            layers = rest.apply { addAll(toIndex - (size - 1), moved) }
        }

        override fun group() {
            calls += "group ${active.value}"
        }

        override fun ungroup() {
            calls += "ungroup ${active.value}"
        }

        override fun previewOpacity(opacity: Float) {
            liveOpacity = opacity
        }

        override fun commitOpacity() {
            val value = liveOpacity ?: return
            calls += "opacity $value"
            layers = layers.map { if (it.id == active) it.copy(opacity = value) else it }
            liveOpacity = null
        }

        override fun setBlendMode(mode: BlendMode) {
            calls += "blend $mode"
            layers = layers.map { if (it.id == active) it.copy(blendMode = mode) else it }
        }

        override fun setVisible(id: LayerId, visible: Boolean) {
            calls += "visible ${id.value} $visible"
            layers = layers.map { if (it.id == id) it.copy(visible = visible) else it }
        }

        override fun rasterize() {
            calls += "rasterize ${active.value}"
            layers = layers.map { if (it.id == active) it.copy(text = null, liquify = false) else it }
        }

        override fun addAdjustment() {
            calls += "addAdjustment ${active.value}"
        }

        override fun addMask() {
            calls += "addMask ${active.value}"
            layers = layers.map { if (it.id == active) it.copy(hasMask = true) else it }
        }

        override fun invertMask() {
            calls += "invertMask ${active.value}"
        }

        override fun deleteMask() {
            calls += "deleteMask ${active.value}"
            layers = layers.map { if (it.id == active) it.copy(hasMask = false) else it }
        }

        override fun editMask(id: LayerId) {
            calls += "editMask ${id.value}"
            active = id
        }

        override fun editPixels(id: LayerId) {
            calls += "editPixels ${id.value}"
            active = id
        }

        companion object {
            fun summary(index: Int) = LayerSummary(LayerId(index.toLong()), "Layer $index", 1f, BlendMode.NORMAL, Adjustments.NONE)
        }
    }

    private val document = PixelSize(400, 300)
    private val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)

    /** 사진 넣기 버튼을 누른 횟수(앱이 사진 선택기를 띄우는 자리) */
    private var photoRequests = 0

    private fun show(fake: FakeLayers, widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                StratumTheme {
                    EditScreen(
                        state = EditorState.Ready(
                            PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)),
                            document,
                            layers = fake.layers,
                            activeLayer = fake.active
                        ),
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        liveOpacity = { fake.liveOpacity },
                        layerActions = fake,
                        onAddPhoto = { photoRequests++ }
                    )
                }
            }
        }
    }

    private fun openCompactLayers(fake: FakeLayers) {
        show(fake, 411, 800)
        // 커버도 상단 바의 레이어 버튼으로 연다. 한 줄 막대 위에 시트가 올라온다.
        composeRule.togglePanel(MainPanel.LAYERS)
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
    }

    private fun row(index: Int) = composeRule.onNodeWithTag(EditScreenTags.layerRow(LayerId(index.toLong())))

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()

    @Test
    fun compact_sheetRisesAboveTheMoreRowOrAToolsBar_andKeepsTheCanvas() {
        show(FakeLayers(2), 411, 800)
        val canvas = bounds(EditScreenTags.CANVAS)
        // 도구가 없으면 막대 자리는 더보기 줄이다(2026-10-23, 전에는 안내 글 막대를 시트가 덮었다)
        val bar = bounds(EditScreenTags.COVER_MORE_TOOLS)
        composeRule.togglePanel(MainPanel.LAYERS)

        // 시트는 더보기 줄 위(캔버스 아래쪽 위)에 뜨고 둘 다 보인다. 캔버스 크기는 그대로다.
        var sheet = bounds(EditScreenTags.COVER_LAYER_SHEET)
        assertEquals(canvas.bottom, sheet.bottom, 1f)
        assertTrue("$sheet should start inside $canvas", sheet.top > canvas.top)
        assertEquals(canvas, bounds(EditScreenTags.CANVAS))
        assertEquals(bar, bounds(EditScreenTags.COVER_MORE_TOOLS))

        // 도구를 고르면 시트는 막대 바로 위(캔버스 아래쪽 위)에 남고, 막대(고른 도구의 칩)는 그대로 쓸 수 있다
        composeRule.onNodeWithTag(EditScreenTags.moreTool(EditTool.Eraser)).performClick()
        composeRule.waitForIdle()
        sheet = bounds(EditScreenTags.COVER_LAYER_SHEET)
        assertEquals(canvas.bottom, sheet.bottom, 1f)
        assertEquals(bar, bounds(EditScreenTags.COVER_TOOL_BAR))
        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.BAR_DONE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.COVER_LAYER_SHEET).assertIsDisplayed()
        assertEquals(canvas, bounds(EditScreenTags.CANVAS))
    }

    @Test
    fun compact_draggingTheSheetDown_closesIt_andBackClosesItToo() {
        openCompactLayers(FakeLayers(2))
        // 머리를 아래로 끌면 닫히고 레이어 버튼도 꺼진다
        composeRule.onNodeWithTag(EditScreenTags.COVER_LAYER_SHEET_HANDLE).performTouchInput {
            swipe(start = center, end = center + Offset(0f, 120.dp.toPx()), durationMillis = 300)
        }
        composeRule.onNodeWithTag(EditScreenTags.COVER_LAYER_SHEET).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).assertIsOff()

        // 뒤로 가기로도 닫힌다(아래 시트는 뒤로 가기로 닫히는 것이 보통이다)
        composeRule.togglePanel(MainPanel.LAYERS)
        composeRule.onNodeWithTag(EditScreenTags.COVER_LAYER_SHEET).assertIsDisplayed()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(EditScreenTags.COVER_LAYER_SHEET).assertDoesNotExist()
    }

    @Test
    fun compact_tappingTheTitle_letsTheSheetGrowToTheCanvasHeight() {
        // 레이어가 많으면 반 높이에서는 목록을 밀어 보고, 제목을 누르면 캔버스 높이까지 늘어난다.
        // 시트는 늘 막대 자리(도구가 없으면 더보기 줄) 위에 뜬다(2026-10-23, 전에는 안내 글 막대까지 덮어 그만큼 더 길었다).
        openCompactLayers(FakeLayers(12))
        val canvas = bounds(EditScreenTags.CANVAS)
        val half = bounds(EditScreenTags.COVER_LAYER_SHEET)
        assertTrue("$half should be at most about half of $canvas", half.height <= canvas.height * 0.56f)
        assertEquals(canvas.bottom, half.bottom, 1f)

        composeRule.onNodeWithTag(EditScreenTags.COVER_LAYER_SHEET_HANDLE).performClick()
        val full = bounds(EditScreenTags.COVER_LAYER_SHEET)
        assertTrue("$full should be taller than $half", full.height > half.height + 1f)
        assertTrue("$full should stay inside $canvas", full.top >= canvas.top - 1f)

        composeRule.onNodeWithTag(EditScreenTags.COVER_LAYER_SHEET_HANDLE).performClick()
        assertEquals(half, bounds(EditScreenTags.COVER_LAYER_SHEET))
    }

    @Test
    fun compact_listShowsTheTopLayerFirst_andTappingPicksALayer() {
        val fake = FakeLayers(3)
        openCompactLayers(fake)

        val top = row(2).fetchSemanticsNode().layoutBoundsInWindow().top
        val bottom = row(0).fetchSemanticsNode().layoutBoundsInWindow().top
        assertTrue("top layer row should be above: $top vs $bottom", top < bottom)
        row(2).assertIsSelected()

        row(1).performClick()
        composeRule.runOnIdle { assertEquals(LayerId(1), fake.active) }
        row(1).assertIsSelected()
    }

    @Test
    fun toolbar_addMenuOffersLayerAdjustmentAndPhoto_andHasTheViewFlipsInsteadOfUpDown() {
        // 2026-10-24 사용자 요청: "더하기 버튼을 누르면 새 레이어, 조정 레이어, 사진 이렇게 선택할 수 있게", 사진·휴지통 버튼은 뺌
        val fake = FakeLayers(1)
        openCompactLayers(fake)

        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD_MENU_ADJUSTMENT).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD_MENU_PHOTO).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD_MENU_LAYER).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD_MENU_LAYER).assertDoesNotExist()
        // 사용자 요청: 위로·아래로 버튼 자리에 화면 상하·좌우 반전(보기만 뒤집어 레이어 일을 보내지 않음). 좌우 반전이 오른쪽이다.
        val vertical = composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_VERTICAL).fetchSemanticsNode().layoutBoundsInWindow()
        val horizontal = composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_HORIZONTAL).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$horizontal should be right of $vertical", horizontal.left >= vertical.right)
        composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_HORIZONTAL).assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_VERTICAL).assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithTag(EditScreenTags.VIEW_FLIP_HORIZONTAL).performClick().assertIsOff()

        composeRule.runOnIdle { assertEquals(listOf("add"), fake.calls) }
    }

    @Test
    fun opacity_previewsThenRecords() {
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        composeRule.onNodeWithTag(EditScreenTags.LAYER_OPACITY).performSemanticsAction(SemanticsActions.SetProgress) { it(0.35f) }

        composeRule.runOnIdle {
            assertEquals(listOf("opacity 0.35"), fake.calls)
            assertEquals(0.35f, fake.layers.last().opacity)
        }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_OPACITY)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "35%"))
    }

    @Test
    fun blendMenu_sitsLeftOfAddInTheHeader_andSetsTheMode() {
        // 2026-10-24 사용자 요청: 혼합 모드는 목록 아래 줄에서 빼고 "모드 선택 옵션만 (라벨 없이) … + 버튼 왼쪽에"
        val fake = FakeLayers(2)
        openCompactLayers(fake)
        val blend = bounds(EditScreenTags.LAYER_BLEND)
        val add = bounds(EditScreenTags.LAYER_ADD)
        assertEquals(add.center.y, blend.center.y, 2f)
        assertTrue("$blend should be left of $add", blend.right <= add.left + 1f)
        val list = bounds(EditScreenTags.LAYER_LIST)
        assertTrue("$blend should be above the list $list", blend.bottom <= list.top + 1f)
        composeRule.onNodeWithTag(EditScreenTags.LAYER_BLEND)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(string(R.string.layer_blend_mode))))

        composeRule.onNodeWithTag(EditScreenTags.LAYER_BLEND).performClick()
        composeRule.onNodeWithTag(EditScreenTags.blendMode(BlendMode.OVERLAY)).performClick()

        composeRule.runOnIdle { assertEquals(listOf("blend OVERLAY"), fake.calls) }
    }

    @Test
    fun draggingTheHandle_movesTheLayer() {
        val fake = FakeLayers(3)
        openCompactLayers(fake)
        val rowHeight = row(0).fetchSemanticsNode().layoutBoundsInWindow().height

        // 맨 아래 레이어(화면에서는 맨 아래 줄)를 두 줄 위로 끌어 맨 위로
        // 손잡이는 줄(누를 수 있는 한 덩어리)에 합쳐져 있어 합치기 전 나무에서 찾는다
        composeRule.onNodeWithTag(EditScreenTags.layerHandle(LayerId(0)), useUnmergedTree = true).performTouchInput {
            swipe(start = center, end = center - Offset(0f, rowHeight * 2), durationMillis = 500)
        }

        composeRule.runOnIdle {
            assertEquals(listOf("move 0 to 2"), fake.calls)
            assertEquals(listOf(1L, 2L, 0L), fake.layers.map { it.id.value })
        }
    }

    @Test
    fun accessibilityActions_moveTheLayer() {
        val fake = FakeLayers(2)
        openCompactLayers(fake)
        val up = composeRule.activity.getString(R.string.layer_move_up)

        val action = row(0).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == up }
        composeRule.runOnIdle { action.action() }

        composeRule.runOnIdle { assertEquals(listOf("move 0 to 1"), fake.calls) }
    }

    @Test
    fun longPressingARow_picksItAndOpensTheMenu_turningOffWhatCannotBeDone() {
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        row(0).performTouchInput { longClick() }

        composeRule.runOnIdle { assertEquals(LayerId(0), fake.active) }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertIsEnabled()
        // 맨 아래 레이어는 아래와 합칠 수 없다
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DELETE).assertIsEnabled()
        // 이름 바꾸기·보이는 레이어 병합·조정 레이어 더하기는 뺐다(2026-10-24 사용자 요청)
        composeRule.onNode(hasText(string(R.string.layer_duplicate))).assertIsDisplayed()
        composeRule.onNode(hasText(string(R.string.layer_add_menu_adjustment))).assertDoesNotExist()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).performClick()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("select 0", "duplicate 0"), fake.calls) }
    }

    @Test
    fun textLayer_hasABadge_andTheMenuConvertsItToPixels() {
        // 사용자 피드백 8차 1-6: 다시 고칠 수 있는 글자 레이어는 작은 그림 구석에 글자 표시, 메뉴에 '픽셀로 바꾸기'
        val fake = FakeLayers(2)
        val spec = TextSpec("Hi", x = 10f, y = 10f, style = TextStyle(size = 40f), width = 50f, height = 48f)
        fake.layers = fake.layers.map { if (it.id == LayerId(1)) it.copy(text = spec) else it }
        openCompactLayers(fake)

        // 표시는 줄의 화면 읽기 정보에 합쳐져(줄이 '글자 레이어'로 읽힘) 합치기 전 노드에서 찾는다
        composeRule.onNodeWithTag(EditScreenTags.layerTextBadge(LayerId(1)), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.layerTextBadge(LayerId(0)), useUnmergedTree = true).assertDoesNotExist()

        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_RASTERIZE).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals("rasterize 1", fake.calls.last()) }
        composeRule.onNodeWithTag(EditScreenTags.layerTextBadge(LayerId(1)), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun liquifyLayer_hasABadge_andTheMenuBakesIt() {
        // 다시 고칠 수 있는 유동화(결정 기록 '얼굴 인식 보정' 3단계)도 작은 그림 구석에 표시, 메뉴의 '픽셀로 바꾸기'로 굳힌다
        val fake = FakeLayers(2)
        fake.layers = fake.layers.map { if (it.id == LayerId(0)) it.copy(liquify = true) else it }
        openCompactLayers(fake)

        composeRule.onNodeWithTag(EditScreenTags.layerLiquifyBadge(LayerId(0)), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.layerLiquifyBadge(LayerId(1)), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.layerTextBadge(LayerId(0)), useUnmergedTree = true).assertDoesNotExist()

        row(0).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_RASTERIZE).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals("rasterize 0", fake.calls.last()) }
        composeRule.onNodeWithTag(EditScreenTags.layerLiquifyBadge(LayerId(0)), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun maskMenu_addsAMask_thenOffersInvertAndDelete() {
        // 레이어 마스크(결정 기록 '피부 보정'의 1d): 없으면 '마스크 더하기', 있으면 '마스크 반전'·'마스크 지우기'
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_INVERT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_ADD).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("addMask 1", fake.calls.last()) }
        composeRule.onNodeWithTag(EditScreenTags.layerMask(LayerId(1))).assertIsDisplayed()

        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_ADD).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_INVERT).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("invertMask 1", fake.calls.last()) }

        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_DELETE).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("deleteMask 1", fake.calls.last()) }
        composeRule.onNodeWithTag(EditScreenTags.layerMask(LayerId(1))).assertDoesNotExist()
    }

    @Test
    fun maskThumbnail_paintsTheMask_andTheLayerThumbnailPaintsThePixels() {
        val fake = FakeLayers(2)
        fake.layers = fake.layers.map { if (it.id == LayerId(0)) it.copy(hasMask = true) else it }
        openCompactLayers(fake)

        composeRule.onNodeWithTag(EditScreenTags.layerMask(LayerId(1))).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.layerMask(LayerId(0))).performClick()
        composeRule.runOnIdle { assertEquals("editMask 0", fake.calls.last()) }

        composeRule.onNodeWithTag(EditScreenTags.layerThumbnail(LayerId(0))).performClick()
        composeRule.runOnIdle { assertEquals("editPixels 0", fake.calls.last()) }
    }

    @Test
    fun adjustmentLayer_showsAnIcon_andTheMenuAddsOne_andNothingMergesOntoIt() {
        // 조정 레이어(결정 기록 '피부 보정'의 1f): 작은 그림 대신 조정 아이콘, 메뉴의 '조정 레이어 더하기', 그 위로는 합치지 않는다
        val fake = FakeLayers(2)
        fake.layers = fake.layers.map { if (it.id == LayerId(0)) it.copy(adjustment = true, hasMask = true) else it }
        openCompactLayers(fake)

        // 조정 레이어의 아이콘을 눌러도 픽셀을 칠하는 쪽으로 바꾸지 않는다(고르기만). 아이콘은 누르는 칸이 아니라 줄에 합쳐져 있다.
        composeRule.onNodeWithTag(EditScreenTags.layerThumbnail(LayerId(0)), useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertTrue(fake.calls.none { it.startsWith("editPixels") }) }
        // 조정 레이어는 마스크를 지우지 않는다(늘 마스크를 칠함)
        row(0).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_DELETE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_INVERT).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("invertMask 0", fake.calls.last()) }

        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        Espresso.pressBack()
        // 조정 레이어는 + 메뉴에서 더한다(2026-10-24 사용자 요청)
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD_MENU_ADJUSTMENT).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("addAdjustment 1", fake.calls.last()) }
        // 더하면 그 값을 고치게 레이어 시트 대신 조정이 열린다
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertDoesNotExist()
    }

    @Test
    fun pixelLayer_menuHasNoConvertItem() {
        // 픽셀로 바꾸기는 글자 레이어에만 보인다(2026-10-22 사용자 요청: "글자 레이어 에서만 해당 옵션이 보이게 해주세요")
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        row(0).performTouchInput { longClick() }

        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_RASTERIZE).assertDoesNotExist()
    }

    @Test
    fun rowLongPress_isInTheMenuGroup_andOpensAfterThreeTenthsOfASecond() {
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        // 0.3초보다 짧게 누르면 그냥 누르기(레이어 고르기)다
        row(0).performTouchInput {
            down(center)
            advanceEventTime(LongPressTimes.MENU_MILLIS - 50)
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertDoesNotExist()
        // 사용자 요청: 메뉴·레이어 꾹 누르기 묶음은 0.3초(휴대폰 설정 0.4초보다 빠름)
        row(0).performTouchInput {
            down(center)
            advanceEventTime(LongPressTimes.MENU_MILLIS + 20)
            up()
        }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("select 0", "select 0"), fake.calls) }
    }

    @Test
    fun menuItems_sendTheirWorkForThePressedLayer() {
        val fake = FakeLayers(3)
        openCompactLayers(fake)

        for (item in listOf(EditScreenTags.LAYER_MENU_MERGE_DOWN, EditScreenTags.LAYER_MENU_DELETE)) {
            row(1).performTouchInput { longClick() }
            composeRule.onNodeWithTag(item).performClick()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("select 1", "mergeDown 1", "select 1", "delete 1"), fake.calls)
        }
    }

    @Test
    fun zeroOpacityBelow_stillMerges_andOneLayerTurnsOffDeleting() {
        val fake = FakeLayers(2)
        fake.layers = fake.layers.mapIndexed { index, layer -> if (index == 0) layer.copy(opacity = 0f) else layer }
        openCompactLayers(fake)

        row(1).performTouchInput { longClick() }
        // 아래 레이어가 0%여도(눈은 켜짐) 아래와 병합은 된다
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsEnabled()
        composeRule.runOnIdle { fake.layers = listOf(fake.layers.last()) }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DELETE).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertIsEnabled()
    }

    @Test
    fun eye_hidesAndShowsTheLayer_withoutPickingIt() {
        val fake = FakeLayers(2)
        openCompactLayers(fake)
        val eye = composeRule.onNodeWithTag(EditScreenTags.layerVisibility(LayerId(0)))

        eye.assertIsOn().performClick()

        eye.assertIsOff()
        // 숨긴 줄은 화면 읽기 기능에 '숨김'으로 읽힌다
        row(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, composeRule.activity.getString(R.string.layer_hidden)))
        eye.performClick()
        eye.assertIsOn()
        composeRule.runOnIdle {
            assertEquals(listOf("visible 0 false", "visible 0 true"), fake.calls)
            // 눈을 눌러도 고른 레이어는 그대로다
            assertEquals(LayerId(1), fake.active)
        }
    }

    @Test
    fun hiddenLayers_turnOffMergeDown() {
        val fake = FakeLayers(3)
        fake.layers = fake.layers.mapIndexed { index, layer -> if (index == 1) layer.copy(visible = false) else layer }
        openCompactLayers(fake)

        // 아래 레이어를 숨겼으면 아래와 병합은 꺼진다
        row(2).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        Espresso.pressBack()

        // 숨긴 레이어 자신도 아래와 합치지 않는다
        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(listOf("select 2", "select 1"), fake.calls) }
    }

    @Test
    fun accessibilityLongClick_opensTheMenu() {
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        row(0).performSemanticsAction(SemanticsActions.OnLongClick)

        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(LayerId(0), fake.active) }
    }

    @Test
    fun expanded_longPressOpensTheMenuInTheFloatingPanel() {
        val fake = FakeLayers(2)
        show(fake, 930, 700)
        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).performClick()

        composeRule.onNode(hasTestTag(EditScreenTags.layerRow(LayerId(1))) and hasAnyAncestor(hasTestTag(EditScreenTags.FLOATING_PANEL)))
            .performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf("select 1", "mergeDown 1"), fake.calls) }
    }

    @Test
    fun medium_layersPanel_hasTheToolbarInTheHeader() {
        val fake = FakeLayers(2)
        show(fake, 700, 900)

        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.LAYER_PANEL) and hasAnyAncestor(hasTestTag(EditScreenTags.FLOATING_PANEL)))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD).assertIsDisplayed()
    }

    @Test
    fun addMenuPhoto_asksTheAppForAPhoto() {
        // 사진 넣기는 + 메뉴의 '사진'(2026-10-24 사용자 요청, 전에는 + 오른쪽 버튼)
        val fake = FakeLayers(1)
        openCompactLayers(fake)
        // 넓은 커버 시트에는 제목도 보인다
        composeRule.onNode(hasText(composeRule.activity.getString(R.string.tool_layers)) and hasAnyAncestor(hasTestTag(EditScreenTags.COVER_LAYER_SHEET)))
            .assertIsDisplayed()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD_MENU_PHOTO).performClick()
        composeRule.runOnIdle {
            assertEquals(1, photoRequests)
            // 레이어 일은 앱이 사진을 고른 뒤에 한다
            assertTrue(fake.calls.isEmpty())
        }
    }

    /** 아래부터 0, [1] 그룹 2 — 그룹 2 안에 레이어 1(결정 기록 '피부 보정'의 1h). 그룹을 고른다. */
    private fun groupedFake(): FakeLayers {
        val fake = FakeLayers(3)
        fake.layers = listOf(
            FakeLayers.summary(0),
            FakeLayers.summary(1).copy(parent = LayerId(2)),
            FakeLayers.summary(2).copy(name = "Group 2", group = true)
        )
        return fake
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun groupRows_indentTheirLayers_andTheFolderCollapsesAndExpands() {
        val fake = groupedFake()
        openCompactLayers(fake)
        val inside = composeRule.onNodeWithTag(EditScreenTags.layerThumbnail(LayerId(1)), useUnmergedTree = true).fetchSemanticsNode().layoutBoundsInWindow()
        val outside = composeRule.onNodeWithTag(EditScreenTags.layerThumbnail(LayerId(0)), useUnmergedTree = true).fetchSemanticsNode().layoutBoundsInWindow()

        // 그룹 안의 줄은 들여 쓴다
        assertTrue("$inside should be indented from $outside", inside.left > outside.left + 4f)
        // 폴더 아이콘을 누르면 접혀 안의 줄이 빠지고, 다시 누르면 펼친다(문서는 그대로)
        composeRule.onNodeWithTag(EditScreenTags.layerGroupToggle(LayerId(2))).performClick()
        row(1).assertDoesNotExist()
        row(2).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                listOf(string(R.string.layer_selected), string(R.string.layer_group_label), string(R.string.layer_group_collapsed)).joinToString(", ")
            )
        )
        composeRule.onNodeWithTag(EditScreenTags.layerGroupToggle(LayerId(2))).performClick()
        row(1).assertExists()
        composeRule.runOnIdle { assertTrue(fake.calls.isEmpty()) }
    }

    @Test
    fun groupMenu_ungroupsAGroup_groupsALayer_andTurnsOffWhatAGroupCannotDo() {
        val fake = groupedFake()
        openCompactLayers(fake)

        // 그룹은 통과 그룹뿐이라 혼합 모드는 '통과'로 꺼져 있다
        composeRule.onNodeWithTag(EditScreenTags.LAYER_BLEND).assertIsNotEnabled().assertTextContains(string(R.string.blend_pass_through))
        // 그룹 줄: 풀기가 있고, 마스크 더하기는 없고, 아래와 병합은 꺼진다
        row(2).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MASK_ADD).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_UNGROUP).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("ungroup 2", fake.calls.last()) }

        // 그룹 안의 레이어: 그룹 밖 아래 레이어와는 합치지 않는다. 풀기는 없고 묶기는 된다
        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_UNGROUP).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_GROUP).assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("group 1", fake.calls.last()) }
    }

    @Test
    fun deleting_isOffWhenTheChosenGroupHoldsEveryLayer() {
        val fake = FakeLayers(2)
        fake.layers = listOf(FakeLayers.summary(0).copy(parent = LayerId(1)), FakeLayers.summary(1).copy(group = true))
        openCompactLayers(fake)

        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DELETE).assertIsNotEnabled()
        Espresso.pressBack()
        // 그룹 안의 레이어는 지울 수 있다
        row(0).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DELETE).assertIsEnabled()
    }

    @Test
    fun draggingBelowAGroup_putsTheLayerInside_andAGroupMovesWithItsLayers() {
        // 아래부터 0, [1] 그룹 2, 3
        val fake = groupedFake()
        fake.layers = fake.layers + FakeLayers.summary(3)
        fake.active = LayerId(3)
        openCompactLayers(fake)
        // 줄이 넷이라 반 높이 시트에서는 맨 아래 줄이 잘려 보인다. 줄 높이는 잘리지 않은 크기로 잰다.
        val rowHeight = row(3).fetchSemanticsNode().size.height.toFloat()

        // 맨 위 3을 한 줄 아래(펼친 그룹 2 바로 아래)로 끌면 그룹 2 안 맨 위로 들어간다
        composeRule.onNodeWithTag(EditScreenTags.layerHandle(LayerId(3)), useUnmergedTree = true).performTouchInput {
            swipe(start = center, end = center + Offset(0f, rowHeight), durationMillis = 500)
        }
        composeRule.runOnIdle {
            assertEquals(listOf("move 3 to 2 in 2"), fake.calls)
            assertEquals(listOf(0L to null, 1L to 2L, 3L to 2L, 2L to null), fake.layers.map { it.id.value to it.parent?.value })
        }

        // 맨 위가 된 그룹 2를 한 줄 아래로 끌면 안의 레이어와 함께 0 아래로 간다
        composeRule.onNodeWithTag(EditScreenTags.layerHandle(LayerId(2)), useUnmergedTree = true).performTouchInput {
            swipe(start = center, end = center + Offset(0f, rowHeight), durationMillis = 500)
        }
        composeRule.runOnIdle {
            assertEquals("move 2 to 2", fake.calls.last())
            assertEquals(listOf(1L, 3L, 2L, 0L), fake.layers.map { it.id.value })
        }
    }

    @Test
    fun accessibilityActions_stepOutOfAndIntoAGroup() {
        val fake = groupedFake()
        openCompactLayers(fake)
        val up = string(R.string.layer_move_up)
        val down = string(R.string.layer_move_down)
        fun act(id: Long, label: String) {
            composeRule.waitForIdle()
            val action = row(id.toInt()).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
            composeRule.runOnIdle { action.action() }
        }

        // 그룹의 맨 아래 1의 '아래로': 그룹 밖(자리는 그대로)
        act(1, down)
        composeRule.runOnIdle { assertEquals("move 1 to 1", fake.calls.last()) }
        // 빈 그룹 바로 아래 1의 '위로': 그 그룹 안으로
        act(1, up)
        composeRule.runOnIdle { assertEquals("move 1 to 1 in 2", fake.calls.last()) }
        // 그룹 바로 아래 0의 '위로': 그룹 안 맨 아래
        act(0, up)
        composeRule.runOnIdle {
            assertEquals("move 0 to 0 in 2", fake.calls.last())
            assertEquals(listOf(0L to 2L, 1L to 2L, 2L to null), fake.layers.map { it.id.value to it.parent?.value })
        }
    }

    @Test
    fun main_layersPanel_dropsItsTitleWhenTheHeaderLeavesNoRoom_butKeepsThePaneName() {
        val fake = FakeLayers(2)
        show(fake, 930, 700)
        composeRule.togglePanel(MainPanel.LAYERS)
        val title = composeRule.activity.getString(R.string.tool_layers)

        // 패널 폭(280dp)에 혼합 모드와 버튼 셋이 다 보이고, 한 줄로 들어가지 않는 제목은 두지 않는다
        for (tag in listOf(EditScreenTags.LAYER_BLEND, EditScreenTags.LAYER_ADD, EditScreenTags.VIEW_FLIP_VERTICAL, EditScreenTags.VIEW_FLIP_HORIZONTAL)) {
            composeRule.onNodeWithTag(tag).assertIsDisplayed()
        }
        composeRule.onNode(hasText(title) and hasAnyAncestor(hasTestTag(EditScreenTags.FLOATING_PANEL))).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.FLOATING_PANEL)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title))
        // 버튼이 하나인 조정 패널은 제목이 그대로 보인다
        composeRule.togglePanel(MainPanel.ADJUST)
        composeRule.onNode(hasText(composeRule.activity.getString(R.string.tool_adjust)) and hasAnyAncestor(hasTestTag(EditScreenTags.FLOATING_PANEL)))
            .assertIsDisplayed()
    }
}
