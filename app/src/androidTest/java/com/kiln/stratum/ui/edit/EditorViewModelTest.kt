package com.kiln.stratum.ui.edit

import android.app.Application
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
import org.junit.Test
import org.junit.runner.RunWith

/** 편집 화면 상태가 새 캔버스를 만들고 바꾸는지 에뮬레이터에서 확인한다. */
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

    @Test
    fun close_returnsToEmpty() {
        onMain { viewModel.newCanvas(640, 480, CanvasFill.Transparent) }
        awaitState(readyWithSize(640, 480))

        onMain { viewModel.close() }

        assertEquals(EditorState.Empty, viewModel.state.value)
    }
}
