package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.FakeSegmentModel
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.platform.ObjectSegmenter
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.segment.SegmentUnavailableException
import com.kiln.stratum.engine.selection.SelectionMode
import kotlinx.coroutines.delay
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

/** 선택 도구 'AI 선택'의 편집 흐름을 가짜 분할 모델로 에뮬레이터에서 확인한다(결정 기록 'AI 제거·AI 선택'). */
@RunWith(AndroidJUnit4::class)
class EditorAiSelectTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    private fun awaitMessage(): EditorMessage = runBlocking { withTimeout(30_000) { viewModel.messages.first() } }

    /** 가짜 분할 모델을 끼운다 */
    private fun useModel(model: FakeSegmentModel): FakeSegmentModel = model.also { viewModel.objectSegmenter = ObjectSegmenter(it) }

    /** 흰 캔버스 1000×800(화면에 맞추면 원본 크기 축소본)에 검은 점([dots]마다 지름 60)을 찍는다. 점마다 실행 취소 한 단계다. */
    private fun openWithDots(vararg dots: StrokePoint = arrayOf(StrokePoint(200f, 200f))): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        var state = awaitReady { it.documentSize == SIZE }
        for (dot in dots) {
            val previous = state.revision
            onMain { viewModel.edit(PaintStroke(LayerId(0), listOf(dot), BrushSettings(size = 60f, hardness = 1f, color = Color.BLACK), false)) }
            state = awaitReady { it.revision > previous }
        }
        return state
    }

    /** 윤곽선의 가로 범위(문서 좌표) */
    private fun xRange(state: EditorState.Ready): ClosedFloatingPointRange<Float> {
        val xs = checkNotNull(state.selection).lines.flatMap { line -> line.filterIndexed { i, _ -> i % 2 == 0 } }
        return xs.min()..xs.max()
    }

    @Test
    fun tappingADot_selectsItsShape_asOneUndoStep() {
        useModel(FakeSegmentModel())
        openWithDots()

        onMain { viewModel.selectObject(200f, 200f) }
        val selected = awaitReady { it.selection != null }

        val range = xRange(selected)
        assertEquals(170f, range.start, 8f)
        assertEquals(230f, range.endInclusive, 8f)
        onMain { viewModel.undo() }
        awaitReady { it.selection == null }
    }

    @Test
    fun theModeComesFromTheSelectionOptions() {
        useModel(FakeSegmentModel())
        openWithDots(StrokePoint(200f, 200f), StrokePoint(700f, 500f))
        onMain { viewModel.selectObject(200f, 200f) }
        val first = awaitReady { it.selection != null }

        onMain {
            viewModel.setSelectionOptions(SelectionOptions(mode = SelectionMode.ADD))
            viewModel.selectObject(700f, 500f)
        }
        val both = awaitReady { it.revision > first.revision }

        val range = xRange(both)
        assertEquals(170f, range.start, 8f)
        assertEquals(730f, range.endInclusive, 8f)
    }

    @Test
    fun choosingAiSelect_readsThePictureAhead_andTheTapUsesIt() {
        val model = useModel(FakeSegmentModel())
        openWithDots()

        onMain { viewModel.prepareObjectSelection() }
        runBlocking { withTimeout(30_000) { while (model.encodes < 1) delay(20) } }
        // 같은 그림이면 다시 읽지 않는다
        onMain { viewModel.prepareObjectSelection() }
        onMain { viewModel.selectObject(200f, 200f) }
        awaitReady { it.selection != null }

        assertEquals(1, model.encodes)
        assertEquals(1, model.decodes)
    }

    @Test
    fun withoutMemoryForTheModel_doesNotReadAhead_andTheTapTellsTheScreen() {
        val model = useModel(FakeSegmentModel(loadBytes = 500L * 1024 * 1024))
        val before = openWithDots()
        viewModel.availableMemory = { 100L * 1024 * 1024 }

        onMain { viewModel.prepareObjectSelection() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, model.encodes)

        onMain { viewModel.selectObject(200f, 200f) }
        assertEquals(EditorMessage.EditNotEnoughMemory, awaitMessage())
        val after = awaitReady { true }
        assertEquals(before.revision, after.revision)
        // 오래된 단계를 다 버려도 모자라므로 실행 취소 기록(점 찍기)은 버리지 않는다
        assertTrue(after.canUndo)
    }

    @Test
    fun anUnavailableModel_tellsTheScreen_andKeepsTheDocument() {
        useModel(FakeSegmentModel(failure = SegmentUnavailableException("No device")))
        val before = openWithDots()

        onMain { viewModel.selectObject(200f, 200f) }

        assertEquals(EditorMessage.SelectUnavailable, awaitMessage())
        val after = awaitReady { true }
        assertEquals(before.revision, after.revision)
        assertNull(after.selection)
        assertFalse(after.canRedo)
    }

    @Test
    fun goingHome_releasesTheModel() {
        val model = useModel(FakeSegmentModel())
        openWithDots()
        onMain { viewModel.selectObject(200f, 200f) }
        awaitReady { it.selection != null }

        onMain { viewModel.close() }

        assertEquals(1, model.releases)
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
