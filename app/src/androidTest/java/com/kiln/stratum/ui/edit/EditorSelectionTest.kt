package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.project.ProjectLibrary
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 선택 도구의 편집 흐름(고르기·실행 취소·윤곽선·새 레이어로 복사·확대 윤곽선)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorSelectionTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() {
        runBlocking { ProjectLibrary.get(context).awaitSaves() }
        File(context.filesDir, "projects").deleteRecursively()
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitState(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    /** 캔버스 영역 1000×800에 흰 캔버스 2600×520을 연다(화면에 맞추면 1/2 축소본) */
    private fun open(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(CANVAS_AREA.width, CANVAS_AREA.height)
            viewModel.newCanvas(WIDE.width, WIDE.height, CanvasFill.Solid(Color.WHITE))
        }
        return awaitState { it.documentSize == WIDE }
    }

    private fun viewportOf(view: CanvasView): Viewport = view.viewport(CANVAS_AREA, WIDE)

    @Test
    fun selectingAShape_showsItsOutline_isOneUndoStep_andKeepsThePreviewImage() {
        val opened = open()
        assertNull(opened.selection)

        onMain { viewModel.selectShape(SelectionShape.Rectangle(100f, 100f, 900f, 400f)) }
        val selected = awaitState { it.selection != null }

        assertTrue(selected.canUndo)
        val xs = selected.selection!!.lines.flatMap { line -> line.filterIndexed { i, _ -> i % 2 == 0 } }
        assertEquals(100f, xs.min(), 8f)
        assertEquals(900f, xs.max(), 8f)
        // 픽셀은 그대로라 축소본을 다시 그리지 않는다
        assertSame(opened.base.bitmap, selected.base.bitmap)

        onMain { viewModel.undo() }
        awaitState { it.selection == null && !it.canUndo }
    }

    @Test
    fun modes_comeFromTheOptions() {
        open()
        onMain {
            viewModel.selectShape(SelectionShape.Rectangle(0f, 0f, 1000f, 520f))
        }
        awaitState { it.selection != null }

        onMain {
            viewModel.setSelectionOptions(SelectionOptions(mode = SelectionMode.SUBTRACT))
            viewModel.selectShape(SelectionShape.Rectangle(0f, 0f, 1000f, 520f))
        }

        // 다 빼면 선택이 풀린다
        awaitState { it.selection == null && it.canUndo }
    }

    @Test
    fun copyToLayer_addsAndChoosesTheNewLayer_andDropsTheSelection() {
        open()
        onMain { viewModel.selectShape(SelectionShape.Rectangle(100f, 100f, 900f, 400f)) }
        awaitState { it.selection != null }

        onMain { viewModel.copySelectionToLayer(cut = false) }
        val copied = awaitState { it.layers.size == 2 }

        assertEquals(LayerId(1), copied.activeLayer)
        assertNull(copied.selection)
    }

    @Test
    fun magicWand_selectsTheTappedColor() {
        open()

        onMain { viewModel.selectSimilar(1300f, 260f) }
        val selected = awaitState { it.selection != null }

        // 흰 캔버스 전체가 한 색이라 전체가 골라진다
        val xs = selected.selection!!.lines.flatMap { line -> line.filterIndexed { i, _ -> i % 2 == 0 } }
        assertEquals(0f, xs.min(), 8f)
        assertEquals(2600f, xs.max(), 8f)
    }

    @Test
    fun zoomingIn_findsADetailedOutline_andFitDropsIt() {
        open()
        onMain { viewModel.selectShape(SelectionShape.Ellipse(1100f, 100f, 1500f, 420f)) }
        awaitState { it.selection != null }

        onMain { viewModel.onViewport(viewportOf(CanvasView.Free(scale = 4.0, centerX = 1300.0, centerY = 260.0))) }
        val zoomed = awaitState { it.selectionDetail != null && it.detail != null }
        val detail = checkNotNull(zoomed.selectionDetail)
        assertEquals(zoomed.detail!!.region.rect, detail.region)
        assertNotNull(detail.lines.firstOrNull())

        onMain { viewModel.onViewport(viewportOf(CanvasView.Fit)) }
        awaitState { it.selectionDetail == null && it.detail == null }
    }

    private companion object {
        val CANVAS_AREA = PixelSize(1000, 800)
        val WIDE = PixelSize(2600, 520)
    }
}
