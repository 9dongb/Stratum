package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.project.ProjectLibrary
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 보정 슬라이더를 끄는 동안 미리 보기와, 놓으면 기록하는 흐름을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorAdjustTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() {
        runBlocking { ProjectLibrary.get(context).awaitSaves() }
        File(context.filesDir, "projects").deleteRecursively()
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && predicate(it) } } as EditorState.Ready
    }

    /** 축소본 가운데 픽셀의 빨강 값 */
    private fun centerRed(ready: EditorState.Ready): Int {
        val pixels = ready.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return Color.red(pixels.getPixel(pixels.width / 2, pixels.height / 2))
    }

    private fun openGrayCanvas(): EditorState.Ready {
        onMain { viewModel.newCanvas(400, 300, CanvasFill.Solid(Color.rgb(128, 128, 128))) }
        return awaitReady { !it.busy && it.documentSize == PixelSize(400, 300) }
    }

    @Test
    fun topLayer_isChosen_andLayersAreListed() {
        val ready = openGrayCanvas()

        assertEquals(1, ready.layers.size)
        assertEquals(ready.layers.last().id, ready.activeLayer)
        assertEquals(Adjustments.NONE, ready.active?.adjustments)
    }

    @Test
    fun dragging_showsThePreview_andReleasingRecordsOneStep() {
        val opened = openGrayCanvas()
        val before = centerRed(opened)

        // 끄는 동안: 그림은 밝아지지만 기록하지 않는다
        onMain { viewModel.previewAdjustments(Adjustments(exposure = 1f)) }
        val previewing = awaitReady { centerRed(it) > before + 20 }
        assertEquals(opened.revision, previewing.revision)
        assertFalse(previewing.canUndo)
        assertEquals(Adjustments.NONE, previewing.active?.adjustments)

        // 놓으면 실행 취소 한 단계로 기록한다
        onMain { viewModel.commitAdjustments(Adjustments(exposure = 1f)) }
        val committed = awaitReady { !it.busy && it.revision > opened.revision }
        assertTrue(committed.canUndo)
        assertEquals(Adjustments(exposure = 1f), committed.active?.adjustments)
        assertTrue(centerRed(committed) > before + 20)

        // 실행 취소하면 보정 전으로 돌아간다
        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.revision > committed.revision }
        assertEquals(Adjustments.NONE, undone.active?.adjustments)
        assertTrue(kotlin.math.abs(centerRed(undone) - before) <= 1)
    }

    @Test
    fun releasingTheSameValues_recordsNothing() {
        val opened = openGrayCanvas()

        onMain { viewModel.previewAdjustments(Adjustments(contrast = 0.4f)) }
        onMain { viewModel.commitAdjustments(Adjustments.NONE) }

        val after = awaitReady { !it.busy }
        Thread.sleep(500)
        val settled = viewModel.state.value as EditorState.Ready
        assertEquals(opened.revision, settled.revision)
        assertFalse(settled.canUndo)
        assertEquals(Adjustments.NONE, after.active?.adjustments)
    }
}
