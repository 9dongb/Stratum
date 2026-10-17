package com.kiln.stratum.ui.edit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.multiTouchSwipe
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.ui.theme.StratumTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 캔버스의 두 손가락 확대·이동과 보기 상태 저장을 확인한다. 손가락 떨림 허용 거리만큼은 버려지므로 범위로 본다. */
@RunWith(AndroidJUnit4::class)
class CanvasViewportTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val document = PixelSize(2000, 1000)
    private val viewState = CanvasViewState(CanvasView.Fit)

    private fun show(onViewport: (Viewport) -> Unit = {}) {
        // 그림 내용은 보지 않으므로, 3단계(1/8) 크기의 빈 축소본이면 된다
        val preview = Bitmap.createBitmap(250, 125, Bitmap.Config.ARGB_8888)
        val ready = EditorState.Ready(PreviewImage(preview, PreviewLevels.wholeRegion(document, 3)), document)
        composeRule.setContent {
            StratumTheme {
                Box(Modifier.size(400.dp, 300.dp)) {
                    CanvasViewport(ready, viewState, Modifier.fillMaxSize().testTag(TAG), onViewport)
                }
            }
        }
    }

    private fun area(): PixelSize {
        val size = composeRule.onNodeWithTag(TAG).fetchSemanticsNode().size
        return PixelSize(size.width, size.height)
    }

    @Test
    fun pinch_zoomsAroundFingers() {
        show()

        // 가운데에서 양쪽으로 벌린다
        composeRule.onNodeWithTag(TAG).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(300f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(300f, 0f)
            )
        }

        val area = area()
        composeRule.runOnIdle {
            val view = viewState.view as CanvasView.Free
            assertTrue("scale ${view.scale}", view.scale > fitScale(area, document) * 3)
            // 손가락 가운데(영역 가운데) 아래에 있던 문서 가운데가 그대로 가운데에 있다
            assertEquals(1000.0, view.centerX, 1.0)
            assertEquals(500.0, view.centerY, 1.0)
        }
    }

    @Test
    fun twoFingerDrag_pans() {
        show()

        // 두 손가락을 나란히 오른쪽으로 200px 끈다. 문서는 따라가고, 가운데의 문서 위치는 왼쪽으로 간다.
        composeRule.onNodeWithTag(TAG).performTouchInput {
            val left = center - Offset(100f, 0f)
            val right = center + Offset(100f, 0f)
            multiTouchSwipe(
                curves = listOf(
                    { t: Long -> left + Offset(200f * t / 500f, 0f) },
                    { t: Long -> right + Offset(200f * t / 500f, 0f) }
                ),
                durationMillis = 500
            )
        }

        val area = area()
        composeRule.runOnIdle {
            val fit = fitScale(area, document)
            val view = viewState.view as CanvasView.Free
            assertEquals(fit, view.scale, 1e-6)
            // 떨림 허용 거리만큼은 버려지므로 100~200px 사이로 옮겨졌는지 본다
            assertTrue("centerX ${view.centerX}", view.centerX <= 1000 - 100 / fit && view.centerX >= 1000 - 200 / fit - 1)
        }
    }

    @Test
    fun oneFingerDrag_doesNothing() {
        show()

        composeRule.onNodeWithTag(TAG).performTouchInput { swipe(center, center + Offset(300f, 100f), 300) }

        composeRule.runOnIdle { assertEquals(CanvasView.Fit, viewState.view) }
    }

    @Test
    fun remainingFingerAfterLift_doesNotPan() {
        show()
        val node = composeRule.onNodeWithTag(TAG)

        // 두 손가락으로 오른쪽으로 100px 옮겨 조작을 시작하고, 오른쪽 손가락을 뗀다
        node.performTouchInput {
            down(0, center - Offset(100f, 0f))
            down(1, center + Offset(100f, 0f))
            repeat(10) {
                updatePointerBy(0, Offset(10f, 0f))
                updatePointerBy(1, Offset(10f, 0f))
                move()
            }
            up(1)
        }
        val afterTwoFingers = composeRule.runOnIdle { viewState.view }
        // 남은 손가락만 움직이면 아무 일도 없다
        node.performTouchInput {
            repeat(10) { moveBy(0, Offset(20f, 0f)) }
            up(0)
        }

        composeRule.runOnIdle {
            assertTrue(afterTwoFingers is CanvasView.Free)
            assertEquals(afterTwoFingers, viewState.view)
        }
    }

    @Test
    fun reportsViewport_whenViewChanges() {
        // 알림은 메인 스레드에서 오고, 확인은 테스트 스레드에서 한다
        val reported = AtomicReference<Viewport?>(null)
        show(onViewport = { reported.set(it) })
        val area = area()

        // 처음에는 맞춘 보기를 알린다
        val fit = CanvasView.Fit.viewport(area, document)
        composeRule.waitUntil(5_000) { reported.get() == fit }

        val zoomed = CanvasView.Free(scale = 2.5, centerX = 300.0, centerY = 400.0)
        composeRule.runOnIdle { viewState.view = zoomed }

        composeRule.waitUntil(5_000) { reported.get() == zoomed.viewport(area, document) }
    }

    @Test
    fun zoomedView_survivesStateRestore() {
        val tester = StateRestorationTester(composeRule)
        var restored: CanvasViewState? = null
        tester.setContent { restored = rememberCanvasViewState(document) }
        val zoomed = CanvasView.Free(scale = 2.5, centerX = 300.0, centerY = 400.0)
        composeRule.runOnIdle { checkNotNull(restored).view = zoomed }

        // 접기·펼치기나 백그라운드 종료로 화면이 다시 만들어지는 것처럼 저장했다가 되살린다
        tester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle { assertEquals(zoomed, checkNotNull(restored).view) }
    }

    private companion object {
        const val TAG = "canvasViewport"
    }
}
