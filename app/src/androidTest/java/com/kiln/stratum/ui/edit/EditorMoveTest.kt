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
import com.kiln.stratum.engine.command.SetLayerVisible
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.PixelSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 이동 도구의 편집 흐름(끄는 동안 옮겨 보임·놓으면 실행 취소 한 단계·그만두기·제자리·숨긴 레이어)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorMoveTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    /** 투명한 캔버스 1000×800(화면에 맞추면 원본 크기 축소본)에 검은 점(가운데 (200, 200), 지름 100)을 찍는다 */
    private fun openWithDot(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Transparent)
        }
        val opened = awaitReady { it.documentSize == SIZE }
        onMain { viewModel.edit(PaintStroke(LayerId(0), listOf(StrokePoint(200f, 200f)), BrushSettings(size = 100f, hardness = 1f, color = Color.BLACK), false)) }
        return awaitReady { it.revision > opened.revision }
    }

    private fun shown(state: EditorState.Ready): Bitmap = state.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)

    @Test
    fun dragging_showsTheLayerMoving_thenRecordsOneUndoStep() {
        val before = openWithDot()

        onMain {
            viewModel.moveActions.begin(200f, 200f)
            viewModel.moveActions.move(350f, 300f)
            viewModel.moveActions.move(500f, 400f)
        }
        // 끄는 동안(기록 전)에도 점이 옮겨 보인다
        val dragging = awaitReady { Color.alpha(shown(it).getPixel(500, 400)) == 255 }
        assertEquals(before.revision, dragging.revision)

        onMain { viewModel.moveActions.end() }
        val moved = awaitReady { it.revision > before.revision }
        assertEquals(Color.BLACK, shown(moved).getPixel(500, 400))
        assertEquals(0, Color.alpha(shown(moved).getPixel(200, 200)))
        assertTrue(moved.canUndo)

        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > moved.revision }
        assertEquals(Color.BLACK, shown(undone).getPixel(200, 200))
        assertEquals(0, Color.alpha(shown(undone).getPixel(500, 400)))
    }

    @Test
    fun cancelling_orReleasingInPlace_recordsNothing() {
        val before = openWithDot()

        onMain {
            viewModel.moveActions.begin(200f, 200f)
            viewModel.moveActions.move(500f, 400f)
            viewModel.moveActions.cancel()
        }
        // 버리면 옮겨 보이던 축소본이 기록된 문서로 돌아온다
        val restored = awaitReady { Color.alpha(shown(it).getPixel(200, 200)) == 255 && Color.alpha(shown(it).getPixel(500, 400)) == 0 }
        assertEquals(before.revision, restored.revision)

        onMain {
            viewModel.moveActions.begin(200f, 200f)
            viewModel.moveActions.move(260f, 200f)
            viewModel.moveActions.move(200f, 200f)
            viewModel.moveActions.end()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val after = awaitReady { Color.alpha(shown(it).getPixel(200, 200)) == 255 }
        assertEquals(before.revision, after.revision)
        assertFalse(after.canRedo)
    }

    @Test
    fun aHiddenLayer_isNotMoved() {
        val before = openWithDot()
        onMain { viewModel.edit(SetLayerVisible(LayerId(0), false)) }
        val hidden = awaitReady { it.revision > before.revision }

        onMain {
            viewModel.moveActions.begin(200f, 200f)
            viewModel.moveActions.move(500f, 400f)
            viewModel.moveActions.end()
        }

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val after = viewModel.state.value as EditorState.Ready
        assertEquals(hidden.revision, after.revision)
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
