package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.settings.StripPosition
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.layout.workBarContentPx
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 픽셀 유동화 작업 모드의 버튼·옵션·캔버스 끌기와, 편집 화면에서 여는 것을 확인한다(엔진 대신 받은 일만 적는 가짜).
 * 옵션은 펼친 화면에서 편집 화면과 같은 왼쪽 위 띠, 닫힌 화면에서 크기·압력 칸과 맨 아래 X·✓ 사이의 도구 탭이다(사용자 피드백 8차 1-5 정정).
 */
@RunWith(AndroidJUnit4::class)
class LiquifyModeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val hinges = mutableStateOf(emptyList<Hinge>())

    /** 받은 일 */
    private class FakeLiquify : LiquifyActions {
        val calls = mutableListOf<String>()
        val points = mutableListOf<Pair<Float, Float>>()
        var chosenTool: LiquifyTool? = null
        var chosenSize: Float? = null
        var chosenStrength: Float? = null

        override fun begin(x: Float, y: Float) {
            calls += "begin"
            points += x to y
        }

        override fun move(x: Float, y: Float) {
            if (calls.lastOrNull() != "move") calls += "move"
            points += x to y
        }

        override fun end() {
            calls += "end"
        }

        override fun cancelStroke() {
            calls += "cancelStroke"
        }

        override fun setTool(tool: LiquifyTool) {
            chosenTool = tool
        }

        override fun setSize(size: Float) {
            chosenSize = size
        }

        override fun setStrength(strength: Float) {
            chosenStrength = strength
        }

        override fun undo() {
            calls += "undo"
        }

        override fun redo() {
            calls += "redo"
        }

        override fun reset() {
            calls += "reset"
        }

        override fun apply() {
            calls += "apply"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private val actions = FakeLiquify()

    /** 1000×1000 문서. 정사각형이라 화면에 맞추면 캔버스 가운데가 문서 가운데다. */
    private val document = PixelSize(1000, 1000)
    private val preview = PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 1))

    private var state by mutableStateOf(
        LiquifyState(preview, document, LiquifyTool.PUSH, size = 150f, strength = 0.5f, canUndo = false, changed = false)
    )

    /** 띠를 옮겨 놓은 자리(편집 화면의 띠와 같은 설정 값)와 손잡이를 놓을 때 받은 자리 */
    private var stripPosition by mutableStateOf<StripPosition?>(null)
    private val positions = mutableListOf<StripPosition>()

    private fun show(widthDp: Int, heightDp: Int, twoFingerTapUndo: Boolean = false, threeFingerGestures: Boolean = false, flip: ViewFlip = ViewFlip.None) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        LiquifyMode(
                            state, actions,
                            twoFingerTapUndo = twoFingerTapUndo,
                            threeFingerGestures = threeFingerGestures,
                            flip = flip,
                            stripPosition = stripPosition,
                            onStripPosition = {
                                positions += it
                                stripPosition = it
                            }
                        )
                    }
                }
            }
        }
    }

    /** 두 손가락을 잠깐 대었다가 뗀다(두 손가락 톡) */
    private fun TouchInjectionScope.twoFingerTap() {
        down(0, center - Offset(60f, 0f))
        down(1, center + Offset(60f, 0f))
        advanceEventTime(80)
        up(0)
        up(1)
    }

    @Test
    fun buttons_followTheState() {
        show(411, 800)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANCEL).assertIsEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_UNDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_REDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_RESET).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_APPLY).assertIsNotEnabled()

        composeRule.runOnIdle { state = state.copy(canUndo = true, canRedo = true, changed = true) }
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_UNDO).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_REDO).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_RESET).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_APPLY).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANCEL).performClick()

        composeRule.runOnIdle { assertEquals(listOf("undo", "redo", "reset", "apply", "cancel"), actions.calls) }
    }

    @Test
    fun cover_cancelAndApplyAreAtTheBottomLeftAndRight_notInTheTopBar() {
        // 닫힌 화면 새 창 공통 규칙(결정 기록 '닫힌 화면 새 창'): 왼쪽 아래 X, 오른쪽 아래 ✓
        show(411, 800)
        val mode = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_MODE).fetchSemanticsNode().layoutBoundsInWindow()
        val cancel = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANCEL).fetchSemanticsNode().layoutBoundsInWindow()
        val apply = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_APPLY).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("$cancel should be bottom-left of $mode", cancel.center.y > mode.center.y + mode.height / 3 && cancel.center.x < mode.center.x)
        assertTrue("$apply should be bottom-right of $mode", apply.center.y > mode.center.y + mode.height / 3 && apply.center.x > mode.center.x)
        composeRule.onNode(hasTestTag(EditScreenTags.LIQUIFY_APPLY) and hasAnyAncestor(hasTestTag(EditScreenTags.LIQUIFY_TOP_BAR))).assertDoesNotExist()
        composeRule.onNode(hasTestTag(EditScreenTags.LIQUIFY_CANCEL) and hasAnyAncestor(hasTestTag(EditScreenTags.LIQUIFY_TOP_BAR))).assertDoesNotExist()
    }

    @Test
    fun mainScreens_keepCancelAndApplyInTheTopBar() {
        show(930, 700)
        composeRule.onNode(hasTestTag(EditScreenTags.LIQUIFY_APPLY) and hasAnyAncestor(hasTestTag(EditScreenTags.LIQUIFY_TOP_BAR))).assertExists()
        composeRule.onNode(hasTestTag(EditScreenTags.LIQUIFY_CANCEL) and hasAnyAncestor(hasTestTag(EditScreenTags.LIQUIFY_TOP_BAR))).assertExists()
    }

    @Test
    fun topBar_isAsLowAsTheEditScreens() {
        show(411, 800)
        val bar = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_TOP_BAR).fetchSemanticsNode()
        val density = bar.layoutInfo.density.density
        val insets = composeRule.runOnIdle { composeRule.activity.window.decorView.rootWindowInsets }
        val statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top
        val safeTop = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top

        // 편집 화면 상단 바와 같은 내용 줄(상태 표시줄 높이)에 상태 표시줄(또는 버튼·제목에 걸리는 카메라 구멍) 높이만 더한다
        val content = workBarContentPx(insets, density)
        val height = bar.layoutBoundsInWindow().height
        assertTrue("top bar $height px (content $content)", height >= content + statusTop - 1f && height <= content + safeTop + 1f)
    }

    @Test
    fun aSecondFinger_dropsTheStroke() {
        show(411, 800)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput {
            down(0, center)
            moveBy(0, Offset(30f, 0f))
            down(1, center + Offset(150f, 0f))
            up(1)
            up(0)
        }

        composeRule.runOnIdle { assertEquals(listOf("begin", "move", "cancelStroke"), actions.calls) }
    }

    @Test
    fun twoFingerTap_undoesAStroke_andTellsSo() {
        state = state.copy(canUndo = true, changed = true)
        show(411, 800, twoFingerTapUndo = true)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput { twoFingerTap() }

        // 첫 손가락이 시작한 획은 버리고, 앞 획을 되돌린다
        composeRule.runOnIdle { assertEquals(listOf("begin", "cancelStroke", "undo"), actions.calls) }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.action_undo))
    }

    @Test
    fun twoFingerTap_withNothingToUndo_tellsSo() {
        show(411, 800, twoFingerTapUndo = true)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput { twoFingerTap() }

        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.gesture_nothing_to_undo))
        composeRule.runOnIdle { assertTrue("undo" !in actions.calls) }
    }

    @Test
    fun mainLandscape_theToastIsCenteredOnTheScreen() {
        // 알림은 왼쪽 위 띠와 상관없이 화면 정중앙이다(사용자 요청)
        show(930, 700, twoFingerTapUndo = true)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput { twoFingerTap() }

        val screen = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_TOP_BAR).fetchSemanticsNode().layoutBoundsInWindow()
        val toast = composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(screen.center.x, toast.center.x, 2f)
        assertTrue("toast $toast should be below the top bar $screen", toast.top >= screen.bottom)
    }

    @Test
    fun twoFingerTap_turnedOff_doesNothing() {
        state = state.copy(canUndo = true, changed = true)
        show(411, 800, twoFingerTapUndo = false)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput { twoFingerTap() }

        composeRule.runOnIdle { assertTrue("undo" !in actions.calls) }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
    }

    @Test
    fun threeFingerTap_redoesAStroke_andTellsSo() {
        state = state.copy(canRedo = true)
        show(411, 800, threeFingerGestures = true)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput { threeFingerTap() }

        composeRule.runOnIdle { assertEquals(listOf("begin", "cancelStroke", "redo"), actions.calls) }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.action_redo))
    }

    @Test
    fun threeFingerTap_withNothingToRedo_tellsSo() {
        show(411, 800, threeFingerGestures = true)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput { threeFingerTap() }

        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertTextEquals(composeRule.activity.getString(R.string.gesture_nothing_to_redo))
        composeRule.runOnIdle { assertTrue("redo" !in actions.calls) }
    }

    @Test
    fun threeFingerSwipes_changeTheSize_andTheStrength() {
        show(411, 800, threeFingerGestures = true)
        val overlay = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY)

        // 위로 밀면 크기가 커지고, 미는 동안 크기 원만 보인다(알림 없음)
        overlay.performTouchInput { threeFingerSwipe(Offset(0f, -200f), lift = false) }
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertExists()
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
        overlay.performTouchInput { for (id in 0..2) up(id) }
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue("${actions.chosenSize}", checkNotNull(actions.chosenSize) > 150f)
            assertEquals(null, actions.chosenStrength)
        }

        // 왼쪽으로 밀면 압력이 준다(유동화에는 단단함이 없다)
        overlay.performTouchInput { threeFingerSwipe(Offset(-200f, 0f)) }
        composeRule.runOnIdle { assertTrue("${actions.chosenStrength}", checkNotNull(actions.chosenStrength) < 0.5f) }
    }

    @Test
    fun threeFingers_turnedOff_doNothing() {
        state = state.copy(canRedo = true)
        show(411, 800, threeFingerGestures = false)
        val overlay = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY)

        overlay.performTouchInput { threeFingerTap() }
        overlay.performTouchInput { threeFingerSwipe(Offset(0f, -200f)) }

        composeRule.runOnIdle {
            assertTrue("redo" !in actions.calls)
            assertEquals(null, actions.chosenSize)
        }
        composeRule.onNodeWithTag(EditScreenTags.GESTURE_TOAST).assertDoesNotExist()
    }

    @Test
    fun pressing_showsTheBrushCursor() {
        show(411, 800)
        val overlay = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY)

        overlay.performTouchInput { down(center) }
        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertExists()
        overlay.performTouchInput { up() }

        composeRule.onNodeWithTag(EditScreenTags.BRUSH_CURSOR).assertDoesNotExist()
    }

    @Test
    fun pinch_zoomsTheCanvas() {
        show(411, 800)
        val overlay = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY)
        // 확대 전: 가운데에서 오른쪽으로 100px 떨어진 화면 자리의 문서 좌표
        overlay.performTouchInput { down(center + Offset(100f, 0f)); up() }
        val before = composeRule.runOnIdle { actions.points.last().first }

        overlay.performTouchInput {
            pinch(start0 = center - Offset(20f, 0f), end0 = center - Offset(200f, 0f), start1 = center + Offset(20f, 0f), end1 = center + Offset(200f, 0f))
        }
        overlay.performTouchInput { down(center + Offset(100f, 0f)); up() }
        val after = composeRule.runOnIdle { actions.points.last().first }

        // 확대했으면 같은 화면 자리가 문서 가운데(500)에 더 가깝다
        assertTrue("$before -> $after", abs(after - 500f) < abs(before - 500f) / 2)
    }

    @Test
    fun whileApplying_everythingIsBlocked() {
        state = state.copy(canUndo = true, canRedo = true, changed = true, busy = true)
        show(411, 800)

        for (tag in listOf(EditScreenTags.LIQUIFY_CANCEL, EditScreenTags.LIQUIFY_UNDO, EditScreenTags.LIQUIFY_REDO, EditScreenTags.LIQUIFY_RESET, EditScreenTags.LIQUIFY_APPLY)) {
            composeRule.onNodeWithTag(tag).assertIsNotEnabled()
        }
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput { down(center); moveBy(Offset(50f, 0f)); up() }
        composeRule.runOnIdle { assertTrue(actions.calls.isEmpty()) }
    }

    @Test
    fun dragging_sendsTheStrokeInDocumentCoordinates() {
        show(411, 800)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            moveBy(Offset(40f, 0f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("begin", "move", "end"), actions.calls)
            val (x, y) = actions.points.first()
            assertTrue("start $x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
            assertTrue(actions.points.last().first > x)
        }
    }

    @Test
    fun mirroredView_draggingRightOnTheScreen_bendsTowardTheLeftOfTheDocument() {
        // 편집 화면의 화면 반전을 따른다(결정 기록 '화면 반전')
        show(411, 800, flip = ViewFlip(horizontal = true))

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OVERLAY).performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            moveBy(Offset(40f, 0f))
            up()
        }

        composeRule.runOnIdle {
            val (x, y) = actions.points.first()
            // 가운데는 뒤집어도 가운데다
            assertTrue("start $x, $y", abs(x - 500f) < 30f && abs(y - 500f) < 30f)
            assertTrue("${actions.points}", actions.points.last().first < x)
        }
    }

    @Test
    fun options_changeToolSizeAndPressure() {
        show(411, 800)

        composeRule.onNodeWithTag(EditScreenTags.liquifyTool(LiquifyTool.BLOAT)).performClick()
        // 크기·압력은 한 줄 막대의 칩이다. 누르면 같은 줄이 슬라이더가 되고 ✓로 돌아온다(결정 기록 '커버 화면 개편').
        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(300f)) }
        composeRule.collapseBar()
        composeRule.expandBarChip("strength")
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_STRENGTH).performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }

        composeRule.runOnIdle {
            assertEquals(LiquifyTool.BLOAT, actions.chosenTool)
            assertEquals(300f, checkNotNull(actions.chosenSize), 0f)
            assertEquals(0.8f, checkNotNull(actions.chosenStrength), 0.001f)
        }
    }

    @Test
    fun systemBack_cancels() {
        show(411, 800)

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.runOnIdle { assertEquals(listOf("cancel"), actions.calls) }
    }

    @Test
    fun cover_backFoldsAnOpenChip_beforeCancelling() {
        show(411, 800)
        composeRule.expandBarChip("size")
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_SIZE).assertIsDisplayed()

        // 펼친 칩이 있으면 뒤로 가기는 칩 줄로 돌아가고, 다음 뒤로 가기가 취소다
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_SIZE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue("${actions.calls}", actions.calls.isEmpty()) }
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("cancel"), actions.calls) }
    }

    @Test
    fun cover_toolTabsAreOnTheBottomRowBetweenCancelAndApply() {
        // 사용자 피드백 8차 1-1: 고르는 탭은 맨 아래 X와 ✓ 사이(닫힌 화면 공통 규칙). 그 위는 크기·압력 칩의 한 줄 막대다(결정 기록 '커버 화면 개편').
        show(411, 800)
        val cancel = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANCEL).fetchSemanticsNode().layoutBoundsInWindow()
        val apply = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_APPLY).fetchSemanticsNode().layoutBoundsInWindow()

        var left = cancel.right
        for (tool in LiquifyTool.entries) {
            val tab = composeRule.onNodeWithTag(EditScreenTags.liquifyTool(tool)).assertIsDisplayed().fetchSemanticsNode().layoutBoundsInWindow()
            assertEquals("$tool should be on the bottom row", cancel.center.y, tab.center.y, 3f)
            assertTrue("$tool $tab should be right of $left", tab.left >= left - 3f)
            left = tab.right
        }
        assertTrue("tabs end at $left, apply $apply", left <= apply.left)
        composeRule.onNodeWithTag(EditScreenTags.liquifyTool(LiquifyTool.PUSH)).assertIsSelected()
        composeRule.onNode(hasTestTag(EditScreenTags.liquifyTool(LiquifyTool.PUSH)) and hasAnyAncestor(hasTestTag(EditScreenTags.COVER_TOOL_BAR))).assertDoesNotExist()
        // 막대는 맨 아래 줄 바로 위다(X는 그 줄 안에서 위아래 여백만큼 안쪽)
        val barNode = composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).fetchSemanticsNode()
        val bar = barNode.layoutBoundsInWindow()
        val dp = with(barNode.layoutInfo.density) { 1.dp.toPx() }
        assertTrue("the bar $bar should sit right above the bottom row ($cancel)", bar.bottom <= cancel.top + 1f && cancel.top - bar.bottom < 12 * dp)
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.bar("strength")).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
    }

    @Test
    fun coverLandscape_isTheRotatedLayout_barAndWorkColumnRightOfTheCanvas() {
        // 사용자 피드백 12차 3: 커버를 가로로 돌리면 커버 배치가 돌아간 형태다. [캔버스 | 크기·압력 기둥 | ✓ · 도구 · X 기둥]
        show(797, 411)
        // 접은 에뮬레이터는 강제 크기를 줄여 넣어 기둥이 낮아지면(기술 주의점) 도구 칸이 밀려 가려진다. 그때는 이 배치 확인을 건너뛴다.
        val toolsShown = LiquifyTool.entries.all { tool ->
            runCatching { composeRule.onNodeWithTag(EditScreenTags.liquifyTool(tool)).assertIsDisplayed() }.isSuccess
        }
        assumeTrue("the work column is squeezed on this emulator", toolsShown)
        val bounds = { tag: String -> composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow() }
        val canvas = bounds(EditScreenTags.LIQUIFY_CANVAS)
        val bar = bounds(EditScreenTags.COVER_TOOL_BAR)
        val apply = bounds(EditScreenTags.LIQUIFY_APPLY)
        assertTrue("canvas $canvas should be left of the bar $bar", canvas.right <= bar.left + 1f)
        assertTrue("bar $bar should be left of the work column ($apply)", bar.right <= apply.left + 1f)
        assertTrue("bar $bar should be a tall column", bar.height > bar.width * 2)
        // 위부터 ✓, 뒤틀기·오목·볼록·재구성, X(한 기둥)
        val column = listOf(apply) + LiquifyTool.entries.map { bounds(EditScreenTags.liquifyTool(it)) } + bounds(EditScreenTags.LIQUIFY_CANCEL)
        for ((upper, lower) in column.zipWithNext()) assertTrue("$upper should be above $lower", upper.bottom <= lower.top + 1f)
        for (item in column) assertEquals("$item", apply.center.x, item.center.x, 2f)
        composeRule.onNodeWithTag(EditScreenTags.liquifyTool(LiquifyTool.PUSH)).assertIsSelected()
        composeRule.onNodeWithTag(EditScreenTags.liquifyTool(LiquifyTool.BLOAT)).performClick()
        composeRule.runOnIdle { assertEquals(LiquifyTool.BLOAT, actions.chosenTool) }

        // 크기·압력 칩은 위아래로 쌓이고, 펼친 화면의 띠는 없다
        assertTrue(bounds(EditScreenTags.bar("size")).top < bounds(EditScreenTags.bar("strength")).top)
        composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertDoesNotExist()
        // 칩을 누르면 같은 기둥이 세로 슬라이더이고, 뒤로 가기는 칩부터 접고 다음이 취소다
        composeRule.onNodeWithTag(EditScreenTags.bar("size")).performClick()
        val slider = bounds(EditScreenTags.LIQUIFY_SIZE)
        assertTrue("$slider should be tall", slider.height > slider.width)
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_SIZE).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue("${actions.calls}", actions.calls.isEmpty()) }
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("cancel"), actions.calls) }
    }

    @Test
    fun mainScreens_optionsAreTheTopLeftStrip_overTheWholeCanvas() {
        // 사용자 피드백 8차 1-5 정정: 새 창 그대로, 수치 조정과 도구 바꾸기는 편집 화면처럼 왼쪽 위 띠(새 창이라 도구 막대는 없다)
        show(930, 700)
        val mode = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_MODE).fetchSemanticsNode().layoutBoundsInWindow()
        val canvasNode = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANVAS).fetchSemanticsNode()
        val dp = with(canvasNode.layoutInfo.density) { 1.dp.toPx() }
        val canvas = canvasNode.layoutBoundsInWindow()
        val strip = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).assertIsDisplayed().fetchSemanticsNode().layoutBoundsInWindow()

        composeRule.onNodeWithTag(EditScreenTags.COVER_TOOL_BAR).assertDoesNotExist()
        // 오른쪽 칸이 없어 캔버스가 창 폭 전체이고, 띠는 그 위 왼쪽 위에 떠 있다
        assertEquals(mode.width, canvas.width, 2 * dp)
        assertEquals(canvas.left + 4 * dp, strip.left, 2 * dp)
        assertEquals(canvas.top + 8 * dp, strip.top, 2 * dp)
        // 칸: 뒤틀기·오목·볼록·재구성(고른 것이 켜짐), 구분선 아래 크기 → 압력(사용자 피드백 10차 9: 수치 칸은 아래)
        var above = Float.NEGATIVE_INFINITY
        for (tool in LiquifyTool.entries) {
            val cell = composeRule.onNodeWithTag(EditScreenTags.liquifyTool(tool)).fetchSemanticsNode().layoutBoundsInWindow()
            assertTrue("$tool $cell should be below $above", cell.top > above)
            above = cell.top
        }
        val size = composeRule.onNodeWithTag(EditScreenTags.strip("size")).fetchSemanticsNode().layoutBoundsInWindow()
        val strength = composeRule.onNodeWithTag(EditScreenTags.strip("strength")).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("size $size should be below the tools ($above)", size.top > above)
        assertTrue("size $size should be above strength $strength", size.top < strength.top)
        composeRule.onNodeWithTag(EditScreenTags.liquifyTool(LiquifyTool.PUSH)).assertIsOn()
    }

    @Test
    fun mainScreens_stripCellsChangeTheToolSizeAndPressure() {
        show(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.liquifyTool(LiquifyTool.RECONSTRUCT)).performClick()
        // 수치 칸을 누르면 옆에 세로 슬라이더 창이 뜬다(편집 화면의 띠와 같다)
        composeRule.onNodeWithTag(EditScreenTags.strip("size")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertIsDisplayed()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(300f)) }
        composeRule.onNodeWithTag(EditScreenTags.strip("strength")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_STRENGTH).performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }

        composeRule.runOnIdle {
            assertEquals(LiquifyTool.RECONSTRUCT, actions.chosenTool)
            assertEquals(300f, checkNotNull(actions.chosenSize), 0f)
            assertEquals(0.8f, checkNotNull(actions.chosenStrength), 0.001f)
        }
    }

    @Test
    fun mainScreens_draggingTheSizeCellUp_raisesTheSize() {
        // 사용자 피드백 9차 1-1: 수치 칸을 누른 채 위로 끌면 커진다(옵션 창은 뜨지 않음)
        show(930, 700)

        composeRule.onNodeWithTag(EditScreenTags.strip("size")).performTouchInput {
            down(center)
            moveBy(Offset(0f, -60f))
            moveBy(Offset(0f, -60f))
            up()
        }

        composeRule.runOnIdle { assertTrue("${actions.chosenSize}", checkNotNull(actions.chosenSize) > 150f) }
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
    }

    @Test
    fun mainScreens_systemBackClosesTheSliderFirst_thenCancels() {
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.strip("size")).performClick()
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertExists()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue("${actions.calls}", "cancel" !in actions.calls) }

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.runOnIdle { assertEquals(listOf("cancel"), actions.calls) }
    }

    @Test
    fun mainScreens_touchingTheCanvasWhileTheSliderIsOpen_onlyClosesIt() {
        // 편집 화면과 같다: 옵션 창이 떠 있는 동안 캔버스를 누르면 닫기만 하고 비틀지 않는다
        show(930, 700)
        composeRule.onNodeWithTag(EditScreenTags.strip("size")).performClick()

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANVAS).performTouchInput { swipe(center, center + Offset(120f, 0f)) }

        composeRule.onNodeWithTag(EditScreenTags.OPTION_FLYOUT).assertDoesNotExist()
        composeRule.runOnIdle { assertTrue("${actions.calls}", "begin" !in actions.calls) }
    }

    @Test
    fun mainScreens_theStripStartsWhereTheEditScreenLeftIt_andTheHandleMovesIt() {
        // 편집 화면의 띠와 같은 자리(설정에 남은 자리, 캔버스 왼쪽 위에서의 dp)를 쓰고, 손잡이로 옮기면 그 자리를 알린다
        stripPosition = StripPosition(300f, 40f)
        show(930, 700)
        val canvasNode = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANVAS).fetchSemanticsNode()
        val dp = with(canvasNode.layoutInfo.density) { 1.dp.toPx() }
        val canvas = canvasNode.layoutBoundsInWindow()
        val strip = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode().layoutBoundsInWindow()
        assertEquals(canvas.left + 300 * dp, strip.left, 2 * dp)
        assertEquals(canvas.top + 40 * dp, strip.top, 2 * dp)
        // 아래로는 캔버스 아래 여백(8dp) 위까지만 옮길 수 있다(접은 화면에 줄여 넣은 테스트에서는 캔버스가 낮다). 남은 자리만큼만 끈다.
        val dy = minOf(60f, canvas.bottom - 8 * dp - strip.bottom - 1f).coerceAtLeast(0f)

        composeRule.onNodeWithTag(EditScreenTags.STRIP_HANDLE).performTouchInput {
            down(center)
            moveBy(Offset(50f, dy / 2))
            moveBy(Offset(50f, dy / 2))
            up()
        }

        composeRule.runOnIdle {
            val spot = positions.last()
            assertEquals(300f + 100f / dp, spot.x, 1f)
            assertEquals(40f + dy / dp, spot.y, 1f)
        }
    }

    @Test
    fun mainPortrait_aHorizontalHinge_doesNotMoveTheStrip() {
        // 사용자 피드백 11차 1: 접히는 선을 비키지 않는다(전에는 띠를 선 위 조각 안에서 끝냈다)
        show(700, 900)
        val strip = composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode().layoutBoundsInWindow()
        val y = strip.center.y.roundToInt()
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = 0, top = y, right = screen.right.roundToInt(), bottom = y, isVertical = false)) }
        composeRule.waitForIdle()

        assertEquals(strip, composeRule.onNodeWithTag(EditScreenTags.OPTION_STRIP).fetchSemanticsNode().layoutBoundsInWindow())
    }

    @Test
    fun editScreen_liquifyToolOpensTheMode_andDoesNotStaySelected() {
        var started = 0
        var open by mutableStateOf<LiquifyState?>(null)
        val ready = EditorState.Ready(
            preview, document,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready,
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        liquify = open,
                        liquifyActions = actions,
                        onStartLiquify = {
                            started++
                            open = state
                        }
                    )
                }
            }
        }

        // 커버는 탭 줄의 유동화 칸으로 연다
        composeRule.chooseCoverTool(EditTool.Liquify)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_MODE).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, started) }
        // 작업 모드를 닫으면 편집 화면에는 고른 도구가 없다(한 줄 막대는 안내 글)
        composeRule.runOnIdle { open = null }
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.BAR_HINT).assertIsDisplayed()
    }
}
