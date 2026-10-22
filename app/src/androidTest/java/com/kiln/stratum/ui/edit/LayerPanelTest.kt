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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.components.LongPressTimes
import com.kiln.stratum.ui.layout.DIALOG_CARD
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import com.kiln.stratum.engine.text.TextSpec
import com.kiln.stratum.engine.text.TextStyle
import kotlin.math.abs
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

        override fun mergeVisible() {
            calls += "mergeVisible"
        }

        override fun move(id: LayerId, toIndex: Int) {
            calls += "move ${id.value} to $toIndex"
            val from = layers.indexOfFirst { it.id == id }
            layers = layers.toMutableList().apply { add(toIndex, removeAt(from)) }
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
            layers = layers.map { if (it.id == active) it.copy(text = null) else it }
        }

        override fun rename(id: LayerId, name: String) {
            calls += "rename ${id.value} $name"
            layers = layers.map { if (it.id == id) it.copy(name = name) else it }
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
    fun compact_sheetCoversTheHintBar_risesAboveAToolsBar_andKeepsTheCanvas() {
        show(FakeLayers(2), 411, 800)
        val canvas = bounds(EditScreenTags.CANVAS)
        val bar = bounds(EditScreenTags.COVER_TOOL_BAR)
        composeRule.togglePanel(MainPanel.LAYERS)

        // 도구를 고르지 않아 막대가 안내 글뿐이면 시트가 막대 자리까지 덮는다(2026-10-22 사용자 요청). 캔버스 크기는 그대로다.
        var sheet = bounds(EditScreenTags.COVER_LAYER_SHEET)
        assertEquals(bar.bottom, sheet.bottom, 1f)
        assertTrue("$sheet should start inside $canvas", sheet.top > canvas.top)
        assertEquals(canvas, bounds(EditScreenTags.CANVAS))

        // 더보기 줄을 열면 막대 자리가 더보기 줄이 되고, 시트는 그 위로 올라간다(둘 다 보인다)
        composeRule.onNodeWithTag(EditScreenTags.MORE_TAB).performClick()
        composeRule.waitForIdle()
        assertEquals(bar, bounds(EditScreenTags.COVER_MORE_TOOLS))
        assertEquals(canvas.bottom, bounds(EditScreenTags.COVER_LAYER_SHEET).bottom, 1f)

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
        // 도구가 없으면 시트가 안내 글 막대 자리까지 덮어 그만큼 더 길다(위 끝 자리는 막대 위에 뜰 때와 같다).
        openCompactLayers(FakeLayers(12))
        val canvas = bounds(EditScreenTags.CANVAS)
        val bar = bounds(EditScreenTags.COVER_TOOL_BAR)
        val half = bounds(EditScreenTags.COVER_LAYER_SHEET)
        assertTrue("$half should be at most about half of $canvas (+ the bar $bar)", half.height <= canvas.height * 0.56f + bar.height)

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
    fun toolbar_followsTheSelection_andHasTheViewFlipsInsteadOfUpDown() {
        val fake = FakeLayers(1)
        openCompactLayers(fake)
        // 한 장뿐이면 지울 수 없다
        composeRule.onNodeWithTag(EditScreenTags.LAYER_DELETE).assertIsNotEnabled()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_DELETE).assertIsEnabled()
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
    fun blendMenu_setsTheMode() {
        val fake = FakeLayers(2)
        openCompactLayers(fake)

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
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_VISIBLE).assertIsEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DELETE).assertIsEnabled()

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
    fun pixelLayer_menuHasNoConvertItem() {
        // 픽셀로 바꾸기는 글자 레이어에만 보인다(2026-10-22 사용자 요청: "글자 레이어 에서만 해당 옵션이 보이게 해주세요")
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        row(0).performTouchInput { longClick() }

        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_DUPLICATE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_RASTERIZE).assertDoesNotExist()
    }

    @Test
    fun rename_opensAPromptWithTheName_andSendsTheCleanedName() {
        // 2026-10-22 사용자 요청: "옵션에 레이어 이름 변경 옵션도 넣으려 합니다"(메뉴 맨 위, 커버는 아래 시트)
        val fake = FakeLayers(2)
        openCompactLayers(fake)

        row(0).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_RENAME).assertIsEnabled().performClick()

        val field = composeRule.onNodeWithTag(EditScreenTags.LAYER_RENAME_FIELD)
        field.assertTextContains("Layer 0")
        field.performTextReplacement("  Sky \n  blue ")
        composeRule.onNodeWithTag(EditScreenTags.LAYER_RENAME_OK).performClick()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_RENAME_FIELD).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals("rename 0 Sky blue", fake.calls.last()) }
    }

    @Test
    fun rename_onTheMainScreen_isACenteredDialog_andABlankNameTurnsOffOk() {
        val fake = FakeLayers(2)
        show(fake, 930, 700)
        composeRule.togglePanel(MainPanel.LAYERS)

        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_RENAME).performClick()

        // 펼친 화면은 아래 시트가 아니라 화면 가운데의 창이다(키보드가 열리기 전에 잰다)
        composeRule.waitForIdle()
        val card = composeRule.onNodeWithTag(DIALOG_CARD).fetchSemanticsNode().layoutBoundsInWindow()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("card $card not centered in $screen", abs(card.center.x - screen.center.x) <= 2f)

        composeRule.onNodeWithTag(EditScreenTags.LAYER_RENAME_FIELD).performTextReplacement("   ")
        composeRule.onNodeWithTag(EditScreenTags.LAYER_RENAME_OK).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_RENAME_FIELD).performTextReplacement("Sky")
        // 접은 에뮬레이터에 큰 화면을 줄여 넣으면 키보드가 창을 거의 덮어 누르는 자리가 빗나간다(기술 주의점). 누르기 동작을 바로 부른다
        composeRule.onNodeWithTag(EditScreenTags.LAYER_RENAME_OK).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals("rename 1 Sky", fake.calls.last()) }
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

        for (item in listOf(EditScreenTags.LAYER_MENU_MERGE_DOWN, EditScreenTags.LAYER_MENU_MERGE_VISIBLE, EditScreenTags.LAYER_MENU_DELETE)) {
            row(1).performTouchInput { longClick() }
            composeRule.onNodeWithTag(item).performClick()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("select 1", "mergeDown 1", "select 1", "mergeVisible", "select 1", "delete 1"), fake.calls)
        }
    }

    @Test
    fun oneVisibleLayer_turnsOffMerging_andOneLayerTurnsOffDeleting() {
        val fake = FakeLayers(2)
        fake.layers = fake.layers.mapIndexed { index, layer -> if (index == 0) layer.copy(opacity = 0f) else layer }
        openCompactLayers(fake)

        row(1).performTouchInput { longClick() }
        // 아래 레이어가 0%여도(눈은 켜짐) 아래와 병합은 된다. 보이는 레이어는 하나뿐이다.
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_VISIBLE).assertIsNotEnabled()
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
    fun hiddenLayers_turnOffMergeDown_andDoNotCountAsVisible() {
        val fake = FakeLayers(3)
        fake.layers = fake.layers.mapIndexed { index, layer -> if (index == 1) layer.copy(visible = false) else layer }
        openCompactLayers(fake)

        // 아래 레이어를 숨겼으면 아래와 병합은 꺼진다. 보이는 레이어는 둘이라 보이는 레이어 병합은 된다.
        row(2).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_VISIBLE).assertIsEnabled().performClick()

        // 숨긴 레이어 자신도 아래와 합치지 않는다
        row(1).performTouchInput { longClick() }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_DOWN).assertIsNotEnabled()
        // 하나를 더 숨기면 보이는 레이어가 하나뿐이다
        composeRule.runOnIdle { fake.layers = fake.layers.mapIndexed { index, layer -> if (index == 0) layer.copy(visible = false) else layer } }
        composeRule.onNodeWithTag(EditScreenTags.LAYER_MENU_MERGE_VISIBLE).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(listOf("select 2", "mergeVisible", "select 1"), fake.calls) }
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
    fun addPhoto_sitsRightOfAdd_andAsksTheAppForAPhoto() {
        // 사용자 요청: "레이어 패널의 + 오른쪽에 이미지 아이콘이나 카메라 아이콘으로 사진 추가 가능하도록 하세요."
        val fake = FakeLayers(1)
        openCompactLayers(fake)
        val add = bounds(EditScreenTags.LAYER_ADD)
        val photo = bounds(EditScreenTags.LAYER_ADD_PHOTO)
        assertEquals(add.center.y, photo.center.y, 1f)
        assertTrue("$photo should be right next to $add", photo.left >= add.right - 1f && photo.left - add.right < add.width / 2)
        // 넓은 커버 시트에는 제목도 보인다
        composeRule.onNode(hasText(composeRule.activity.getString(R.string.tool_layers)) and hasAnyAncestor(hasTestTag(EditScreenTags.COVER_LAYER_SHEET)))
            .assertIsDisplayed()

        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD_PHOTO).performClick()
        composeRule.runOnIdle {
            assertEquals(1, photoRequests)
            // 레이어 일은 앱이 사진을 고른 뒤에 한다
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test
    fun main_layersPanel_dropsItsTitleWhenTheFiveButtonsLeaveNoRoom_butKeepsThePaneName() {
        val fake = FakeLayers(2)
        show(fake, 930, 700)
        composeRule.togglePanel(MainPanel.LAYERS)
        val title = composeRule.activity.getString(R.string.tool_layers)

        // 패널 폭(280dp)에 버튼 다섯이 다 보이고, 한 줄로 들어가지 않는 제목은 두지 않는다
        for (tag in listOf(EditScreenTags.LAYER_ADD, EditScreenTags.LAYER_ADD_PHOTO, EditScreenTags.LAYER_DELETE, EditScreenTags.VIEW_FLIP_VERTICAL, EditScreenTags.VIEW_FLIP_HORIZONTAL)) {
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
