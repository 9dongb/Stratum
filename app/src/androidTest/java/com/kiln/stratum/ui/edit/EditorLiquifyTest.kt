package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.project.ProjectLibrary
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 픽셀 유동화 작업 모드의 흐름(열기·끌기·되돌리기·적용·취소)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorLiquifyTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() {
        runBlocking { ProjectLibrary.get(context).awaitSaves() }
        File(context.filesDir, "projects").deleteRecursively()
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    private fun awaitLiquify(predicate: (LiquifyState?) -> Boolean): LiquifyState? = runBlocking {
        withTimeout(30_000) { viewModel.liquify.first(predicate) }
    }

    /** 흰 캔버스 1000×800 가운데에 검은 세로 줄(x 495..505)을 그린다 */
    private fun openWithLine(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        val opened = awaitReady { it.documentSize == SIZE }
        val line = StrokeDabs.place(listOf(StrokePoint(500f, 50f), StrokePoint(500f, 750f)), 1f)
        onMain { viewModel.edit(PaintStroke(LayerId(0), line, BrushSettings(size = 10f, hardness = 1f, color = Color.BLACK), false)) }
        return awaitReady { it.revision > opened.revision }
    }

    private fun pushRight() {
        onMain {
            viewModel.liquifyActions.setTool(LiquifyTool.PUSH)
            viewModel.liquifyActions.setSize(240f)
            viewModel.liquifyActions.setStrength(1f)
            viewModel.liquifyActions.begin(500f, 400f)
            for (step in 1..8) viewModel.liquifyActions.move(500f + step * 5f, 400f)
            viewModel.liquifyActions.end()
        }
    }

    private fun dark(bitmap: Bitmap, x: Int, y: Int) = Color.red(bitmap.getPixel(x, y)) < 100

    @Test
    fun startPushUndo_updatesTheModeState() {
        openWithLine()

        onMain { viewModel.startLiquify() }
        val started = checkNotNull(awaitLiquify { it != null })
        assertFalse(started.changed)
        assertFalse(started.canUndo)
        assertEquals(SIZE, started.documentSize)

        pushRight()
        val pushed = checkNotNull(awaitLiquify { it?.changed == true && it.preview !== started.preview })
        assertTrue(pushed.canUndo)
        assertNotSame(started.preview.bitmap, pushed.preview.bitmap)

        onMain { viewModel.liquifyActions.undo() }
        awaitLiquify { it != null && !it.changed && !it.canUndo }
    }

    @Test
    fun apply_recordsOneUndoStep_andMovesThePixels() {
        val before = openWithLine()
        onMain { viewModel.startLiquify() }
        awaitLiquify { it != null }
        pushRight()
        awaitLiquify { it?.changed == true }

        onMain { viewModel.liquifyActions.apply() }

        awaitLiquify { it == null }
        val after = awaitReady { it.revision > before.revision }
        assertTrue(after.canUndo)
        // 1/1 단계 축소본(캔버스가 문서보다 커서 원본 크기)에서 가운데 줄이 오른쪽으로 옮겨졌다
        val shown = after.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val scale = 1 shl after.base.region.level
        assertFalse(dark(shown, 500 / scale, 400 / scale))
        assertTrue((520 / scale..560 / scale).any { dark(shown, it, 400 / scale) })

        // 실행 취소 한 단계로 원래대로 돌아간다
        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > after.revision }
        assertTrue(dark(undone.base.bitmap.copy(Bitmap.Config.ARGB_8888, false), 500 / scale, 400 / scale))
    }

    @Test
    fun cancel_closesWithoutChangingTheDocument() {
        val before = openWithLine()
        onMain { viewModel.startLiquify() }
        awaitLiquify { it != null }
        pushRight()
        awaitLiquify { it?.changed == true }

        onMain { viewModel.liquifyActions.cancel() }

        awaitLiquify { it == null }
        val after = viewModel.state.value as EditorState.Ready
        assertEquals(before.revision, after.revision)
    }

    @Test
    fun closingTheDocument_closesTheMode() {
        openWithLine()
        onMain { viewModel.startLiquify() }
        awaitLiquify { it != null }

        onMain { viewModel.close() }

        awaitLiquify { it == null }
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
