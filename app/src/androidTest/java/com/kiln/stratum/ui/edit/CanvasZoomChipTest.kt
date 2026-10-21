package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.R
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.layout.Hinge
import com.kiln.stratum.ui.layout.LocalHinges
import com.kiln.stratum.ui.layout.layoutBoundsInWindow
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 캔버스 왼쪽 위의 배율 표시(사용자 피드백 10차 8): 처음에는 없고, 손으로 배율을 바꿀 때만 왼쪽 위에 보였다가 1초 뒤 0.5초 동안
 * 서서히 사라지며, 보이는 동안 누르면 화면에 맞춘다. 옮기기만 하면 보이지 않고, 도구 옵션 띠를 피한다(접히는 선은 보지 않는다, 11차).
 * 숨은 동안의 화면 읽기 기능(캔버스 그림의 배율·'화면에 맞추기')도 본다. 자리 계산은 `HingePlacementTest`가 본다.
 */
@RunWith(AndroidJUnit4::class)
class CanvasZoomChipTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 가짜 힌지. 테스트 중에 바꾸면 다시 배치된다. */
    private val hinges = mutableStateOf(emptyList<Hinge>())
    private val document = PixelSize(4000, 3000)
    private val preview = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)

    private fun show(size: DpSize) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        EditScreen(
                            state = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 0)), document),
                            onBack = {},
                            onCanvasSize = { _, _ -> },
                            onViewport = {},
                            onChooseSize = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 배율 표시만 띄운다(화면 크기 700×900, 확대한 보기). 힌지와 상관없는지 볼 때 쓴다. */
    private fun showChipAlone(): CanvasViewState {
        val viewState = CanvasViewState(CanvasView.Free(scale = 2.5, centerX = 100.0, centerY = 100.0))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(700.dp, 900.dp))) {
                CompositionLocalProvider(LocalHinges provides hinges.value) {
                    StratumTheme {
                        Box(Modifier.fillMaxSize()) { CanvasZoomChip(viewState, document, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        composeRule.runOnIdle { viewState.noteZoom(2.5) }
        composeRule.waitForIdle()
        return viewState
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()

    private fun dp(tag: String): Float = with(composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.density) { 1.dp.toPx() }

    /** 캔버스 가운데에서 두 손가락을 크게 벌려 확대한다 */
    private fun pinchOut() {
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(200f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(200f, 0f)
            )
        }
        composeRule.waitForIdle()
    }

    /** 맞춤일 때의 배율: 캔버스 영역에 문서가 꼭 들어가는 배율(퍼센트 글자) */
    private fun fitPercent(): String {
        val canvas = bounds(EditScreenTags.CANVAS)
        return zoomPercentText(minOf(canvas.width / document.width, canvas.height / document.height).toDouble())
    }

    private fun canvasZoomDescription(): String? =
        composeRule.canvasImage().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun canvasFitAction() =
        composeRule.canvasImage().fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)
            ?.singleOrNull { it.label == composeRule.activity.getString(R.string.canvas_fit) }

    @Test
    fun hiddenAtFirst_shownAtTheCanvasTopLeftWhenZoomed_thenFadesOut() {
        show(DpSize(411.dp, 800.dp))
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()
        // 화면 읽기 기능은 캔버스에서 지금 배율을 읽는다(맞춤이면 맞출 것이 없다)
        assertEquals(composeRule.activity.getString(R.string.canvas_zoom_description, fitPercent()), canvasZoomDescription())
        assertNull(canvasFitAction())

        pinchOut()

        val canvas = bounds(EditScreenTags.CANVAS)
        val chip = bounds(EditScreenTags.ZOOM_CHIP)
        val dp = dp(EditScreenTags.ZOOM_CHIP)
        assertEquals(canvas.left + 8 * dp, chip.left, 2 * dp)
        assertEquals(canvas.top + 8 * dp, chip.top, 2 * dp)
        val shown = composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertFalse("$shown should not be the fit zoom ${fitPercent()}", shown.contains("${fitPercent()}%"))

        // 1초 동안은 그대로 있다가 0.5초 동안 사라진다
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(ZOOM_CHIP_HOLD_MILLIS - 200)
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertExists()
        composeRule.mainClock.advanceTimeBy(200L + ZOOM_CHIP_FADE_MILLIS + 200)
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun tapWhileShown_fits_andShowsTheFitZoomBriefly() {
        show(DpSize(930.dp, 700.dp))
        pinchOut()
        assertTrue(canvasFitAction() != null)

        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).performClick()

        val fit = fitPercent()
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.canvas_zoom_fit_description, fit))
        assertEquals(composeRule.activity.getString(R.string.canvas_zoom_description, fit), canvasZoomDescription())
        assertNull(canvasFitAction())
    }

    @Test
    fun movingWithTwoFingersOnly_doesNotShowIt() {
        show(DpSize(930.dp, 700.dp))

        // 두 손가락을 같은 쪽으로 옮긴다(배율은 그대로)
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            down(0, center - Offset(100f, 0f))
            down(1, center + Offset(100f, 0f))
            repeat(10) {
                updatePointerBy(0, Offset(15f, 10f))
                updatePointerBy(1, Offset(15f, 10f))
                move()
            }
            up(0)
            up(1)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()
        // 옮긴 보기라 화면 읽기 기능으로는 맞출 수 있다
        assertTrue(canvasFitAction() != null)
    }

    @Test
    fun theScreenReaderStopsOnTheCanvasImage_whichCarriesTheZoom() {
        // 화면 읽기 기능은 내용이 있는 잎 노드에 머문다. 배율과 '화면에 맞추기'가 캔버스 그림에 있어야 들을 수 있다(검토에서 찾음).
        show(DpSize(930.dp, 700.dp))
        pinchOut()

        val image = composeRule.canvasImage().fetchSemanticsNode()
        assertTrue("${image.children}", image.children.isEmpty())
        assertEquals(listOf(composeRule.activity.getString(R.string.edit_canvas_description)), image.config[SemanticsProperties.ContentDescription])
        assertTrue(canvasZoomDescription() != null)
        assertTrue(canvasFitAction() != null)
    }

    @Test
    fun comingBackOnScreen_doesNotShowAnOldZoomAgain() {
        // 닫힌 화면에서 자르기 창을 열고 닫으면 배율 표시가 다른 자리에서 다시 그려진다. 지난 바뀜을 다시 보이지 않는다(검토에서 찾음).
        val viewState = CanvasViewState(CanvasView.Free(scale = 2.5, centerX = 100.0, centerY = 100.0))
        val first = mutableStateOf(true)
        composeRule.setContent {
            StratumTheme {
                if (first.value) {
                    Box(Modifier.fillMaxSize()) { CanvasZoomChip(viewState, document, Modifier.fillMaxSize()) }
                } else {
                    Column(Modifier.fillMaxSize()) { CanvasZoomChip(viewState, document, Modifier.fillMaxSize()) }
                }
            }
        }
        composeRule.runOnIdle { viewState.noteZoom(2.5) }
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertExists()
        composeRule.mainClock.advanceTimeBy(ZOOM_CHIP_HOLD_MILLIS + ZOOM_CHIP_FADE_MILLIS + 500)
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()

        composeRule.runOnIdle { first.value = false }

        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertDoesNotExist()
        // 다시 배율을 바꾸면 보인다
        composeRule.runOnIdle { viewState.noteZoom(0.5) }
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).assertExists()
    }

    @Test
    fun canvasFitAction_fitsWithoutTheChip() {
        show(DpSize(930.dp, 700.dp))
        pinchOut()
        val fitLabel = composeRule.activity.getString(R.string.canvas_fit)

        val fit = checkNotNull(canvasFitAction())
        composeRule.runOnIdle { fit.action() }

        assertEquals(composeRule.activity.getString(R.string.canvas_zoom_description, fitPercent()), canvasZoomDescription())
        assertNull("$fitLabel should be gone at fit", canvasFitAction())
    }

    @Test
    fun main_staysRightOfTheStripAtTheTopLeft() {
        show(DpSize(930.dp, 700.dp))
        composeRule.onNodeWithTag(EditScreenTags.railTool(EditTool.Brush)).performClick()
        val strip = bounds(EditScreenTags.OPTION_STRIP)

        pinchOut()

        val chip = bounds(EditScreenTags.ZOOM_CHIP)
        val dp = dp(EditScreenTags.ZOOM_CHIP)
        assertEquals("chip $chip strip $strip canvas ${bounds(EditScreenTags.CANVAS)} dp $dp", strip.right + 8 * dp, chip.left, 2 * dp)
        assertEquals(bounds(EditScreenTags.CANVAS).top + 8 * dp, chip.top, 2 * dp)
    }

    @Test
    fun staysAtTheTopLeft_evenOnAHinge() {
        // 사용자 피드백 11차 1: 접히는 선을 비키지 않는다(전에는 선 오른쪽·아래로 옮겼다)
        showChipAlone()
        val before = bounds(EditScreenTags.ZOOM_CHIP)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        // 배율 표시 한가운데를 세로로, 또 가로로 지나는 힌지
        val vertical = Hinge(left = before.center.x.roundToInt(), top = 0, right = before.center.x.roundToInt(), bottom = screen.bottom.roundToInt(), isVertical = true)
        val horizontal = Hinge(left = 0, top = before.center.y.roundToInt(), right = screen.right.roundToInt(), bottom = before.center.y.roundToInt(), isVertical = false)

        composeRule.runOnIdle { hinges.value = listOf(vertical, horizontal) }

        assertEquals(before, bounds(EditScreenTags.ZOOM_CHIP))
    }
}
