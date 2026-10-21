package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** 선택 도구의 옵션, 캔버스에서 끌기·누르기, 윤곽선 표시를 확인한다(엔진 대신 받은 일만 적는 가짜). */
@RunWith(AndroidJUnit4::class)
class SelectionToolTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())

    /** 받은 선택 일 */
    private class FakeSelection : SelectionActions {
        val calls = mutableListOf<String>()
        val shapes = mutableListOf<SelectionShape>()
        val points = mutableListOf<Pair<Float, Float>>()

        override fun shape(shape: SelectionShape) {
            calls += "shape"
            shapes += shape
        }

        override fun similar(x: Float, y: Float) {
            calls += "similar"
            points += x to y
        }

        override fun all() {
            calls += "all"
        }

        override fun none() {
            calls += "none"
        }

        override fun invert() {
            calls += "invert"
        }

        override fun delete() {
            calls += "delete"
        }

        override fun copyToLayer(cut: Boolean) {
            calls += if (cut) "cut" else "copy"
        }

        override fun aiFill() {
            calls += "aiFill"
        }

        override fun objectAt(x: Float, y: Float) {
            calls += "object"
            points += x to y
        }

        /** AI 선택을 고를 때 미리 읽기를 부른 횟수 */
        var prepares = 0

        override fun prepareObjects() {
            prepares++
        }
    }

    private val actions = FakeSelection()
    private var options by mutableStateOf(SelectionOptions())

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)
    private val preview = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)

    /** 문서 (200, 200)-(600, 600) 사각형 윤곽선 */
    private val square = SelectionOutline(listOf(floatArrayOf(200f, 200f, 600f, 200f, 600f, 600f, 200f, 600f, 200f, 200f)))

    /** 편집 화면에 보내는 알림(알림 막대) */
    private val messages = Channel<EditorMessage>(Channel.UNLIMITED)

    private fun show(widthDp: Int, heightDp: Int, outline: SelectionOutline? = null) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        EditScreen(
                            state = EditorState.Ready(
                                PreviewImage(preview, PreviewLevels.wholeRegion(document, 1)),
                                document,
                                layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
                                activeLayer = LayerId(0),
                                selection = outline
                            ),
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {},
                            messages = messages.receiveAsFlow(),
                            selectionOptions = options,
                            onSelectionOptions = { options = it },
                            selectionActions = actions
                        )
                    }
                }
            }
        }
    }

    private fun openCoverTool() {
        show(411, 800)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Select)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OPTIONS).assertIsDisplayed()
    }

    @Test
    fun cover_optionsChangeTheSettings() {
        openCoverTool()

        composeRule.onNodeWithTag(EditScreenTags.selectionMode(SelectionMode.ADD)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_FEATHER).performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        composeRule.runOnIdle {
            assertEquals(SelectionMode.ADD, options.mode)
            assertEquals(fractionToFeather(0.2f), options.feather, 0f)
        }

        // 자동 선택은 허용치와 이어진 곳만을 보인다
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.SELECT_WAND)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_FEATHER).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_TOLERANCE).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_CONTIGUOUS).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_CONTIGUOUS).assertIsOff()
        composeRule.runOnIdle {
            assertEquals(128, options.tolerance)
            assertEquals(false, options.contiguous)
        }
    }

    @Test
    fun actions_needASelection_exceptSelectAll() {
        openCoverTool()

        composeRule.onNodeWithTag(EditScreenTags.SELECTION_ALL).performScrollTo().assertIsEnabled().performClick()
        for (tag in listOf(EditScreenTags.SELECTION_NONE, EditScreenTags.SELECTION_INVERT, EditScreenTags.SELECTION_DELETE, EditScreenTags.SELECTION_COPY, EditScreenTags.SELECTION_CUT, EditScreenTags.SELECTION_AI_FILL)) {
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        }
        composeRule.runOnIdle { assertEquals(listOf("all"), actions.calls) }
    }

    @Test
    fun actions_withASelection_areSent() {
        show(411, 800, outline = square)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Select)).performClick()

        for (tag in listOf(EditScreenTags.SELECTION_INVERT, EditScreenTags.SELECTION_DELETE, EditScreenTags.SELECTION_COPY, EditScreenTags.SELECTION_CUT, EditScreenTags.SELECTION_AI_FILL, EditScreenTags.SELECTION_NONE)) {
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        }

        composeRule.runOnIdle { assertEquals(listOf("invert", "delete", "copy", "cut", "aiFill", "none"), actions.calls) }
    }

    /**
     * 닫힌 화면은 옵션 칸이 캔버스 아래쪽 위에 겹쳐 뜨므로(결정 기록 '닫힌 화면 도구 옵션') 보이는 위쪽 자리를 누른다.
     * 화면에 맞춘 1000×1000 문서에서 그 자리의 문서 좌표도 함께 돌려준다.
     */
    private fun TouchInjectionScope.visiblePoint(): Pair<Offset, Offset> {
        val point = Offset(centerX, height * 0.25f)
        val scale = minOf(width, height) / 1000f
        val left = (width - 1000f * scale) / 2f
        val top = (height - 1000f * scale) / 2f
        return point to Offset((point.x - left) / scale, (point.y - top) / scale)
    }

    @Test
    fun dragWithRectangle_sendsAPixelAlignedRectangle() {
        openCoverTool()

        var start = Offset.Zero
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput {
            val (point, document) = visiblePoint()
            start = document
            swipe(start = point, end = point + Offset(width * 0.25f, height * 0.15f), durationMillis = 300)
        }

        composeRule.runOnIdle {
            val shape = actions.shapes.single() as SelectionShape.Rectangle
            // 누른 자리의 문서 좌표에서 시작한다
            assertTrue("$shape from $start", abs(shape.left - start.x) < 30f && abs(shape.top - start.y) < 30f)
            assertTrue("$shape", shape.right > shape.left + 50f && shape.bottom > shape.top + 20f)
            assertEquals(shape.left, shape.left.roundToInt().toFloat(), 0f)
        }
    }

    @Test
    fun dragWithLasso_sendsTheFingerPath() {
        openCoverTool()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.SELECT_LASSO)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput {
            down(visiblePoint().first)
            moveBy(Offset(100f, 0f))
            moveBy(Offset(0f, 100f))
            moveBy(Offset(-100f, 0f))
            up()
        }

        composeRule.runOnIdle {
            val lasso = actions.shapes.single() as SelectionShape.Lasso
            assertTrue("${lasso.points.size} points", lasso.points.size >= 3)
        }
    }

    @Test
    fun tapWithoutDragging_deselectsInNewMode_andTheWandSamplesTheTappedPoint() {
        openCoverTool()

        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput { click(visiblePoint().first) }
        composeRule.runOnIdle { assertEquals(listOf("none"), actions.calls) }

        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.SELECT_WAND)).performClick()
        var tapped = Offset.Zero
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput {
            val (point, document) = visiblePoint()
            tapped = document
            click(point)
        }
        composeRule.runOnIdle {
            assertEquals(listOf("none", "similar"), actions.calls)
            val (x, y) = actions.points.single()
            assertTrue("$x, $y vs $tapped", abs(x - tapped.x) < 30f && abs(y - tapped.y) < 30f)
        }
    }

    @Test
    fun aiSelect_hasNoSliders_readsAhead_andATapSendsTheObjectPoint() {
        openCoverTool()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.SELECT_AI)).performClick()

        // 페더·허용치가 없고(설명 줄도 없다, 사용자 피드백 8차 1-7), 고르자마자 그림을 미리 읽는다
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_FEATHER).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_TOLERANCE).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue("${actions.prepares}", actions.prepares >= 1) }

        // 끌면 무시하고, 누르면 그 자리의 물체를 고른다
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput {
            val (point, _) = visiblePoint()
            swipe(start = point, end = point + Offset(width * 0.2f, 0f), durationMillis = 300)
        }
        var tapped = Offset.Zero
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput {
            val (point, document) = visiblePoint()
            tapped = document
            click(point)
        }
        composeRule.runOnIdle {
            assertEquals(listOf("object"), actions.calls)
            assertTrue(actions.shapes.isEmpty())
            val (x, y) = actions.points.single()
            assertTrue("$x, $y vs $tapped", abs(x - tapped.x) < 30f && abs(y - tapped.y) < 30f)
        }
    }

    @Test
    fun cover_theFiveSubToolNames_fitOnOneRow() {
        openCoverTool()

        // 칸이 좁으면 글자는 칸 폭으로 놓이고 넘친 부분이 잘려 보인다('Rectangl'). 글자 배치가 넘쳤는지 본다.
        for (subTool in EditTool.Select.subTools) {
            val label = composeRule.onNode(
                hasText(composeRule.activity.getString(subTool.shortLabel)) and hasAnyAncestor(hasTestTag(EditScreenTags.subToolOption(subTool))),
                useUnmergedTree = true
            ).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertFalse("$subTool label is cut", layouts.single().hasVisualOverflow)
        }
    }

    @Test
    fun tapInAddMode_doesNotDeselect() {
        options = SelectionOptions(mode = SelectionMode.ADD)
        openCoverTool()

        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput { click(center) }

        composeRule.runOnIdle { assertTrue(actions.calls.isEmpty()) }
    }

    @Test
    fun aSecondFinger_dropsTheShape() {
        openCoverTool()

        // 두 손가락 모두 옵션 칸이 덮지 않은 위쪽 캔버스를 누른다(상단 바가 낮아져 캔버스 가운데가 옵션 칸 위 끝에 가까워졌다)
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OVERLAY).performTouchInput {
            val (point, _) = visiblePoint()
            down(0, point)
            moveBy(0, Offset(60f, 0f))
            down(1, point + Offset(0f, 100f))
            moveBy(1, Offset(0f, 50f))
            up(1)
            up(0)
        }

        composeRule.runOnIdle { assertTrue(actions.calls.isEmpty()) }
    }

    @Test
    fun outline_isDrawnOnlyWithASelection() {
        show(411, 800)
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OUTLINE).assertDoesNotExist()
    }

    @Test
    fun outline_isDrawnWhateverToolIsChosen() {
        show(411, 800, outline = square)
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OUTLINE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OUTLINE).assertIsDisplayed()
    }

    @Test
    fun mainPortrait_stripChangesTheSettings_andAvoidsTheHorizontalHinge() {
        show(700, 900)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_OPTIONS).assertDoesNotExist()

        // 합치는 방법: 띠에 아이콘 넷이 늘 보이고, 누르면 바로 바뀐다(사용자 피드백 10차 1)
        for (mode in SelectionMode.entries) composeRule.onNodeWithTag(EditScreenTags.selectionMode(mode)).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.selectionMode(SelectionMode.ADD)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.selectionMode(SelectionMode.ADD)).assertIsOn()
        composeRule.onNodeWithTag(EditScreenTags.selectionMode(SelectionMode.REPLACE)).assertIsOff()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.strip("feather")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_FEATHER).performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        composeRule.runOnIdle {
            assertEquals(SelectionMode.ADD, options.mode)
            assertEquals(fractionToFeather(0.2f), options.feather, 0f)
        }

        // 자동 선택은 허용치 칸이고, 그 창에 '이어진 곳만'이 있다
        // 하위 도구는 고른 도구를 다시 눌러 연 메뉴에서 고른다(띠에는 하위 도구 칸이 없다)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.SELECT_WAND)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.strip("feather")).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.strip("tolerance")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_TOLERANCE).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_CONTIGUOUS).performClick()
        composeRule.runOnIdle {
            assertEquals(128, options.tolerance)
            assertEquals(false, options.contiguous)
        }

        // 합치는 방법 첫 칸 한가운데를 가로로 지나는 힌지
        val y = composeRule.onNodeWithTag(EditScreenTags.selectionMode(SelectionMode.REPLACE)).fetchSemanticsNode().layoutBoundsInWindow().center.y.roundToInt()
        val hinge = Hinge(left = 0, top = y, right = 10_000, bottom = y, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
    }

    @Test
    fun expanded_aiSelectStrip_hasTheModes_butNoSlider_andTheBarHasTheActions() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.SELECT_AI)).performClick()

        for (mode in SelectionMode.entries) {
            composeRule.onNode(hasTestTag(EditScreenTags.selectionMode(mode)) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(EditScreenTags.strip("feather")).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.strip("tolerance")).assertDoesNotExist()
        // 수치 칸이 없어 띠 맨 끝에 구분선이 남지 않는다(검토에서 찾음): 마지막 칸 아래는 칸 바탕 둘레와 띠의 아래 여백(약 9dp)뿐이고,
        // 구분선이 남으면 간격·구분선만큼(11dp) 더 내려간다
        val strip = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode()
        val lastCell = SelectionMode.entries.maxOf { mode ->
            composeRule.onNode(hasTestTag(EditScreenTags.selectionMode(mode)) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP)))
                .fetchSemanticsNode().layoutBoundsInWindow().bottom
        }
        val dp = with(strip.layoutInfo.density) { 1.dp.toPx() }
        assertTrue("strip ends ${(strip.layoutBoundsInWindow().bottom - lastCell) / dp}dp below its last cell", strip.layoutBoundsInWindow().bottom - lastCell < 14 * dp)
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_ACTION_BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_ALL).assertIsEnabled()
        composeRule.runOnIdle { assertTrue("${actions.prepares}", actions.prepares >= 1) }
    }

    @Test
    fun expanded_barActions_needASelection() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_ALL).assertIsEnabled()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_INVERT).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_AI_FILL).assertIsNotEnabled()
        // 띠에는 동작이 없다(아래 작업 표시줄에 있다)
        composeRule.onNode(hasTestTag(EditScreenTags.SELECTION_ALL) and hasAnyAncestor(hasTestTag(EditScreenTags.OPTION_STRIP))).assertDoesNotExist()
    }

    @Test
    fun expanded_withASelection_barActionsAreSent() {
        show(930, 700, outline = square)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()

        for (tag in listOf(EditScreenTags.SELECTION_INVERT, EditScreenTags.SELECTION_DELETE, EditScreenTags.SELECTION_COPY, EditScreenTags.SELECTION_CUT, EditScreenTags.SELECTION_AI_FILL, EditScreenTags.SELECTION_NONE)) {
            composeRule.onNodeWithTag(tag).performClick()
        }

        composeRule.runOnIdle { assertEquals(listOf("invert", "delete", "copy", "cut", "aiFill", "none"), actions.calls) }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()

    private val barActionTags = listOf(
        EditScreenTags.SELECTION_ALL, EditScreenTags.SELECTION_NONE, EditScreenTags.SELECTION_INVERT, EditScreenTags.SELECTION_DELETE,
        EditScreenTags.SELECTION_COPY, EditScreenTags.SELECTION_CUT, EditScreenTags.SELECTION_AI_FILL
    )

    @Test
    fun expanded_actionBar_sitsAtTheBottomCenterOfTheScreen_inOneRow() {
        // 사용자 피드백 10차 1: "그외 액션은 화면 중앙 하단에 가로로 보여주세요."
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()

        val node = composeRule.onNodeWithTag(EditScreenTags.SELECTION_ACTION_BAR).fetchSemanticsNode()
        val dp = with(node.layoutInfo.density) { 1.dp.toPx() }
        val bar = node.layoutBoundsInWindow()
        val screen = bounds(EditScreenTags.TOP_BAR)
        val canvas = bounds(EditScreenTags.CANVAS)
        assertEquals(screen.center.x, bar.center.x, 2 * dp)
        assertEquals(canvas.bottom - 8 * dp, bar.bottom, 2 * dp)
        // 버튼 일곱이 카드 안 한 줄에, 왼쪽부터 차례로
        val buttons = barActionTags.map { bounds(it) }
        for (button in buttons) assertTrue("$button should be inside $bar", button.left >= bar.left - 1 && button.right <= bar.right + 1 && button.top >= bar.top - 1 && button.bottom <= bar.bottom + 1)
        assertEquals(buttons.map { it.left }.sorted(), buttons.map { it.left })
    }

    @Test
    fun expanded_actionBar_staysOneCenteredRow_evenAcrossACenterHinge() {
        // 사용자 피드백 11차 1: 접히는 선을 비켜 가운데를 비워 두지 않는다(전에는 선 양쪽으로 나눴다)
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()
        val before = barActionTags.map { bounds(it) }
        // 펼친 폴드처럼 화면 가운데를 세로로 지나는 힌지
        val centerX = bounds(EditScreenTags.TOP_BAR).center.x.roundToInt()
        val hinge = Hinge(left = centerX, top = 0, right = centerX, bottom = 10_000, isVertical = true)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }
        composeRule.waitForIdle()

        val buttons = barActionTags.map { bounds(it) }
        assertEquals("the hinge should not move the buttons", before, buttons)
        // 버튼 사이가 모두 같다(가운데가 비지 않음)
        val gaps = buttons.zipWithNext { a, b -> b.left - a.right }
        assertTrue("$gaps", gaps.max() - gaps.min() < 2f)
        val row = (buttons.first().left + buttons.last().right) / 2
        assertEquals(centerX.toFloat(), row, 4f)
    }

    @Test
    fun expanded_messageBar_sitsAboveTheActionBar() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()

        composeRule.runOnIdle { messages.trySend(EditorMessage.HiddenLayer) }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.edit_hidden_layer)).assertExists()

        val message = bounds(EditScreenTags.MESSAGE_BAR)
        val bar = bounds(EditScreenTags.SELECTION_ACTION_BAR)
        assertTrue("message $message should be above the bar $bar", message.bottom <= bar.top + 1)
    }

    @Test
    fun actionBar_isOnlyForTheSelectionTool() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_ACTION_BAR).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Select)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_ACTION_BAR).assertIsDisplayed()
    }

    @Test
    fun cover_keepsTheActionsInTheOptions_withoutTheBar() {
        openCoverTool()
        composeRule.onNodeWithTag(EditScreenTags.SELECTION_ACTION_BAR).assertDoesNotExist()
        composeRule.onNode(hasTestTag(EditScreenTags.SELECTION_ALL) and hasAnyAncestor(hasTestTag(EditScreenTags.SELECTION_OPTIONS))).assertExists()
    }
}
