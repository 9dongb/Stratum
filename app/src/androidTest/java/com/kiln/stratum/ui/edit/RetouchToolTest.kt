package com.kiln.stratum.ui.edit

import com.kiln.stratum.ui.components.HELP_TOOLTIP_TAG
import androidx.compose.ui.test.assertContentDescriptionEquals
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.layout.touchBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 리터칭 도구의 옵션·캔버스 터치(가져올 곳 정하기·칠하기)와, 커버 탭 줄을 밀어 보는 것을 확인한다(엔진 대신 받은 일만 적는 가짜). */
@RunWith(AndroidJUnit4::class)
class RetouchToolTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())

    /** 받은 일 */
    private class FakeRetouch : RetouchActions {
        val calls = mutableListOf<String>()
        val points = mutableListOf<Pair<Float, Float>>()
        var mode: RetouchMode? = null

        override fun setSource(x: Float, y: Float) {
            calls += "source"
            points += x to y
        }

        override fun begin(x: Float, y: Float, mode: RetouchMode) {
            calls += "begin"
            points += x to y
            this.mode = mode
        }

        override fun extend(x: Float, y: Float) {
            if (calls.lastOrNull() != "extend") calls += "extend"
            points += x to y
        }

        override fun end() {
            calls += "end"
        }

        override fun cancel() {
            calls += "cancel"
        }

        override fun moveSource(x: Float, y: Float) {
            calls += "move"
            points += x to y
        }

        override fun toggleSourceLock() {
            calls += "lock"
        }

        override fun beginRemove(x: Float, y: Float) {
            calls += "remove"
            points += x to y
        }

        override fun prepareRemove() {
            calls += "prepare"
        }

        override fun pickObject(x: Float, y: Float) {
            calls += "pick"
            points += x to y
        }

        override fun removePicked() {
            calls += "removePicked"
        }

        override fun clearPicked() {
            calls += "clearPicked"
        }
    }

    /** 톡 눌러 고른 물체(문서 전체를 덮는 2단계 마스크 250×250, 한 칸 4px), 고를 때의 문서 번호 0 */
    private fun aPick(): RemovePick = RemovePick(
        Bitmap.createBitmap(250, 250, Bitmap.Config.ALPHA_8).apply { eraseColor(android.graphics.Color.BLACK) },
        level = 2,
        outline = SelectionOutline(listOf(floatArrayOf(400f, 400f, 600f, 400f, 600f, 600f, 400f, 600f, 400f, 400f))),
        area = InpaintArea.Masked(ByteArray(250 * 250) { -1 }, 250, 250, 4f, 4f),
        revision = 0
    )

    /** 표시를 꾹 눌러 알린 새 고정 상태 */
    private val locks = mutableListOf<Boolean>()

    private val actions = FakeRetouch()

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)

    private var settings by mutableStateOf(BrushSettings(size = 60f, hardness = 0.5f))

    private fun controls(source: CloneSource?, objectPicked: Boolean = false) =
        RetouchControls(settings, onChange = { settings = it }, source = source, objectPicked = objectPicked, onRemovePicked = actions::removePicked)

    private fun showOptions(mode: SubTool, source: CloneSource? = null, widthDp: Int = 411, objectPicked: Boolean = false) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 800.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme { RetouchOptions(mode, controls(source, objectPicked), onChoose = {}) }
                }
            }
        }
    }

    private fun showOverlay(
        mode: RetouchMode,
        source: CloneSource? = null,
        removing: Boolean = false,
        pick: RemovePick? = null
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 400.dp))) {
                StratumTheme {
                    Box(Modifier.fillMaxSize()) {
                        RetouchOverlay(
                            view = { CanvasView.Fit },
                            document = document,
                            mode = mode,
                            source = source,
                            radius = 30f,
                            actions = actions,
                            modifier = Modifier.fillMaxSize(),
                            removing = removing,
                            pick = pick,
                            onLockChanged = { locks += it }
                        )
                    }
                }
            }
        }
    }

    private fun exists(tag: String) = composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** 화면에 맞춘 보기의 변환과 캔버스 자리(루트 기준 픽셀) */
    private fun fit(): Pair<ViewTransform, Offset> {
        val overlay = composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).fetchSemanticsNode().boundsInRoot
        return CanvasView.Fit.transform(PixelSize(overlay.width.roundToInt(), overlay.height.roundToInt()), document) to overlay.topLeft
    }

    /** 문서 좌표 ([x], [y])의 캔버스 안 자리(터치 자리, 픽셀) */
    private fun localOf(x: Float, y: Float): Offset {
        val (transform, _) = fit()
        return Offset(transform.toScreenX(x.toDouble()).toFloat(), transform.toScreenY(y.toDouble()).toFloat())
    }

    private fun hint(id: Int) = composeRule.activity.getString(id)

    /** 하위 도구 줄 끝의 물음표를 누르면 [id] 안내가 말풍선으로 뜬다(설명 줄 대신, 사용자 피드백 8차 1-7) */
    private fun assertHelp(id: Int) {
        composeRule.onNodeWithTag(EditScreenTags.TOOL_HELP).assertContentDescriptionEquals(hint(R.string.tool_help)).performClick()
        composeRule.onNode(hasText(hint(id)) and hasAnyAncestor(hasTestTag(HELP_TOOLTIP_TAG))).assertIsDisplayed()
    }

    @Test
    fun spotHeal_hasNoOpacity() {
        showOptions(SubTool.RETOUCH_SPOT)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HARDNESS).assertIsDisplayed()
        assertTrue(!exists(EditScreenTags.RETOUCH_OPACITY))
        assertHelp(R.string.retouch_hint_spot)
    }

    @Test
    fun subToolRow_isAtTheBottom_withTheHelpAtItsEnd() {
        // 커버 공통 규칙(사용자 피드백 8차 1-1): 하위 도구 줄은 옵션 칸 맨 아래, 물음표는 그 줄 오른쪽 끝(1-7)
        showOptions(SubTool.RETOUCH_CLONE, source = CloneSource(100f, 100f))

        val opacity = composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPACITY).fetchSemanticsNode().layoutBoundsInWindow()
        val spot = composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_SPOT)).fetchSemanticsNode().layoutBoundsInWindow()
        val remove = composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_REMOVE)).fetchSemanticsNode().layoutBoundsInWindow()
        val help = composeRule.onNodeWithTag(EditScreenTags.TOOL_HELP).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$spot should be below $opacity", spot.top >= opacity.bottom)
        assertTrue("$help should be right of $remove", help.left >= remove.right - 1f)
        // 설명 줄은 없다(물음표를 눌러야 보인다)
        composeRule.onNodeWithText(hint(R.string.retouch_hint_clone)).assertDoesNotExist()
    }

    @Test
    fun heal_hasNoSourceButtonOrOpacity_evenWithoutASource() {
        // 사용자 피드백 10차 4: '가져올 곳 정하기' 버튼을 뺐다(표시가 늘 보이고 끌어 옮긴다)
        showOptions(SubTool.RETOUCH_HEAL)

        composeRule.onNodeWithText(hint(R.string.retouch_hint_heal)).assertDoesNotExist()
        assertTrue(!exists(EditScreenTags.RETOUCH_OPACITY))
        // 옵션 칸에는 크기·경도와 하위 도구 줄(과 물음표)뿐이다
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HARDNESS).assertIsDisplayed()
        assertHelp(R.string.retouch_hint_heal)
    }

    @Test
    fun clone_hasOpacity() {
        showOptions(SubTool.RETOUCH_CLONE, source = CloneSource(100f, 100f))

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPACITY).performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }

        composeRule.runOnIdle { assertEquals(0.4f, settings.opacity, 0.001f) }
        assertHelp(R.string.retouch_hint_clone)
    }

    @Test
    fun aiRemove_hasOnlyTheSize_andItsHint() {
        showOptions(SubTool.RETOUCH_REMOVE)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).assertIsDisplayed()
        assertTrue(!exists(EditScreenTags.RETOUCH_HARDNESS) && !exists(EditScreenTags.RETOUCH_OPACITY))
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_REMOVE)).assertIsSelected()
        assertHelp(R.string.retouch_hint_remove)
    }

    @Test
    fun theFourSubTools_fitOnOneLine_atTheCoverWidth() {
        showOptions(SubTool.RETOUCH_REMOVE)

        // 하위 도구 넷의 이름이 커버 폭(411dp)에서 잘리지 않는다
        for (subTool in EditTool.Retouch.subTools) {
            val results = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(composeRule.activity.getString(subTool.shortLabel), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertFalse("$subTool overflows", results.single().hasVisualOverflow)
        }
    }

    @Test
    fun sizeAndHardness_change() {
        showOptions(SubTool.RETOUCH_SPOT)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(200f)) }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HARDNESS).performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }

        composeRule.runOnIdle {
            assertEquals(200f, settings.size, 0f)
            assertEquals(0.9f, settings.hardness, 0.001f)
        }
    }

    @Test
    fun withoutASourceYet_aTouchPaints_insteadOfSettingTheSource() {
        // 사용자 피드백 10차 4: 정하는 단계가 없다(편집 화면이 고를 때 표시를 둔다). 누르면 칠한다.
        showOverlay(RetouchMode.CLONE)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }

        composeRule.runOnIdle { assertEquals(listOf("begin", "extend", "end"), actions.calls.distinct()) }
    }

    @Test
    fun painting_sendsTheStrokeWithTheMode() {
        showOverlay(RetouchMode.HEAL, source = CloneSource(100f, 100f, SourceOffset(400, 400)))

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
            moveBy(Offset(30f, 0f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("begin", "extend", "end"), actions.calls)
            assertEquals(RetouchMode.HEAL, actions.mode)
            val (x, y) = actions.points.first()
            assertTrue("start $x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
        }
    }

    @Test
    fun theSourceMarker_staysAfterAStroke_atTheSource() {
        // 한 번 칠해 간격이 정해진 뒤에도 보인다(사용자 요청, 전에는 이때 표시가 사라졌다)
        showOverlay(RetouchMode.CLONE, source = CloneSource(250f, 750f, SourceOffset(400, 200)))

        val marker = composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE_MARKER).fetchSemanticsNode().boundsInRoot
        val (_, origin) = fit()
        val expected = origin + localOf(250f, 750f)
        assertEquals(expected.x, marker.center.x, 2f)
        assertEquals(expected.y, marker.center.y, 2f)
    }

    @Test
    fun draggingTheMarker_movesTheSource_andDoesNotPaint() {
        showOverlay(RetouchMode.CLONE, source = CloneSource(300f, 300f, SourceOffset(400, 200)))
        val start = localOf(300f, 300f)
        val scale = fit().first.scaleX.toFloat()

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(start)
            moveBy(Offset(40f, 0f))
            moveBy(Offset(40f, 0f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("move"), actions.calls)
            // 끈 만큼(화면 80픽셀) 옮긴 자리가 새 가져올 곳이다
            val (x, y) = actions.points.single()
            assertEquals(300f + 80f / scale, x, 1f)
            assertEquals(300f, y, 1f)
        }
    }

    @Test
    fun holdingTheMarker_togglesTheLock_andATapDoesNothing() {
        showOverlay(RetouchMode.HEAL, source = CloneSource(300f, 300f))
        val at = localOf(300f, 300f)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(at)
            // 길게 누르기 시간(약 0.4초)보다 오래 움직이지 않는다
            advanceEventTime(800)
            up()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("lock"), actions.calls)
            assertEquals(listOf(true), locks)
        }

        // 표시를 톡 누르면 아무것도 하지 않는다(칠하지도 않는다)
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput { click(at) }
        composeRule.runOnIdle { assertEquals(listOf("lock"), actions.calls) }
    }

    @Test
    fun aSecondFinger_whileDraggingTheMarker_leavesTheSource() {
        showOverlay(RetouchMode.CLONE, source = CloneSource(300f, 300f))
        val at = localOf(300f, 300f)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(0, at)
            moveBy(0, Offset(60f, 0f))
            down(1, at + Offset(150f, 150f))
            up(1)
            up(0)
        }

        composeRule.runOnIdle { assertTrue(actions.calls.toString(), actions.calls.isEmpty()) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theMarker_tellsScreenReadersItIsLocked_andOffersUnlock() {
        showOverlay(RetouchMode.CLONE, source = CloneSource(300f, 300f, locked = true))

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE_MARKER)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, hint(R.string.retouch_marker_locked)))
            .performCustomAccessibilityActionWithLabel(hint(R.string.retouch_unlock))

        composeRule.runOnIdle {
            assertEquals(listOf("lock"), actions.calls)
            assertEquals(listOf(false), locks)
        }
    }

    @Test
    fun aiRemove_paintsARemoveStroke_evenOverTheSourceMarker() {
        // AI 제거는 가져올 곳을 쓰지 않는다: 표시 위를 눌러도 칠한다
        showOverlay(RetouchMode.HEAL, source = CloneSource(500f, 500f), removing = true)
        assertTrue(!exists(EditScreenTags.RETOUCH_SOURCE_MARKER))

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("remove", "extend", "end"), actions.calls)
            val (x, y) = actions.points.first()
            assertTrue("start $x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
        }
    }

    @Test
    fun aiRemove_aTap_picksTheObject_insteadOfPainting() {
        showOverlay(RetouchMode.SPOT_HEAL, removing = true)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput { click(center) }

        composeRule.runOnIdle {
            assertEquals(listOf("pick"), actions.calls)
            val (x, y) = actions.points.single()
            assertTrue("$x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
        }
    }

    @Test
    fun aiRemove_showsThePickedShape_withAnOutline() {
        var pick by mutableStateOf<RemovePick?>(null)
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.fillMaxSize().background(Color.White).testTag(OVERLAY)) {
                    RetouchOverlay(
                        view = { CanvasView.Fit }, document = document, mode = RetouchMode.SPOT_HEAL, source = null,
                        radius = 30f, actions = actions, modifier = Modifier.fillMaxSize(), removing = true, pick = pick
                    )
                }
            }
        }
        assertTrue(!exists(EditScreenTags.SELECTION_OUTLINE))

        composeRule.runOnIdle { pick = aPick() }

        composeRule.waitForIdle()
        assertTrue(exists(EditScreenTags.SELECTION_OUTLINE))
        // 고른 모양 안(윤곽선에서 먼 문서 가운데)은 흰 바탕이 반투명 검정으로 어두워진다
        val shot = composeRule.onNodeWithTag(OVERLAY).captureToImage().asAndroidBitmap()
        val center = shot.getPixel(shot.width / 2, shot.height / 2)
        assertEquals(Integer.toHexString(center), 128f, android.graphics.Color.red(center).toFloat(), 8f)
    }

    @Test
    fun aiRemove_theRemoveButton_needsAPick() {
        var picked by mutableStateOf(false)
        composeRule.setContent {
            StratumTheme { RetouchOptions(SubTool.RETOUCH_REMOVE, controls(null, objectPicked = picked), onChoose = {}) }
        }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).assertIsNotEnabled()

        composeRule.runOnIdle { picked = true }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf("removePicked"), actions.calls) }
    }

    @Test
    fun editScreen_backDropsThePickFirst_thenClosesTheTool() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        var pick by mutableStateOf<RemovePick?>(null)
        val clearing = object : RetouchActions by actions {
            override fun clearPicked() {
                actions.clearPicked()
                pick = null
            }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {},
                        retouchActions = clearing, removePick = pick
                    )
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_REMOVE)).performClick()
        composeRule.runOnIdle {
            actions.calls.clear()
            pick = aPick()
        }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).performScrollTo().assertIsEnabled()

        // 뒤로 가기는 먼저 고른 물체만 버린다(도구는 그대로)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("clearPicked"), actions.calls) }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPTIONS).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).performScrollTo().assertIsNotEnabled()

        // 그다음 뒤로 가기는 도구를 닫는다
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPTIONS).assertDoesNotExist()
    }

    @Test
    fun editScreen_recreatingKeepsThePick_butLeavingAiRemoveDropsIt() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        var pick by mutableStateOf<RemovePick?>(null)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {},
                        retouchActions = actions, removePick = pick
                    )
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_REMOVE)).performClick()
        composeRule.runOnIdle {
            actions.calls.clear()
            pick = aPick()
        }

        // 접기·펼치기처럼 화면을 다시 만들어도(저장한 상태로 되살림) 고른 물체는 그대로다
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { assertFalse(actions.calls.toString(), "clearPicked" in actions.calls) }

        // 하위 도구를 바꾸면(AI 제거를 떠나면) 버린다
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_SPOT)).performClick()
        composeRule.runOnIdle { assertEquals(1, actions.calls.count { it == "clearPicked" }) }
    }

    @Test
    fun editScreen_whenPickingCannotRun_tellsToPaintInstead() {
        val messages = Channel<EditorMessage>(Channel.UNLIMITED)
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {},
                        messages = messages.receiveAsFlow(), retouchActions = actions
                    )
                }
            }
        }

        composeRule.runOnIdle { messages.trySend(EditorMessage.PickUnavailable) }

        composeRule.onNodeWithText(hint(R.string.edit_pick_unavailable)).assertIsDisplayed()
    }

    @Test
    fun aSecondFinger_dropsTheStroke() {
        showOverlay(RetouchMode.SPOT_HEAL)

        // 스팟 복구는 가져올 곳이 없이 칠한다. 두 번째 손가락이 닿으면 버린다.
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(0, center)
            moveBy(0, Offset(20f, 0f))
            down(1, center + Offset(80f, 80f))
            up(1)
            up(0)
        }

        composeRule.runOnIdle { assertEquals("cancel", actions.calls.last()) }
    }

    /** 편집 화면이 든 가져올 곳(ViewModel을 흉내 냄: 정하면 바뀐다) */
    private var editSource by mutableStateOf<CloneSource?>(null)

    /** 커버 편집 화면(411×800)에서 리터칭 도구를 연다. 가져올 곳을 정하면 [editSource]가 바뀐다. */
    private fun openRetouchOnTheCover() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        val sourceKeeping = object : RetouchActions by actions {
            override fun setSource(x: Float, y: Float) {
                actions.setSource(x, y)
                editSource = CloneSource(x, y)
            }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {},
                        retouchActions = sourceKeeping, cloneSource = editSource
                    )
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performScrollTo().performClick()
    }

    @Test
    fun editScreen_choosingHealOrClone_putsTheSourceInTheMiddleOfTheVisibleCanvas() {
        // 사용자 피드백 10차 4: "버튼 없어도 기본적으로 화면에 가져올 곳이 보여지고 사용자가 이를 옮기기만하면 됩니다"
        openRetouchOnTheCover()
        // 스팟 복구는 가져올 곳을 쓰지 않는다
        composeRule.runOnIdle { assertTrue(actions.calls.isEmpty()) }

        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_HEAL)).performClick()

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE_MARKER).assertExists()
        composeRule.runOnIdle {
            assertEquals(listOf("source"), actions.calls)
            // 보이는 캔버스(옵션 칸 위)의 가운데: 문서 가로 가운데, 세로는 옵션 칸이 덮은 만큼 위
            val (x, y) = actions.points.single()
            assertEquals(500f, x, 2f)
            assertTrue("$y should be above the middle", y < 500f)
        }

        // 보이는 가져올 곳은 복제 도장으로 바꿔도 그대로다
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_CLONE)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("source"), actions.calls) }
    }

    @Test
    fun editScreen_aSourceUnderTheOptions_movesUpIntoView() {
        // 옵션 칸 밑(문서 아래쪽)이라 보이지 않는 가져올 곳
        editSource = CloneSource(500f, 990f)
        openRetouchOnTheCover()

        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_CLONE)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("source"), actions.calls)
            assertTrue("${actions.points}", actions.points.single().second < 500f)
        }
    }

    @Test
    fun editScreen_retouchTool_opensItsOptions_andTheCoverTabsScroll() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {}, retouchActions = actions)
                }
            }
        }

        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performScrollTo().performClick()

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPTIONS).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).assertExists()
        // 탭이 커버 폭보다 많아 옆으로 밀어 보고, 어떤 탭도 누르는 영역이 48dp보다 좁지 않다
        val density = composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_ROW).fetchSemanticsNode().layoutInfo.density
        for (tool in EditTool.tabTools) {
            val node = composeRule.onNodeWithTag(EditScreenTags.tabTool(tool)).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            val width = node.touchBoundsInWindow().width / density.density
            assertTrue("$tool is $width dp", width >= 48f)
        }
    }

    @Test
    fun editScreen_choosingAiRemove_preparesTheModel_andPaintingRemoves() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {}, retouchActions = actions)
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(actions.calls.isEmpty()) }

        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_REMOVE)).performClick()

        // 고르면 모델을 미리 불러 두고, 옵션은 크기뿐이다
        composeRule.runOnIdle { assertEquals(listOf("prepare"), actions.calls) }
        assertTrue(!exists(EditScreenTags.RETOUCH_HARDNESS))
        // 캔버스 위쪽(옵션 칸이 덮지 않은 곳)을 칠하면 AI 제거 획이다
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(Offset(centerX, height * 0.2f))
            moveBy(Offset(30f, 0f))
            up()
        }
        composeRule.runOnIdle { assertEquals(listOf("prepare", "remove", "extend", "end"), actions.calls) }
    }

    @Test
    fun editScreen_whenAiRemoveCannotRun_offersTheSpotHealingBrush() {
        val messages = Channel<EditorMessage>(Channel.UNLIMITED)
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1)),
            document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {},
                        messages = messages.receiveAsFlow(), retouchActions = actions
                    )
                }
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performScrollTo().performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_REMOVE)).performClick()

        composeRule.runOnIdle { messages.trySend(EditorMessage.RemoveUnavailable) }

        // 알림 막대의 [스팟 복구]를 누르면 리터칭이 스팟 복구 브러시로 바뀐다
        composeRule.onNodeWithText(hint(R.string.edit_remove_unavailable)).assertIsDisplayed()
        composeRule.onNode(hasText(hint(R.string.subtool_spot_heal_short)) and hasAnyAncestor(hasTestTag(EditScreenTags.MESSAGE_BAR))).performClick()
        composeRule.onNodeWithTag(EditScreenTags.subToolOption(SubTool.RETOUCH_SPOT)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HARDNESS).assertExists()
    }

    @Test
    fun mainPortrait_optionsAvoidTheHorizontalHinge() {
        showOptions(SubTool.RETOUCH_CLONE, source = CloneSource(1f, 1f), widthDp = 700)
        val opacity = composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPACITY).fetchSemanticsNode().layoutBoundsInWindow()
        val y = opacity.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = y, right = 10_000, bottom = y, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
    }

    private companion object {
        /** 고른 물체 표시를 찍는 흰 바탕 상자 */
        const val OVERLAY = "pickOverlay"
    }
}
