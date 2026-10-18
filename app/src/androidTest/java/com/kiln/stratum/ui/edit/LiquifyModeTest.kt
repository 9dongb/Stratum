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
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.assertClickablesAvoid
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 픽셀 유동화 작업 모드의 버튼·옵션·캔버스 끌기와, 편집 화면에서 여는 것을 확인한다(엔진 대신 받은 일만 적는 가짜). */
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

    private fun show(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme { LiquifyMode(state, actions) }
                }
            }
        }
    }

    @Test
    fun buttons_followTheState() {
        show(411, 800)

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANCEL).assertIsEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_UNDO).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_RESET).assertIsNotEnabled()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_APPLY).assertIsNotEnabled()

        composeRule.runOnIdle { state = state.copy(canUndo = true, changed = true) }
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_UNDO).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_RESET).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_APPLY).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANCEL).performClick()

        composeRule.runOnIdle { assertEquals(listOf("undo", "reset", "apply", "cancel"), actions.calls) }
    }

    @Test
    fun topBar_isAsLowAsTheEditScreens() {
        show(411, 800)
        val bar = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_TOP_BAR).fetchSemanticsNode()
        val density = bar.layoutInfo.density.density
        val insets = composeRule.runOnIdle { composeRule.activity.window.decorView.rootWindowInsets }
        val statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top
        val safeTop = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top

        // 편집 화면 상단 바와 같은 48dp에 상태 표시줄(또는 버튼·제목에 걸리는 카메라 구멍) 높이만 더한다
        val height = bar.layoutBoundsInWindow().height
        assertTrue("top bar $height px", height >= 48 * density + statusTop - 1f && height <= 48 * density + safeTop + 1f)
    }

    @Test
    fun whileApplying_everythingIsBlocked() {
        state = state.copy(canUndo = true, changed = true, busy = true)
        show(411, 800)

        for (tag in listOf(EditScreenTags.LIQUIFY_CANCEL, EditScreenTags.LIQUIFY_UNDO, EditScreenTags.LIQUIFY_RESET, EditScreenTags.LIQUIFY_APPLY)) {
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
    fun options_changeToolSizeAndPressure() {
        show(411, 800)

        composeRule.onNodeWithTag(EditScreenTags.liquifyTool(LiquifyTool.BLOAT)).performClick()
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_SIZE).performSemanticsAction(SemanticsActions.SetProgress) { it(sizeToFraction(300f)) }
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
    fun mainLandscape_optionsOnTheRight() {
        show(930, 700)

        val options = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OPTIONS).assertIsDisplayed().fetchSemanticsNode().layoutBoundsInWindow()
        val canvas = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_CANVAS).fetchSemanticsNode().layoutBoundsInWindow()
        assertTrue("options $options, canvas $canvas", options.left >= canvas.right - 1)
    }

    @Test
    fun mainPortrait_optionsAvoidTheHorizontalHinge() {
        show(700, 900)
        val options = composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_OPTIONS).fetchSemanticsNode().layoutBoundsInWindow()
        val y = options.center.y.roundToInt()
        val hinge = Hinge(left = 0, top = y, right = 10_000, bottom = y, isVertical = false)
        composeRule.runOnIdle { hinges.value = listOf(hinge) }

        composeRule.assertClickablesAvoid(hinge)
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

        // 탭 줄 뒤쪽에 있어 커버 폭에서는 밀어야 다 보인다
        composeRule.onNodeWithTag(EditScreenTags.tabTool(EditTool.Liquify)).performScrollTo().performClick()

        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_MODE).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, started) }
        // 작업 모드를 닫으면 편집 화면에는 고른 도구가 없다(옵션이 보이지 않음)
        composeRule.runOnIdle { open = null }
        composeRule.onNodeWithTag(EditScreenTags.LIQUIFY_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(EditScreenTags.TOOL_OPTIONS).assertDoesNotExist()
    }
}
