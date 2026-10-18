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
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.text.TextStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 브러시·지우개 획과 글자 넣기를 편집 흐름(ViewModel)에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorBrushTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && predicate(it) } } as EditorState.Ready
    }

    /** 흰 400×300 캔버스. 축소본은 원본 크기(단계 0)다. */
    private fun openWhite(): EditorState.Ready {
        onMain { viewModel.newCanvas(400, 300, CanvasFill.Solid(Color.WHITE)) }
        return awaitReady { !it.busy && it.documentSize == PixelSize(400, 300) }
    }

    /** 축소본에서 문서 ([x], [y])의 픽셀 */
    private fun pixel(ready: EditorState.Ready, x: Int, y: Int): Int {
        val bitmap = ready.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val level = ready.base.region.level
        return bitmap.getPixel(x shr level, y shr level)
    }

    /** 왼쪽에서 오른쪽으로 그은 한 획 */
    private fun stroke(y: Float, erase: Boolean = false) = onMain {
        viewModel.beginStroke(StrokePoint(50f, y), erase)
        for (x in 60..350 step 10) viewModel.extendStroke(StrokePoint(x.toFloat(), y))
        viewModel.endStroke()
    }

    @Test
    fun aStroke_showsWhileDrawing_andIsRecordedAsOneStep() {
        val opened = openWhite()
        onMain {
            viewModel.setBrush(BrushSettings(size = 20f, hardness = 1f, opacity = 1f, color = Color.RED))
            viewModel.beginStroke(StrokePoint(50f, 100f), erase = false)
            for (x in 60..350 step 10) viewModel.extendStroke(StrokePoint(x.toFloat(), 100f))
        }
        // 끄는 동안: 그려 보이지만 기록하지 않았다
        val drawing = awaitReady { pixel(it, 200, 100) == Color.RED }
        assertFalse(drawing.canUndo)

        onMain { viewModel.endStroke() }
        val recorded = awaitReady { !it.busy && it.revision > opened.revision }
        assertTrue(recorded.canUndo)
        assertEquals(Color.RED, pixel(recorded, 200, 100))

        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.revision > recorded.revision }
        assertEquals(Color.WHITE, pixel(undone, 200, 100))
    }

    @Test
    fun strokesDrawnQuickly_oneAfterAnother_areBothRecorded() {
        val opened = openWhite()
        onMain { viewModel.setBrush(BrushSettings(size = 10f, hardness = 1f, color = Color.BLUE)) }

        // 첫 획을 기록하는 동안 둘째 획을 긋는다
        stroke(100f)
        stroke(200f)

        val done = awaitReady { !it.busy && it.revision >= opened.revision + 2 }
        assertEquals(Color.BLUE, pixel(done, 200, 100))
        assertEquals(Color.BLUE, pixel(done, 200, 200))
        // 두 단계로 남는다
        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.revision > done.revision }
        assertEquals(Color.BLUE, pixel(undone, 200, 100))
        assertEquals(Color.WHITE, pixel(undone, 200, 200))
    }

    @Test
    fun cancelling_dropsTheStroke() {
        val opened = openWhite()
        onMain {
            viewModel.setBrush(BrushSettings(size = 20f, hardness = 1f, color = Color.GREEN))
            viewModel.beginStroke(StrokePoint(50f, 100f), erase = false)
            viewModel.extendStroke(StrokePoint(300f, 100f))
        }
        awaitReady { pixel(it, 200, 100) == Color.GREEN }

        onMain { viewModel.cancelStroke() }

        val back = awaitReady { pixel(it, 200, 100) == Color.WHITE }
        assertEquals(opened.revision, back.revision)
        assertFalse(back.canUndo)
    }

    @Test
    fun eraser_makesTheLayerTransparent() {
        val opened = openWhite()
        onMain { viewModel.setEraser(BrushSettings(size = 30f, hardness = 1f)) }

        stroke(150f, erase = true)

        val erased = awaitReady { !it.busy && it.revision > opened.revision }
        assertEquals(0, Color.alpha(pixel(erased, 200, 150)))
        assertEquals(Color.WHITE, pixel(erased, 200, 50))
    }

    @Test
    fun text_isAddedAsANewLayer_andPicked() {
        val opened = openWhite()
        // 글자 크기를 고르기 전에는 문서에 알맞은 크기(짧은 변 300의 6% = 18px)다
        assertEquals(18f, viewModel.textStyle.value.size)
        onMain { viewModel.setTextStyle(TextStyle(size = 40f, color = Color.BLACK)) }

        onMain { viewModel.addText("제목 Title\n두 번째 줄", 20f, 20f) }

        val added = awaitReady { !it.busy && it.layers.size == 2 }
        assertEquals("제목 Title", added.layers.last().name)
        assertEquals(added.layers.last().id, added.activeLayer)
        assertTrue(added.revision > opened.revision)
    }
}
