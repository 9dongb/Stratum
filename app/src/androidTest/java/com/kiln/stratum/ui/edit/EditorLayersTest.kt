package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.kiln.stratum.engine.brush.StrokePoint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.R
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.raster.PixelSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 레이어 더하기·지우기·옮기기·불투명도·혼합 모드와 작은 그림을 편집 흐름(ViewModel)에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorLayersTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && predicate(it) } } as EditorState.Ready
    }

    private fun openCanvas(): EditorState.Ready {
        onMain { viewModel.newCanvas(400, 300, CanvasFill.Solid(Color.rgb(200, 120, 40))) }
        return awaitReady { !it.busy && it.documentSize == PixelSize(400, 300) }
    }

    /** 캔버스 축소본의 문서 좌표 ([x], [y]) 픽셀 */
    private fun pixel(ready: EditorState.Ready, x: Int, y: Int): Int {
        val bitmap = ready.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val level = ready.base.region.level
        return bitmap.getPixel(x shr level, y shr level)
    }

    private fun addLayer(after: EditorState.Ready): EditorState.Ready {
        onMain { viewModel.addLayer() }
        return awaitReady { !it.busy && it.layers.size == after.layers.size + 1 }
    }

    @Test
    fun add_putsANamedEmptyLayerAboveTheActiveOne_andPicksIt() {
        val opened = openCanvas()
        // 한 가지 색 캔버스도 작은 그림이 있다
        assertNotNull(opened.layers.single().thumbnail)

        val added = addLayer(opened)

        val top = added.layers.last()
        assertEquals(context.getString(R.string.layer_new_name, 1), top.name)
        assertEquals(top.id, added.activeLayer)
        // 빈 레이어는 작은 그림이 없다
        assertNull(top.thumbnail)
        assertTrue(added.canUndo)
    }

    @Test
    fun delete_picksTheLayerBelow_andKeepsTheLastLayer() {
        val withTwo = addLayer(openCanvas())

        onMain { viewModel.deleteLayer() }
        val deleted = awaitReady { !it.busy && it.layers.size == 1 }
        assertEquals(LayerId(0), deleted.activeLayer)

        // 마지막 한 장은 지우지 않는다
        onMain { viewModel.deleteLayer() }
        Thread.sleep(300)
        assertEquals(1, (viewModel.state.value as EditorState.Ready).layers.size)
        assertEquals(withTwo.revision + 1, (viewModel.state.value as EditorState.Ready).revision)
    }

    @Test
    fun move_showsTheNewOrderAtOnce_andRecordsIt() {
        val withTwo = addLayer(openCanvas())
        val top = withTwo.layers.last().id

        onMain {
            viewModel.moveLayer(top, 0, null)
            // 기록을 기다리지 않고 목록이 바로 새 순서다
            assertEquals(top, (viewModel.state.value as EditorState.Ready).layers.first().id)
        }

        val moved = awaitReady { !it.busy && it.revision > withTwo.revision }
        assertEquals(listOf(top, LayerId(0)), moved.layers.map { it.id })
        assertEquals(top, moved.activeLayer)
    }

    @Test
    fun opacity_previewsWhileDragging_andRecordsOnRelease() {
        val opened = openCanvas()

        onMain { viewModel.previewOpacity(0.4f) }
        assertEquals(0.4f, viewModel.liveOpacity.value)
        Thread.sleep(300)
        assertEquals(opened.revision, (viewModel.state.value as EditorState.Ready).revision)

        onMain { viewModel.commitLive() }
        val committed = awaitReady { !it.busy && it.revision > opened.revision }
        assertEquals(0.4f, committed.active?.opacity)
        assertNull(viewModel.liveOpacity.value)
    }

    @Test
    fun blendMode_isRecorded() {
        val opened = openCanvas()

        onMain { viewModel.setBlendMode(BlendMode.MULTIPLY) }

        val changed = awaitReady { !it.busy && it.revision > opened.revision }
        assertEquals(BlendMode.MULTIPLY, changed.active?.blendMode)
    }

    @Test
    fun duplicate_addsANamedCopyAbove_picksIt_andUndoRemovesIt() {
        val opened = openCanvas()
        val original = opened.layers.single()

        onMain { viewModel.duplicateLayer() }
        val duplicated = awaitReady { !it.busy && it.layers.size == 2 }

        val copy = duplicated.layers.last()
        assertEquals(context.getString(R.string.layer_copy_name, original.name), copy.name)
        assertEquals(copy.id, duplicated.activeLayer)
        assertNotNull(copy.thumbnail)
        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.layers.size == 1 }
        assertEquals(original.id, undone.activeLayer)
    }

    @Test
    fun mergeDown_mergesIntoTheLayerBelow_andPicksIt_butNotFromTheBottom() {
        val withTwo = addLayer(openCanvas())

        onMain { viewModel.mergeLayerDown() }
        val merged = awaitReady { !it.busy && it.layers.size == 1 }
        assertEquals(LayerId(0), merged.activeLayer)

        // 맨 아래 레이어는 아래와 합칠 수 없다
        onMain { viewModel.mergeLayerDown() }
        Thread.sleep(300)
        assertEquals(merged.revision, (viewModel.state.value as EditorState.Ready).revision)
        assertEquals(withTwo.revision + 1, merged.revision)
    }

    @Test
    fun hiding_isOneUndoStep_andTheCanvasDrawsWithoutTheLayer() {
        val opened = openCanvas()
        val layer = opened.layers.single()

        onMain { viewModel.setLayerVisible(layer.id, false) }
        val hidden = awaitReady { !it.busy && it.revision > opened.revision }

        val summary = hidden.layers.single()
        assertEquals(false, summary.visible)
        // 불투명도는 그대로이고, 작은 그림도 남는다(줄에서 흐리게 보인다)
        assertEquals(1f, summary.opacity)
        assertNotNull(summary.thumbnail)
        // 숨긴 레이어는 그리지 않는다(한 장뿐이라 캔버스가 투명하다)
        assertEquals(0, Color.alpha(pixel(hidden, 200, 150)))
        assertTrue(hidden.canUndo)

        onMain { viewModel.undo() }
        val shown = awaitReady { !it.busy && it.layers.single().visible }
        assertEquals(Color.rgb(200, 120, 40), pixel(shown, 200, 150))
    }

    @Test
    fun drawingOnAHiddenLayer_tellsWhy_andDrawsNothing() {
        val opened = openCanvas()
        onMain { viewModel.setLayerVisible(opened.layers.single().id, false) }
        val hidden = awaitReady { !it.busy && it.revision > opened.revision }

        onMain {
            viewModel.beginStroke(StrokePoint(100f, 100f), erase = false)
            viewModel.extendStroke(StrokePoint(300f, 100f))
            viewModel.endStroke()
        }

        assertEquals(EditorMessage.HiddenLayer, runBlocking { withTimeout(10_000) { viewModel.messages.first() } })
        Thread.sleep(300)
        assertEquals(hidden.revision, (viewModel.state.value as EditorState.Ready).revision)
        // 유동화 작업 모드도 열지 않는다(같은 알림은 떠 있는 동안 다시 보내지 않는다)
        onMain { viewModel.startLiquify() }
        Thread.sleep(300)
        assertNull(viewModel.liquify.value)
    }

    @Test
    fun hiddenLayers_stayOutOfMerging() {
        val withThree = addLayer(addLayer(openCanvas()))
        val middle = withThree.layers[1].id

        onMain { viewModel.setLayerVisible(middle, false) }
        val hidden = awaitReady { !it.busy && it.revision > withThree.revision }
        // 맨 위 레이어(고른 것)의 아래가 숨어 있어 아래와 병합은 하지 않는다
        onMain { viewModel.mergeLayerDown() }
        Thread.sleep(300)
        assertEquals(hidden.revision, (viewModel.state.value as EditorState.Ready).revision)
    }

    @Test
    fun selecting_isIgnoredWhileASliderIsHeld() {
        val withTwo = addLayer(openCanvas())
        val top = withTwo.activeLayer

        onMain {
            viewModel.previewOpacity(0.7f)
            viewModel.selectLayer(LayerId(0))
        }
        assertEquals(top, (viewModel.state.value as EditorState.Ready).activeLayer)

        onMain { viewModel.commitLive() }
        val committed = awaitReady { !it.busy && it.revision > withTwo.revision }
        assertEquals(0.7f, committed.layers.last().opacity)
        onMain { viewModel.selectLayer(LayerId(0)) }
        assertEquals(LayerId(0), (viewModel.state.value as EditorState.Ready).activeLayer)
    }
}
