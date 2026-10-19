package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
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
    }

    /** 표시를 꾹 눌러 알린 새 고정 상태 */
    private val locks = mutableListOf<Boolean>()

    private val actions = FakeRetouch()

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)

    private var settings by mutableStateOf(BrushSettings(size = 60f, hardness = 0.5f))
    private var picks = 0

    private fun controls(source: CloneSource?, picking: Boolean) =
        RetouchControls(settings, onChange = { settings = it }, source = source, picking = picking, onPick = { picks++ })

    private fun showOptions(mode: SubTool, source: CloneSource? = null, picking: Boolean = source == null, widthDp: Int = 411) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 800.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme { RetouchOptions(mode, controls(source, picking), onChoose = {}) }
                }
            }
        }
    }

    private fun showOverlay(mode: RetouchMode, picking: Boolean, source: CloneSource? = null, onPicked: () -> Unit = {}) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 400.dp))) {
                StratumTheme {
                    Box(Modifier.fillMaxSize()) {
                        RetouchOverlay(
                            view = { CanvasView.Fit },
                            document = document,
                            mode = mode,
                            picking = picking,
                            source = source,
                            radius = 30f,
                            actions = actions,
                            onPicked = onPicked,
                            modifier = Modifier.fillMaxSize(),
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
    fun spotHeal_hasNoSourceButtonOrOpacity() {
        showOptions(SubTool.RETOUCH_SPOT)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SIZE).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HARDNESS).assertIsDisplayed()
        assertTrue(!exists(EditScreenTags.RETOUCH_OPACITY) && !exists(EditScreenTags.RETOUCH_SOURCE))
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HINT).assertTextEquals(hint(R.string.retouch_hint_spot))
    }

    @Test
    fun heal_asksForTheSourceFirst_andHasNoOpacity() {
        showOptions(SubTool.RETOUCH_HEAL)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE).assertIsSelected()
        assertTrue(!exists(EditScreenTags.RETOUCH_OPACITY))
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HINT).assertTextEquals(hint(R.string.retouch_hint_pick))
    }

    @Test
    fun clone_withASource_hasOpacity_andTheSourceButtonPicksAgain() {
        showOptions(SubTool.RETOUCH_CLONE, source = CloneSource(100f, 100f), picking = false)

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_HINT).assertTextEquals(hint(R.string.retouch_hint_clone))
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPACITY).performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE).performClick()

        composeRule.runOnIdle {
            assertEquals(0.4f, settings.opacity, 0.001f)
            assertEquals(1, picks)
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
    fun picking_setsTheSourceWhereTheFingerLifts_andDoesNotPaint() {
        var picked = 0
        showOverlay(RetouchMode.CLONE, picking = true, onPicked = { picked++ })

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("source"), actions.calls)
            assertEquals(1, picked)
            // 손을 뗀 자리(가운데에서 오른쪽)가 가져올 곳이다
            val (x, y) = actions.points.single()
            assertTrue("$x, $y", x > 500f && abs(y - 500f) < 30f)
        }
    }

    @Test
    fun painting_sendsTheStrokeWithTheMode() {
        showOverlay(RetouchMode.HEAL, picking = false, source = CloneSource(100f, 100f, SourceOffset(400, 400)))

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
        showOverlay(RetouchMode.CLONE, picking = false, source = CloneSource(250f, 750f, SourceOffset(400, 200)))

        val marker = composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE_MARKER).fetchSemanticsNode().boundsInRoot
        val (_, origin) = fit()
        val expected = origin + localOf(250f, 750f)
        assertEquals(expected.x, marker.center.x, 2f)
        assertEquals(expected.y, marker.center.y, 2f)
    }

    @Test
    fun draggingTheMarker_movesTheSource_andDoesNotPaint() {
        showOverlay(RetouchMode.CLONE, picking = false, source = CloneSource(300f, 300f, SourceOffset(400, 200)))
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
        showOverlay(RetouchMode.HEAL, picking = false, source = CloneSource(300f, 300f))
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
        showOverlay(RetouchMode.CLONE, picking = false, source = CloneSource(300f, 300f))
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
        showOverlay(RetouchMode.CLONE, picking = false, source = CloneSource(300f, 300f, locked = true))

        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE_MARKER)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, hint(R.string.retouch_marker_locked)))
            .performCustomAccessibilityActionWithLabel(hint(R.string.retouch_unlock))

        composeRule.runOnIdle {
            assertEquals(listOf("lock"), actions.calls)
            assertEquals(listOf(false), locks)
        }
    }

    @Test
    fun whilePicking_touchingTheMarkerPicksAgain() {
        showOverlay(RetouchMode.CLONE, picking = true, source = CloneSource(300f, 300f))

        // 정하는 중에는 표시를 잡지 않는다(누른 자리가 새 가져올 곳)
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_SOURCE_MARKER).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput { click(localOf(300f, 300f)) }

        composeRule.runOnIdle { assertEquals(listOf("source"), actions.calls) }
    }

    @Test
    fun aSecondFinger_dropsTheStroke() {
        showOverlay(RetouchMode.SPOT_HEAL, picking = true)

        // 스팟 복구는 가져올 곳을 정하지 않으므로 칠한다. 두 번째 손가락이 닿으면 버린다.
        composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OVERLAY).performTouchInput {
            down(0, center)
            moveBy(0, Offset(20f, 0f))
            down(1, center + Offset(80f, 80f))
            up(1)
            up(0)
        }

        composeRule.runOnIdle { assertEquals("cancel", actions.calls.last()) }
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
    fun mainPortrait_optionsAvoidTheHorizontalHinge() {
        showOptions(SubTool.RETOUCH_CLONE, source = CloneSource(1f, 1f), picking = false, widthDp = 700)
        val opacity = composeRule.onNodeWithTag(EditScreenTags.RETOUCH_OPACITY).fetchSemanticsNode().layoutBoundsInWindow()
        val y = opacity.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = y, right = 10_000, bottom = y, isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
    }
}
