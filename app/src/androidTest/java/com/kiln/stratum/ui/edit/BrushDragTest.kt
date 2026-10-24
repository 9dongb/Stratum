package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ctrl(Command)+Alt를 누른 채 마우스 왼쪽 버튼으로 끌면 붓 크기(좌우)·경도(위아래)를 바꾼다(사용자 피드백 13차 9).
 * 캔버스([CanvasViewport])가 알리는 축·거리와, 그 누름이 도구 화면에 가지 않는지를 본다.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BrushDragTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(400, 400)
    private var flip by mutableStateOf(ViewFlip.None)
    private val events = mutableListOf<String>()
    private var lastDrag: Pair<SwipeAxis, Float>? = null
    private var toolTaps = 0

    private val recorder = object : BrushDragActions {
        override fun start(at: Offset) {
            events += "start"
        }

        override fun drag(axis: SwipeAxis, distance: Dp) {
            lastDrag = axis to distance.value
        }

        override fun end() {
            events += "end"
        }
    }

    private fun show() {
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(document, 0)),
            document
        )
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(400.dp, 400.dp)) {
                    CanvasViewport(ready, CanvasViewState(CanvasView.Fit), Modifier.fillMaxSize().testTag(TAG), brushDrag = recorder, flip = flip) {
                        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toolTaps++ } })
                    }
                }
            }
        }
    }

    /** Ctrl+Alt를 누른 채 왼쪽 버튼으로 [node]의 가운데에서 [by]만큼 끈다. [release]가 false면 버튼을 누른 채 둔다. */
    private fun ctrlAltDrag(by: Offset, node: SemanticsNodeInteraction = composeRule.onNodeWithTag(TAG), release: Boolean = true) {
        node.performMultiModalInput {
            key {
                keyDown(Key.CtrlLeft)
                keyDown(Key.AltLeft)
            }
            mouse {
                moveTo(center)
                press()
                repeat(10) { moveBy(by / 10f) }
                if (release) release()
            }
            if (release) {
                key {
                    keyUp(Key.AltLeft)
                    keyUp(Key.CtrlLeft)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rightAndUp_growTheSizeAndTheHardness_withoutReachingTheTool() {
        show()

        ctrlAltDrag(Offset(150f, 10f))
        composeRule.runOnIdle {
            assertEquals(listOf("start", "end"), events)
            val (axis, distance) = checkNotNull(lastDrag)
            assertEquals(SwipeAxis.HORIZONTAL, axis)
            assertTrue("$distance", distance > 0f)
        }

        ctrlAltDrag(Offset(5f, -150f))
        composeRule.runOnIdle {
            val (axis, distance) = checkNotNull(lastDrag)
            // 위로 끌면 경도가 오른다(+, 사용자 결정 "위로 끌면 단단하게")
            assertEquals(SwipeAxis.VERTICAL, axis)
            assertTrue("$distance", distance > 0f)
            assertEquals(0, toolTaps)
        }
    }

    @Test
    fun flippedCanvas_reportsTheScreenDirection() {
        flip = ViewFlip(horizontal = true, vertical = true)
        show()

        ctrlAltDrag(Offset(-150f, 0f))
        composeRule.runOnIdle {
            val (axis, distance) = checkNotNull(lastDrag)
            assertEquals(SwipeAxis.HORIZONTAL, axis)
            assertTrue("left on screen shrinks: $distance", distance < 0f)
        }
    }

    @Test
    fun plainClick_goesToTheTool() {
        show()

        composeRule.onNodeWithTag(TAG).performMouseInput { click(center) }

        composeRule.runOnIdle {
            assertEquals(1, toolTaps)
            assertTrue(events.isEmpty())
        }
    }

    // --- 편집 화면 ---

    private var brush by mutableStateOf(BrushSettings(size = 40f, hardness = 0.5f))
    private var brushStrokes = 0

    /** 커버 크기 편집 화면에서 브러시를 고른다. 세 손가락 제스처 설정은 끈다(그것과 상관없이 된다). */
    private fun showEditScreen() {
        val size = PixelSize(1000, 1000)
        val ready = EditorState.Ready(
            PreviewImage(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), PreviewLevels.wholeRegion(size, 1)),
            size,
            layers = listOf(LayerSummary(LayerId(0), "Background", 1f, BlendMode.NORMAL, Adjustments.NONE)),
            activeLayer = LayerId(0)
        )
        val strokes = object : StrokeActions by StrokeActions.None {
            override fun begin(x: Float, y: Float, erase: Boolean) {
                brushStrokes++
            }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(411.dp, 800.dp))) {
                StratumTheme {
                    EditScreen(
                        state = ready,
                        onBack = {},
                        onCanvasSize = { _, _ -> },
                        onViewport = {},
                        onChooseSize = {},
                        brush = brush,
                        onBrush = { brush = it },
                        strokeActions = strokes,
                        threeFingerGestures = false
                    )
                }
            }
        }
        composeRule.chooseCoverTool(EditTool.Brush)
    }

    @Test
    fun editScreen_rightGrowsTheBrush_upHardensIt_andNothingIsPainted() {
        showEditScreen()
        // 캔버스 가운데에서 끈다(한 줄 막대는 캔버스 영역 맨 아래에 있다)
        val canvas = composeRule.onNodeWithTag(EditScreenTags.CANVAS)

        ctrlAltDrag(Offset(150f, 0f), canvas, release = false)
        // 끄는 동안 크기 원이 보인다
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertExists()
        canvas.performMultiModalInput {
            mouse { release() }
            key {
                keyUp(Key.AltLeft)
                keyUp(Key.CtrlLeft)
            }
        }
        composeRule.onNodeWithTag(EditScreenTags.SIZE_PREVIEW).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue("${brush.size}", brush.size > 40f)
            assertEquals(0.5f, brush.hardness, 0f)
        }

        val sized = brush.size
        ctrlAltDrag(Offset(0f, -150f), canvas)
        composeRule.runOnIdle {
            assertTrue("${brush.hardness}", brush.hardness > 0.5f)
            assertEquals(sized, brush.size, 0f)
            assertEquals(0, brushStrokes)
        }
    }

    private companion object {
        const val TAG = "brushDragViewport"
    }
}
