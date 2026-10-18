package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.project.ProjectLibrary
import java.io.File
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

/** 리터칭 도구의 편집 흐름(가져올 곳·간격·기록·실행 취소·스팟 복구)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorRetouchTest {

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

    /** 흰 캔버스 1000×800(화면에 맞추면 원본 크기 축소본)에 검은 점(가운데 (200, 200), 지름 30)을 찍는다 */
    private fun openWithDot(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        val opened = awaitReady { it.documentSize == SIZE }
        onMain { viewModel.edit(PaintStroke(LayerId(0), listOf(StrokePoint(200f, 200f)), BrushSettings(size = 30f, hardness = 1f, color = Color.BLACK), false)) }
        return awaitReady { it.revision > opened.revision }
    }

    private fun shown(state: EditorState.Ready): Bitmap = state.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)

    private fun dark(bitmap: Bitmap, x: Int, y: Int) = Color.red(bitmap.getPixel(x, y)) < 100

    private fun stroke(mode: RetouchMode, vararg points: StrokePoint) = onMain {
        viewModel.retouchActions.begin(points.first().x, points.first().y, mode)
        for (point in points.drop(1)) viewModel.retouchActions.extend(point.x, point.y)
        viewModel.retouchActions.end()
    }

    @Test
    fun clone_takesFromTheSource_isOneUndoStep_andKeepsTheSpacing() {
        val before = openWithDot()

        onMain { viewModel.retouchActions.setSource(200f, 200f) }
        stroke(RetouchMode.CLONE, StrokePoint(600f, 400f))
        val cloned = awaitReady { it.revision > before.revision }

        assertTrue(dark(shown(cloned), 600, 400))
        assertTrue(cloned.canUndo)
        // 첫 획이 간격(칠한 곳 - 가져올 곳)을 정했고, 다음 획도 같은 간격으로 가져온다
        assertEquals(SourceOffset(400, 200), viewModel.cloneSource.value?.offset)
        stroke(RetouchMode.CLONE, StrokePoint(650f, 400f))
        val again = awaitReady { it.revision > cloned.revision }
        assertFalse(dark(shown(again), 650, 400))
        assertEquals(SourceOffset(400, 200), viewModel.cloneSource.value?.offset)

        // 실행 취소 두 번이면 처음으로 돌아간다
        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > again.revision }
        onMain { viewModel.undo() }
        assertFalse(dark(shown(awaitReady { it.revision > undone.revision }), 600, 400))
    }

    @Test
    fun healWithoutASource_isIgnored() {
        val before = openWithDot()

        stroke(RetouchMode.HEAL, StrokePoint(600f, 400f))

        // 기록하지 않는다(그릴 것이 없다)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val after = viewModel.state.value as EditorState.Ready
        assertEquals(before.revision, after.revision)
        assertFalse(after.canRedo)
    }

    @Test
    fun spotHeal_removesTheDot() {
        val before = openWithDot()
        onMain { viewModel.setRetouch(BrushSettings(size = 50f, hardness = 0.8f)) }

        stroke(RetouchMode.SPOT_HEAL, StrokePoint(200f, 200f))

        val healed = awaitReady { it.revision > before.revision }
        assertFalse(dark(shown(healed), 200, 200))
        assertTrue(healed.canUndo)
    }

    @Test
    fun theSourceIsForgotten_whenTheDocumentChangesSize() {
        openWithDot()
        onMain { viewModel.retouchActions.setSource(200f, 200f) }
        assertEquals(CloneSource(200f, 200f), viewModel.cloneSource.value)

        // 문서 밖은 가져올 곳이 되지 않는다
        onMain { viewModel.retouchActions.setSource(-5f, 200f) }
        assertEquals(CloneSource(200f, 200f), viewModel.cloneSource.value)

        val rotated = runBlocking {
            onMain { viewModel.edit(RotateImage(clockwise = true)) }
            awaitReady { it.documentSize == PixelSize(SIZE.height, SIZE.width) }
        }
        assertEquals(PixelSize(800, 1000), rotated.documentSize)
        assertNull(viewModel.cloneSource.value)
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
