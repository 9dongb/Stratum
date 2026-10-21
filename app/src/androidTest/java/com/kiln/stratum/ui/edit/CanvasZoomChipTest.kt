package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 상단 바 가운데의 배율 표시(사용자 피드백 9차): 늘 보이고(맞춤이면 맞춤 배율), 확대 상태에서 누르면 맞추고,
 * 바 가운데(화면 가운데)에 있으며 바의 버튼에 걸리지 않는지 확인한다. 카메라 구멍을 피하는 계산은 `TopBarInsetsTest`가 본다.
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

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().layoutBoundsInWindow()

    /** 맞춤일 때 보일 배율: 캔버스 영역에 문서가 꼭 들어가는 배율(퍼센트 글자) */
    private fun fitPercent(): String {
        val canvas = bounds(EditScreenTags.CANVAS)
        return zoomPercentText(minOf(canvas.width / document.width, canvas.height / document.height).toDouble())
    }

    @Test
    fun atFit_showsTheFitZoom_andHasNothingToTap() {
        show(DpSize(930.dp, 700.dp))

        val percent = fitPercent()
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.canvas_zoom_description, percent))
            .assertHasNoClickAction()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.canvas_zoom_percent, percent), useUnmergedTree = true).assertExists()
    }

    @Test
    fun zoomed_showsTheScale_andTapFits() {
        show(DpSize(930.dp, 700.dp))
        val fit = fitPercent()

        // 두 손가락으로 크게 벌려 확대한다
        composeRule.onNodeWithTag(EditScreenTags.CANVAS).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(200f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(200f, 0f)
            )
        }
        composeRule.waitForIdle()
        val zoomed = composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].single()
        assertTrue("$zoomed should not be the fit zoom $fit", !zoomed.contains(fit))

        // 누르면 화면에 맞춘다
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP).performClick()
        composeRule.onNodeWithTag(EditScreenTags.ZOOM_CHIP)
            .assertContentDescriptionEquals(composeRule.activity.getString(R.string.canvas_zoom_description, fit))
    }

    @Test
    fun sitsInTheMiddleOfTheBar_evenOverACenterHinge() {
        show(DpSize(930.dp, 700.dp))
        val bar = bounds(EditScreenTags.TOP_BAR)
        assertEquals(bar.center.x, bounds(EditScreenTags.ZOOM_CHIP).center.x, 2f)

        // 펼친 폴드의 가운데 접히는 선 위에도 그대로 둔다(사용자 결정: 배율 표시만 힌지 규칙의 예외)
        val screen = composeRule.onRoot().fetchSemanticsNode().layoutBoundsInWindow()
        val centerX = bar.center.x.roundToInt()
        composeRule.runOnIdle { hinges.value = listOf(Hinge(left = centerX, top = 0, right = centerX, bottom = screen.bottom.roundToInt(), isVertical = true)) }
        composeRule.waitForIdle()
        assertEquals(bar.center.x, bounds(EditScreenTags.ZOOM_CHIP).center.x, 2f)
    }

    @Test
    fun cover_staysClearOfTheBarButtons() {
        show(DpSize(411.dp, 800.dp))
        val chip = bounds(EditScreenTags.ZOOM_CHIP)
        val undo = bounds(EditScreenTags.UNDO)
        // 커버는 오른쪽 버튼들이 가운데 가까이 와서, 겹치지 않게 그 왼쪽에 둔다
        assertTrue("$chip should end before the undo button $undo", chip.right <= undo.left + 1f)
    }
}
