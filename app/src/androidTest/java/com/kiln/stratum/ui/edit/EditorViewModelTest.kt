package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.raster.PixelSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 편집 화면 상태가 새 캔버스를 만들고 바꾸는지, 확대해 보면 보이는 곳을 선명하게 다시 그리는지 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorViewModelTest {

    private val viewModel = EditorViewModel(ApplicationProvider.getApplicationContext<Application>())

    /** 편집 상태는 메인 스레드에서만 다루므로, 부르는 것도 메인 스레드에서 한다. */
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    /** [predicate]를 만족하는 상태가 될 때까지 기다린다. */
    private fun awaitState(predicate: (EditorState) -> Boolean): EditorState = runBlocking {
        withTimeout(30_000) { viewModel.state.first(predicate) }
    }

    private fun readyWithSize(width: Int, height: Int): (EditorState) -> Boolean = {
        it is EditorState.Ready && it.documentSize == PixelSize(width, height)
    }

    @Test
    fun newCanvas_becomesReadyWithDocumentSize() {
        onMain { viewModel.newCanvas(1001, 801, CanvasFill.Solid(Color.WHITE)) }

        val state = awaitState { it is EditorState.Ready || it is EditorState.Failed }

        assertEquals(PixelSize(1001, 801), (state as EditorState.Ready).documentSize)
    }

    @Test
    fun newCanvas_replacesTheOpenDocument() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Transparent) }
        awaitState(readyWithSize(640, 480))

        onMain { viewModel.newCanvas(300, 200, CanvasFill.Solid(Color.BLACK)) }

        awaitState(readyWithSize(300, 200))
    }

    /** 캔버스 영역 [CANVAS_AREA]에 넓은 흰 캔버스 [WIDE]를 연다. 화면에 맞추면 1/2 축소본으로 보인다. */
    private fun openWideCanvas(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(CANVAS_AREA.width, CANVAS_AREA.height)
            viewModel.newCanvas(WIDE.width, WIDE.height, CanvasFill.Solid(Color.WHITE))
        }
        return awaitState(readyWithSize(WIDE.width, WIDE.height)) as EditorState.Ready
    }

    private fun viewportOf(view: CanvasView): Viewport = view.viewport(CANVAS_AREA, WIDE)

    @Test
    fun zoomedIn_sharpensVisiblePart_fitDropsIt() {
        val ready = openWideCanvas()
        assertEquals(1, ready.base.region.level)
        assertNull(ready.detail)

        // 400%로 문서 가운데를 본다. 1/2 축소본을 8배로 키우면 흐리므로, 보이는 곳을 원본 단계로 다시 그린다.
        val zoomed = viewportOf(CanvasView.Free(scale = 4.0, centerX = 1300.0, centerY = 260.0))
        onMain { viewModel.onViewport(zoomed) }

        val sharpened = awaitState { it is EditorState.Ready && it.detail != null } as EditorState.Ready
        val detail = checkNotNull(sharpened.detail)
        assertEquals(0, detail.region.level)
        assertTrue("${detail.region.rect} does not cover ${zoomed.visible}", zoomed.visible in detail.region.rect)
        assertEquals(detail.region.previewWidth, detail.bitmap.width)
        assertEquals(detail.region.previewHeight, detail.bitmap.height)
        assertEquals(Bitmap.Config.HARDWARE, detail.bitmap.config)
        // 전체 축소본은 그대로다
        assertSame(ready.base, sharpened.base)

        // 화면에 맞추면 부분 그림이 필요 없어 지운다
        onMain { viewModel.onViewport(viewportOf(CanvasView.Fit)) }
        awaitState { it is EditorState.Ready && it.detail == null }
    }

    @Test
    fun close_whileSharpening_staysEmpty() {
        openWideCanvas()
        onMain { viewModel.onViewport(viewportOf(CanvasView.Free(scale = 4.0, centerX = 1300.0, centerY = 260.0))) }

        // 기다림이 끝나 그리기를 시작할 즈음 닫는다
        Thread.sleep(SHARPEN_DELAY_MILLIS)
        onMain { viewModel.close() }

        // 그리던 것이 끝날 만큼 기다려도, 늦게 끝난 그림이 빈 상태를 덮지 않는다
        Thread.sleep(1_000)
        assertEquals(EditorState.Empty, viewModel.state.value)
    }

    @Test
    fun close_returnsToEmpty() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Transparent) }
        awaitState(readyWithSize(640, 480))

        onMain { viewModel.close() }

        assertEquals(EditorState.Empty, viewModel.state.value)
    }

    private companion object {
        val CANVAS_AREA = PixelSize(1000, 800)
        val WIDE = PixelSize(2600, 520)
    }
}
