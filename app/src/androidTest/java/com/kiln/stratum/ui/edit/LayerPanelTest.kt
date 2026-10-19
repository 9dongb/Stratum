package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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

/**
 * 레이어 패널을 세 배치(커버 탭, 메인 세로 아래 패널의 탭, 메인 가로 오른쪽 패널의 칸)에서 확인한다.
 * 엔진 대신 레이어 목록만 들고 있는 가짜([FakeLayers])로 버튼·목록·슬라이더·메뉴가 보내는 일을 본다.
 */
@RunWith(AndroidJUnit4::class)
class LayerPanelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 화면이 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())

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

        companion object {
            fun summary(index: Int) = LayerSummary(LayerId(index.toLong()), "Layer $index", 1f, BlendMode.NORMAL, Adjustments.NONE)
        }
    }

    private val document = PixelSize(400, 300)
    private val preview = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)

    private fun show(fake: FakeLayers, widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
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
                            layerActions = fake
                        )
                    }
                }
            }
        }
    }

    private fun openCompactLayers(fake: FakeLayers) {
        show(fake, 411, 800)
        // 탭 줄 끝에 있어 커버 폭에서는 밀어야 보인다
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Layers)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_PANEL).assertIsDisplayed()
    }

    private fun row(index: Int) = composeRule.onNodeWithTag(EditScreenTags.layerRow(LayerId(index.toLong())))

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
        // 사용자 요청: 위로·아래로 버튼 자리에 화면 좌우·상하 반전(보기만 뒤집어 레이어 일을 보내지 않음)
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
    fun medium_layersPanel_hasTheToolbarInTheHeader_andAvoidsTheHinge() {
        val fake = FakeLayers(2)
        show(fake, 700, 900)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val middle = screen.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = middle, right = screen.right.roundToInt(), bottom = middle, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.onNodeWithTag(EditScreenTags.panelToggle(MainPanel.LAYERS)).performClick()
        composeRule.onNode(hasTestTag(EditScreenTags.LAYER_PANEL) and hasAnyAncestor(hasTestTag(EditScreenTags.FLOATING_PANEL)))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.LAYER_ADD).assertIsDisplayed()

        composeRule.assertClickablesAvoid(hinge)
    }
}
