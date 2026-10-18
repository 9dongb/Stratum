package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
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
            viewModel.moveLayer(top, 0)
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
