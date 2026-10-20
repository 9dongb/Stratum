package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.FakeInpaintModel
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.SetLayerVisible
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.inpaint.InpaintUnavailableException
import com.kiln.stratum.engine.platform.InpaintRegion
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AI 제거(리터칭의 넷째 하위 도구)의 편집 흐름을 가짜 채우기 모델로 에뮬레이터에서 확인한다(결정 기록 'AI 제거·AI 선택'). */
@RunWith(AndroidJUnit4::class)
class EditorRemoveTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    private fun awaitMessage(): EditorMessage = runBlocking { withTimeout(30_000) { viewModel.messages.first() } }

    /** 흰 캔버스 1000×800(화면에 맞추면 원본 크기 축소본)에 검은 점([dots]마다 지름 30)을 찍는다. 점마다 실행 취소 한 단계다. */
    private fun openWithDots(vararg dots: StrokePoint = arrayOf(StrokePoint(200f, 200f))): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        var state = awaitReady { it.documentSize == SIZE }
        for (dot in dots) {
            val previous = state.revision
            onMain { viewModel.edit(PaintStroke(LayerId(0), listOf(dot), BrushSettings(size = 30f, hardness = 1f, color = Color.BLACK), false)) }
            state = awaitReady { it.revision > previous }
        }
        return state
    }

    private fun shown(state: EditorState.Ready): Bitmap = state.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)

    private fun dark(bitmap: Bitmap, x: Int, y: Int) = Color.red(bitmap.getPixel(x, y)) < 100

    /** AI 제거로 [points]를 칠하고 손을 뗀다 */
    private fun remove(vararg points: StrokePoint) = onMain {
        viewModel.retouchActions.beginRemove(points.first().x, points.first().y)
        for (point in points.drop(1)) viewModel.retouchActions.extend(point.x, point.y)
        viewModel.retouchActions.end()
    }

    @Test
    fun remove_fillsThePaintedDot_isOneUndoStep_andKeepsTheRetouchSettings() {
        val model = FakeInpaintModel().also { viewModel.inpaintModel = it }
        val before = openWithDots()
        onMain { viewModel.setRetouch(BrushSettings(size = 50f, hardness = 0.2f)) }

        remove(StrokePoint(200f, 200f))
        val removed = awaitReady { it.revision > before.revision }

        assertFalse(dark(shown(removed), 200, 200))
        assertEquals(1, model.calls)
        // 칠한 곳은 단단한 원으로 보지만, 리터칭 설정(다른 하위 도구가 함께 씀)은 그대로다
        assertEquals(BrushSettings(size = 50f, hardness = 0.2f), viewModel.retouch.value)
        // 실행 취소 한 번이면 점이 돌아온다
        onMain { viewModel.undo() }
        assertTrue(dark(shown(awaitReady { it.revision > removed.revision }), 200, 200))
    }

    @Test
    fun removeOnAHiddenLayer_tellsSo_andDoesNothing() {
        val model = FakeInpaintModel().also { viewModel.inpaintModel = it }
        val before = openWithDots()
        onMain { viewModel.edit(SetLayerVisible(LayerId(0), false)) }
        val hidden = awaitReady { it.revision > before.revision }

        remove(StrokePoint(200f, 200f))

        assertEquals(EditorMessage.HiddenLayer, awaitMessage())
        assertEquals(0, model.calls)
        assertEquals(hidden.revision, awaitReady { true }.revision)
    }

    @Test
    fun anUnavailableModel_tellsTheScreen_andKeepsTheDocument() {
        val model = FakeInpaintModel(failure = InpaintUnavailableException("No device")).also { viewModel.inpaintModel = it }
        val before = openWithDots()

        remove(StrokePoint(200f, 200f))

        assertEquals(EditorMessage.RemoveUnavailable, awaitMessage())
        val after = awaitReady { true }
        assertEquals(1, model.calls)
        assertEquals(before.revision, after.revision)
        assertFalse(after.canRedo)
        // 칠하던 반투명 검정도 지우고 기록된 문서를 다시 보인다(점 옆은 흰색)
        val image = shown(after)
        assertTrue(dark(image, 200, 200))
        assertTrue(Color.red(image.getPixel(220, 200)) > 240)
    }

    @Test
    fun withoutMemoryForTheModel_tellsTheScreen_andKeepsTheUndoSteps() {
        val model = FakeInpaintModel(pendingLoadBytes = 400L * 1024 * 1024).also { viewModel.inpaintModel = it }
        val before = openWithDots()
        viewModel.availableMemory = { 100L * 1024 * 1024 }

        remove(StrokePoint(200f, 200f))

        assertEquals(EditorMessage.EditNotEnoughMemory, awaitMessage())
        val after = awaitReady { true }
        assertEquals(0, model.calls)
        assertEquals(before.revision, after.revision)
        // 오래된 단계를 다 버려도 모자라므로 실행 취소 기록(점 찍기)은 버리지 않는다
        assertTrue(after.canUndo)
    }

    @Test
    fun aLittleShortOfMemory_dropsOldUndoSteps_andRemoves() {
        val model = FakeInpaintModel().also { viewModel.inpaintModel = it }
        // 같은 타일에 점을 두 번 찍어, 첫 점의 타일은 오래된 단계에만 남는다(버리면 그만큼 비워진다)
        val before = openWithDots(StrokePoint(200f, 200f), StrokePoint(100f, 100f))
        // 이 획의 예상 메모리보다 조금(타일 반 장) 모자라게 한다. 예상은 문서 크기·칠한 곳에만 기대므로 같은 크기의 빈 문서로 잰다.
        val command = InpaintRegion(LayerId(0), InpaintArea.Painted(listOf(StrokePoint(200f, 200f)), 25f), model)
        val sameSize = Document.create(SIZE.width, SIZE.height, "Background", Raster.EMPTY)
        val total = command.estimateNewBytes(sameSize) + command.estimateWorkBytes(sameSize)
        viewModel.availableMemory = { total - 128L * 1024 }
        onMain { viewModel.setRetouch(BrushSettings(size = 50f)) }

        remove(StrokePoint(200f, 200f))
        val removed = awaitReady { it.revision > before.revision }

        assertFalse(dark(shown(removed), 200, 200))
        assertEquals(1, model.calls)
        // 점 찍기 두 단계는 버렸고, 지우기 한 단계만 되돌릴 수 있다
        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > removed.revision }
        assertTrue(dark(shown(undone), 200, 200))
        assertFalse(undone.canUndo)
    }

    @Test
    fun goingHome_releasesTheModel() {
        val model = FakeInpaintModel().also { viewModel.inpaintModel = it }
        openWithDots()

        onMain { viewModel.retouchActions.prepareRemove() }
        assertEquals(1, model.prepares)
        onMain { viewModel.close() }

        assertEquals(1, model.releases)
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
