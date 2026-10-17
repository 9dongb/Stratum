package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.platform.FlipImage
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.project.ProjectLibrary
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

        // 끄는 동안: 그림은 밝아지지만 기록하지 않는다. 슬라이더는 끄는 값을 보인다.
        onMain { viewModel.previewAdjustments { it.copy(exposure = 1f) } }
        val previewing = awaitReady { centerRed(it) > before + 20 }
        assertEquals(opened.revision, previewing.revision)
        assertFalse(previewing.canUndo)
        assertEquals(Adjustments.NONE, previewing.active?.adjustments)
        assertEquals(Adjustments(exposure = 1f), viewModel.liveAdjustments.value)

        // 놓으면 실행 취소 한 단계로 기록하고, 끄는 값은 지운다
        onMain { viewModel.commitLive() }
        val committed = awaitReady { !it.busy && it.revision > opened.revision }
        assertTrue(committed.canUndo)
        assertEquals(Adjustments(exposure = 1f), committed.active?.adjustments)
        assertTrue(centerRed(committed) > before + 20)
        assertNull(viewModel.liveAdjustments.value)

        // 실행 취소하면 보정 전으로 돌아간다
        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.revision > committed.revision }
        assertEquals(Adjustments.NONE, undone.active?.adjustments)
        assertTrue(abs(centerRed(undone) - before) <= 1)
    }

    @Test
    fun releasingOnTheStartingValue_recordsNothing_andShowsTheRecordedPicture() {
        val opened = openGrayCanvas()
        val before = centerRed(opened)

        // 밝게 끌어 미리 본 뒤, 처음 값으로 되돌려 놓는다
        onMain { viewModel.previewAdjustments { it.copy(exposure = 1f) } }
        awaitReady { centerRed(it) > before + 20 }
        onMain {
            viewModel.previewAdjustments { it.copy(exposure = 0f) }
            viewModel.commitLive()
        }

        // 기록은 없고, 그림은 미리 보기가 아니라 기록된 문서다
        val settled = awaitReady { !it.busy && abs(centerRed(it) - before) <= 1 }
        assertEquals(opened.revision, settled.revision)
        assertFalse(settled.canUndo)
        assertEquals(Adjustments.NONE, settled.active?.adjustments)
        Thread.sleep(300)
        assertNull(viewModel.liveAdjustments.value)
        assertTrue(abs(centerRed(viewModel.state.value as EditorState.Ready) - before) <= 1)
    }

    @Test
    fun releasingWhileAnotherEditRuns_isRecordedAfterIt() {
        val opened = openGrayCanvas()

        // 뒤집기를 처리하는 동안 끌고 놓는다. 놓은 값을 잃지 않고 뒤집기 다음 단계로 기록한다.
        onMain {
            viewModel.edit(FlipImage(horizontal = true))
            viewModel.previewAdjustments { it.copy(contrast = 0.3f) }
            viewModel.commitLive()
        }

        val done = awaitReady { !it.busy && it.active?.adjustments == Adjustments(contrast = 0.3f) }
        assertEquals(opened.revision + 2, done.revision)
        assertNull(viewModel.liveAdjustments.value)
        // 두 단계로 남아, 한 번 되돌리면 보정만 사라진다
        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.revision > done.revision }
        assertEquals(Adjustments.NONE, undone.active?.adjustments)
        assertTrue(undone.canUndo)
    }

    @Test
    fun record_appliesAtOnce_onTopOfTheCurrentValues() {
        openGrayCanvas()
        onMain {
            viewModel.previewAdjustments { it.copy(saturation = 0.5f) }
            viewModel.commitLive()
        }
        val saturated = awaitReady { !it.busy && it.active?.adjustments == Adjustments(saturation = 0.5f) }

        // 초기화 버튼처럼 바로 기록한다(한 단계)
        onMain { viewModel.recordAdjustments { Adjustments.NONE } }

        val reset = awaitReady { !it.busy && it.revision > saturated.revision }
        assertEquals(Adjustments.NONE, reset.active?.adjustments)
        assertTrue(reset.canUndo)
    }
}
