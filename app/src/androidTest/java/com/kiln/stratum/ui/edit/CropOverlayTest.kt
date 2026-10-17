package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 자르기 테두리를 손가락으로 끄는 것을 확인한다. 손가락 떨림 허용 거리만큼은 버려지므로 범위로 본다.
 * 두 손가락 확대는 부모(캔버스 영역)가 받아, 자르기 중에도 된다.
 */
@RunWith(AndroidJUnit4::class)
class CropOverlayTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(2000, 1000)
    private val viewState = CanvasViewState(CanvasView.Fit)
    private val frame = mutableStateOf(CropFrame.whole(document))

    private fun show(initial: CropFrame) {
        frame.value = initial
        // 그림 내용은 보지 않으므로, 3단계(1/8) 크기의 빈 축소본이면 된다
        val preview = Bitmap.createBitmap(250, 125, Bitmap.Config.ARGB_8888)
        val ready = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 3)), document)
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(400.dp, 300.dp)) {
                    CanvasViewport(ready, viewState, Modifier.fillMaxSize().testTag(TAG), overlay = {
                        CropOverlay(frame.value, document, { viewState.view }, { frame.value = it }, Modifier.fillMaxSize())
                    })
                }
            }
        }
    }

    private fun area(): PixelSize {
        val size = composeRule.onNodeWithTag(TAG).fetchSemanticsNode().size
        return PixelSize(size.width, size.height)
    }

    /** 문서 좌표의 화면(캔버스 영역 안) 자리 */
    private fun screenOf(x: Double, y: Double, area: PixelSize): Offset {
        val transform = viewState.view.transform(area, document)
        return Offset(transform.toScreenX(x).toFloat(), transform.toScreenY(y).toFloat())
    }

    private fun drag(from: Offset, by: Offset) {
        composeRule.onNodeWithTag(TAG).performTouchInput { swipe(from, from + by, durationMillis = 300) }
    }

    @Test
    fun dragCorner_resizesFromTheOppositeCorner() {
        show(CropFrame.whole(document))
        val area = area()
        val scale = fitScale(area, document)

        drag(screenOf(2000.0, 1000.0, area), Offset(-100f, -50f))

        composeRule.runOnIdle {
            val f = frame.value
            assertEquals(0.0, f.left, 1e-6)
            assertEquals(0.0, f.top, 1e-6)
            // 떨림 허용 거리만큼은 버려지므로, 끈 거리 안쪽에서 줄었는지 본다
            assertTrue("$f", f.right < 2000 && f.right >= 2000 - 100 / scale - 1)
            assertTrue("$f", f.bottom < 1000 && f.bottom >= 1000 - 50 / scale - 1)
        }
    }

    @Test
    fun lockedRatio_staysSquare() {
        show(CropFrame.whole(document).withRatio(CropRatio.SQUARE, document))
        val area = area()

        drag(screenOf(1500.0, 1000.0, area), Offset(-80f, -20f))

        composeRule.runOnIdle {
            val f = frame.value
            assertEquals(500.0, f.left, 1e-6)
            assertEquals(0.0, f.top, 1e-6)
            assertTrue("$f", f.right < 1500)
            assertEquals(f.width, f.height, 1e-6)
        }
    }

    @Test
    fun dragInside_movesTheFrame() {
        show(CropFrame(500.0, 250.0, 1000.0, 750.0))
        val area = area()

        drag(screenOf(750.0, 500.0, area), Offset(60f, 0f))

        composeRule.runOnIdle {
            val f = frame.value
            assertEquals(500.0, f.width, 1e-6)
            assertEquals(250.0, f.top, 1e-6)
            assertTrue("$f", f.left > 500)
        }
    }

    @Test
    fun dragOutside_changesNothing() {
        val initial = CropFrame(500.0, 250.0, 1000.0, 750.0)
        show(initial)
        val area = area()

        drag(screenOf(1600.0, 900.0, area), Offset(-60f, -30f))

        composeRule.runOnIdle { assertEquals(initial, frame.value) }
    }

    @Test
    fun pinchWhileDragging_zoomsAndPutsTheFrameBack() {
        val initial = CropFrame(500.0, 250.0, 1000.0, 750.0)
        show(initial)
        val area = area()
        val corner = screenOf(1000.0, 750.0, area)

        composeRule.onNodeWithTag(TAG).performTouchInput {
            // 한 손가락으로 모서리를 끌기 시작한다
            down(0, corner)
            repeat(10) { moveBy(0, Offset(-4f, 0f)) }
        }
        composeRule.runOnIdle { assertTrue("drag did not start", frame.value != initial) }
        composeRule.onNodeWithTag(TAG).performTouchInput {
            // 둘째 손가락을 대고 두 손가락을 벌린다
            down(1, center + Offset(60f, 0f))
            repeat(10) {
                updatePointerBy(0, Offset(-12f, 0f))
                updatePointerBy(1, Offset(12f, 0f))
                move()
            }
            up(0)
            up(1)
        }

        composeRule.runOnIdle {
            assertTrue("view ${viewState.view}", viewState.view is CanvasView.Free)
            assertEquals(initial, frame.value)
        }
    }

    private companion object {
        const val TAG = "cropCanvas"
    }
}
