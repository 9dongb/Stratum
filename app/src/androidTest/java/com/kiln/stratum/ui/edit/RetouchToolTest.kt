package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

/** 리터칭 도구의 옵션·캔버스 터치(가져올 곳 표시 옮기기·고정, 칠하기)와, 커버 탭 줄을 밀어 보는 것을 확인한다(엔진 대신 받은 일만 적는 가짜). */
@RunWith(AndroidJUnit4::class)
class RetouchToolTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()


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

        override fun enhanceTexture() {
            calls += "enhanceTexture"
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

    /** 가져오기: 아래 포함인지(결정 기록 '피부 보정'의 5단계) */
    private var sampleBelow by mutableStateOf(false)

    private fun controls(source: CloneSource?, objectPicked: Boolean = false, textureReady: Boolean = false, sampleFixed: Boolean = false) =
        RetouchControls(
            settings, onChange = { settings = it }, source = source, objectPicked = objectPicked, onRemovePicked = actions::removePicked,
            textureReady = textureReady, onEnhanceTexture = actions::enhanceTexture,
            sampleBelow = sampleBelow, sampleFixed = sampleFixed, onSampleBelow = { sampleBelow = it }
        )

    /** 커버 한 줄 막대에 리터칭의 칸을 놓는다(결정 기록 '커버 화면 개편') */
    private fun showOptions(mode: SubTool, source: CloneSource? = null, widthDp: Int = 411, objectPicked: Boolean = false, sampleFixed: Boolean = false) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 800.dp))) {
                StratumTheme { CoverToolBar(retouchBar(mode, controls(source, objectPicked, sampleFixed = sampleFixed)), rememberCoverBarState()) }
            }
        }
    }

    @Test
    fun theSampleChip_choosesThisLayerOrWithBelow() {
        showOptions(SubTool.RETOUCH_SPOT)

        composeRule.onNodeWithTag(EditScreenTags.bar("retouchSample")).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(EditScreenTags.retouchSample(RetouchSample.BELOW)).performClick()
        composeRule.runOnIdle { assertTrue(sampleBelow) }

        composeRule.onNodeWithTag(EditScreenTags.bar("retouchSample")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.retouchSample(RetouchSample.LAYER)).performClick()
        composeRule.runOnIdle { assertTrue(!sampleBelow) }
    }

    @Test
    fun theSpotsLayer_andAiRemove_haveNoSampleChip() {
        // 잡티 제거 레이어에는 늘 아래 포함이고, AI 제거는 그 레이어를 채운다
        showOptions(SubTool.RETOUCH_HEAL, sampleFixed = true)
        assertTrue(!exists(EditScreenTags.bar("retouchSample")))
    }

    @Test
    fun aiRemove_hasNoSampleChip() {
        showOptions(SubTool.RETOUCH_REMOVE)
        assertTrue(!exists(EditScreenTags.bar("retouchSample")))
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

    @Test
    fun spotHeal_hasNoOpacity() {
        showOptions(SubTool.RETOUCH_SPOT)

        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.bar("hardness")).assertIsDisplayed()
        assertTrue(!exists(EditScreenTags.bar("opacity")))
    }

    @Test
    fun sizeComesFirst_withoutASubToolChipOrHints() {
        // 커버 한 줄 막대(결정 기록 '커버 화면 개편'): 크기·경도·불투명도. 하위 도구 칩은 없다(탭의 하위 도구 메뉴로 고름, 2026-10-23 사용자 요청).
        showOptions(SubTool.RETOUCH_CLONE, source = CloneSource(100f, 100f))

        val lefts = composeRule.barChipLefts(listOf("size", "hardness", "opacity"))
        assertTrue("$lefts should go right", lefts.zipWithNext().all { (a, b) -> a < b })
        assertTrue(!exists(EditScreenTags.bar("subTool")))
        // 설명 줄도 물음표도 없다(안내는 처음 고를 때 편집 화면의 알림, 사용자 피드백 10차 5)
        composeRule.onNodeWithText(hint(R.string.retouch_hint_clone)).assertDoesNotExist()
    }

    @Test
    fun heal_hasNoSourceButtonOrOpacity_evenWithoutASource() {
        // 사용자 피드백 10차 4: '가져올 곳 정하기' 버튼을 뺐다(표시가 늘 보이고 끌어 옮긴다)
        showOptions(SubTool.RETOUCH_HEAL)

        composeRule.onNodeWithText(hint(R.string.retouch_hint_heal)).assertDoesNotExist()
        assertTrue(!exists(EditScreenTags.bar("opacity")))
        // 막대에는 크기·경도뿐이다
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.bar("hardness")).assertIsDisplayed()
    }

    @Test
    fun clone_hasOpacity() {
        showOptions(SubTool.RETOUCH_CLONE, source = CloneSource(100f, 100f))

        composeRule.expandBarChip("opacity")
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPACITY).performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }

        composeRule.runOnIdle { assertEquals(0.4f, settings.opacity, 0.001f) }
    }

    @Test
    fun aiRemove_hasOnlyTheSize_andItsHint() {
        showOptions(SubTool.RETOUCH_REMOVE)

        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
        assertTrue(!exists(EditScreenTags.bar("hardness")) && !exists(EditScreenTags.bar("opacity")))
    }

    @Test
    fun sizeAndHardness_change() {
        showOptions(SubTool.RETOUCH_SPOT)

        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(200f)) }
        composeRule.collapseBar()
        composeRule.expandBarChip("hardness")
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
            StratumTheme { CoverToolBar(retouchBar(SubTool.RETOUCH_REMOVE, controls(null, objectPicked = picked)), rememberCoverBarState()) }
        }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).assertIsNotEnabled()

        composeRule.runOnIdle { picked = true }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf("removePicked"), actions.calls) }
    }

    @Test
    fun aiRemove_theTextureButton_needsTheLastFillShown() {
        var ready by mutableStateOf(false)
        composeRule.setContent {
            StratumTheme { CoverToolBar(retouchBar(SubTool.RETOUCH_REMOVE, controls(null, textureReady = ready)), rememberCoverBarState()) }
        }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_ENHANCE_TEXTURE).assertIsDisplayed().assertIsNotEnabled()

        composeRule.runOnIdle { ready = true }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_ENHANCE_TEXTURE).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf("enhanceTexture"), actions.calls) }
    }

    @Test
    fun otherRetouchTools_haveNoTextureButton() {
        showOptions(SubTool.RETOUCH_SPOT)

        assertTrue(!exists(EditScreenTags.RETOUCH_ENHANCE_TEXTURE))
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
        var homeBacks = 0
        composeRule.setContent {
            // StratumApp처럼 편집 화면보다 먼저 '뒤로 → 홈'을 둔다
            BackHandler { homeBacks++ }
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready, onBack = {}, onCanvasSize = { _, _ -> }, onViewport = {}, onChooseSize = {},
                        retouchActions = clearing, removePick = pick
                    )
                }
            }
        }
        composeRule.chooseCoverTool(EditTool.Retouch)
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_REMOVE)
        composeRule.runOnIdle {
            actions.calls.clear()
            pick = aPick()
        }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).performScrollTo().assertIsEnabled()

        // 뒤로 가기는 먼저 고른 물체만 버린다(도구는 그대로)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("clearPicked"), actions.calls) }
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).performScrollTo().assertIsNotEnabled()

        // 그다음 뒤로 가기는 홈으로 간다(커버는 도구를 닫는 단계가 없다, 2026-10-23 사용자 요청)
        composeRule.runOnIdle { assertEquals(0, homeBacks) }
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(1, homeBacks) }
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
        composeRule.chooseCoverTool(EditTool.Retouch)
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_REMOVE)
        composeRule.runOnIdle {
            actions.calls.clear()
            pick = aPick()
        }

        // 접기·펼치기처럼 화면을 다시 만들어도(저장한 상태로 되살림) 고른 물체는 그대로다
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_REMOVE_PICKED).performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { assertFalse(actions.calls.toString(), "clearPicked" in actions.calls) }

        // 하위 도구를 바꾸면(AI 제거를 떠나면) 버린다
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_SPOT)
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
        composeRule.chooseCoverTool(EditTool.Retouch)
    }

    @Test
    fun editScreen_eachSubToolHint_showsOnceAsAToast() {
        // 사용자 피드백 10차 5: "각 도구의 도움말 버튼 제거하세요. 토스트로 보여주세요." 하위 도구마다 이번 실행에서 처음 고를 때 한 번(사용자 결정)
        openRetouchOnTheCover()
        val spot = hint(R.string.retouch_hint_spot)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(spot)
        // 글 길이만큼(3~10초) 보이고 사라진다
        composeRule.mainClock.advanceTimeBy(toolHelpToastMillis(spot) - 500)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertExists()
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()

        val heal = hint(R.string.retouch_hint_heal)
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_HEAL)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(heal)
        composeRule.mainClock.advanceTimeBy(toolHelpToastMillis(heal) + 1_000)

        // 이미 본 스팟 복구로 돌아가면 다시 뜨지 않는다
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_SPOT)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
    }

    @Test
    fun editScreen_aHintLeftAtOnce_showsAgainNextTime() {
        // 다 읽기 전에(2초 안에) 하위 도구를 바꾸면 본 것으로 적지 않아, 다음에 고를 때 다시 보인다(검토에서 찾음)
        openRetouchOnTheCover()
        val spot = hint(R.string.retouch_hint_spot)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(spot)

        composeRule.chooseCoverSubTool(SubTool.RETOUCH_CLONE)
        val clone = hint(R.string.retouch_hint_clone)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(clone)
        composeRule.mainClock.advanceTimeBy(toolHelpToastMillis(clone) + 1_000)

        composeRule.chooseCoverSubTool(SubTool.RETOUCH_SPOT)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(spot)
    }

    @Test
    fun editScreen_switchingToAnotherTool_clearsTheHintAtOnce() {
        // 2026-10-22 펼친 화면 점검: 선택 도구로 바꾼 뒤에도 리터칭 안내가 몇 초 남았다. 떠난 도구의 안내는 바로 지운다.
        openRetouchOnTheCover()
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(hint(R.string.retouch_hint_spot))

        composeRule.chooseCoverTool(EditTool.Select)
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
    }

    @Test
    fun editScreen_choosingHealOrClone_putsTheSourceInTheMiddleOfTheVisibleCanvas() {
        // 사용자 피드백 10차 4: "버튼 없어도 기본적으로 화면에 가져올 곳이 보여지고 사용자가 이를 옮기기만하면 됩니다"
        openRetouchOnTheCover()
        // 스팟 복구는 가져올 곳을 쓰지 않는다
        composeRule.runOnIdle { assertTrue(actions.calls.isEmpty()) }

        composeRule.chooseCoverSubTool(SubTool.RETOUCH_HEAL)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE_MARKER).assertExists()
        composeRule.runOnIdle {
            assertEquals(listOf("source"), actions.calls)
            // 보이는 캔버스의 가운데: 한 줄 막대는 캔버스 아래에 있어 캔버스를 덮지 않는다(결정 기록 '커버 화면 개편')
            val (x, y) = actions.points.single()
            assertEquals(500f, x, 2f)
            assertEquals(500f, y, 2f)
        }

        // 보이는 가져올 곳은 복제 도장으로 바꿔도 그대로다
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_CLONE)
        composeRule.runOnIdle { assertEquals(listOf("source"), actions.calls) }
    }

    @Test
    fun editScreen_aSourceUnderTheLayerSheet_movesUpIntoView() {
        // 캔버스 아래쪽 위에 겹쳐 뜬 레이어 시트 밑(문서 아래쪽)이라 보이지 않는 가져올 곳
        editSource = CloneSource(500f, 990f)
        openRetouchOnTheCover()
        composeRule.togglePanel(MainPanel.LAYERS)

        composeRule.chooseCoverSubTool(SubTool.RETOUCH_CLONE)

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

        composeRule.chooseCoverTool(EditTool.Retouch)

        // 한 줄 막대에 리터칭의 칸이 보인다(결정 기록 '커버 화면 개편')
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).assertExists()
        // 탭 여섯(더보기 포함)이 커버 폭을 나눠 모두 보이고, 어떤 탭도 누르는 영역이 48dp보다 좁지 않다(결정 기록 '커버 화면 개편')
        val density = composeRule.onNodeWithTag(EditScreenTags.TOOL_TAB_ROW).fetchSemanticsNode().layoutInfo.density
        for (tag in EditTool.coverTabs.map { EditScreenTags.tabTool(it) } + EditScreenTags.MORE_TAB) {
            val node = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            val width = node.touchBoundsInWindow().width / density.density
            assertTrue("$tag is $width dp", width >= 48f)
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
        composeRule.chooseCoverTool(EditTool.Retouch)
        composeRule.runOnIdle { assertTrue(actions.calls.isEmpty()) }

        composeRule.chooseCoverSubTool(SubTool.RETOUCH_REMOVE)

        // 고르면 모델을 미리 불러 두고, 옵션은 크기뿐이다
        composeRule.runOnIdle { assertEquals(listOf("prepare"), actions.calls) }
        assertTrue(!exists(EditScreenTags.bar("hardness")))
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
        composeRule.chooseCoverTool(EditTool.Retouch)
        composeRule.chooseCoverSubTool(SubTool.RETOUCH_REMOVE)

        composeRule.runOnIdle { messages.trySend(EditorMessage.RemoveUnavailable) }

        // 알림 막대의 [스팟 복구]를 누르면 리터칭이 스팟 복구 브러시로 바뀐다
        composeRule.onNodeWithText(hint(R.string.edit_remove_unavailable)).assertIsDisplayed()
        composeRule.onNode(hasText(hint(R.string.subtool_spot_heal_short)) and hasAnyAncestor(hasTestTag(EditScreenTags.MESSAGE_BAR))).performClick()
        composeRule.onNodeWithTag(EditScreenTags.bar("hardness")).assertExists()
        // 탭의 하위 도구 메뉴를 다시 열면 스팟 복구가 골라져 있다(막대에는 하위 도구 칩이 없다, 2026-10-23)
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Retouch)).performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag(EditScreenTags.subTool(SubTool.RETOUCH_SPOT)).assertIsSelected()
    }

    private companion object {
        /** 고른 물체 표시를 찍는 흰 바탕 상자 */
        const val OVERLAY = "pickOverlay"
    }
}
