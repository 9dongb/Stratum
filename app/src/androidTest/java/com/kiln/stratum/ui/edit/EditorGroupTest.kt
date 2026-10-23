package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.R
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 레이어 그룹을 쓰는 흐름(ViewModel, 결정 기록 '피부 보정'의 1h): 묶기·풀기, 새 레이어는 고른 그룹 안, 덩어리째 복제·지우기,
 * 그룹에 들어가고 나오는 옮기기, 그룹에는 픽셀 도구를 쓰지 않음, 숨긴 그룹 안에는 그리지 않음.
 */
@RunWith(AndroidJUnit4::class)
class EditorGroupTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && predicate(it) } } as EditorState.Ready
    }

    private fun awaitMessage(): EditorMessage = runBlocking { withTimeout(10_000) { viewModel.messages.first() } }

    private fun ready() = viewModel.state.value as EditorState.Ready

    /** 레이어(번호, 감싼 그룹)를 아래부터 */
    private fun tree(ready: EditorState.Ready) = ready.layers.map { it.id.value to it.parent?.value }

    /** 주황 캔버스(레이어 0)를 열고 레이어 0을 그룹 1로 묶는다 */
    private fun openGrouped(): EditorState.Ready {
        onMain { viewModel.newCanvas(400, 300, CanvasFill.Solid(Color.rgb(220, 120, 40))) }
        val opened = awaitReady { !it.busy && it.documentSize == PixelSize(400, 300) }
        onMain { viewModel.groupLayer() }
        val grouped = awaitReady { !it.busy && it.revision > opened.revision }
        assertEquals(listOf(0L to 1L, 1L to null), tree(grouped))
        assertTrue(grouped.layers[1].group)
        assertEquals(context.getString(R.string.layer_group_name, 1), grouped.layers[1].name)
        // 묶으면 새 그룹을 고른다(포토샵과 같다)
        assertEquals(LayerId(1), grouped.activeLayer)
        return grouped
    }

    @Test
    fun newLayers_goInsideTheChosenGroupAtTheTop_andNextToTheChosenLayer() {
        val grouped = openGrouped()

        onMain { viewModel.addLayer() }
        val first = awaitReady { !it.busy && it.revision > grouped.revision }
        assertEquals(listOf(0L to 1L, 2L to 1L, 1L to null), tree(first))
        assertEquals(LayerId(2), first.activeLayer)
        // 그룹 안의 레이어를 고른 채 더하면 그 바로 위, 같은 그룹 안이다(조정 레이어도)
        onMain { viewModel.addAdjustmentLayer() }
        val second = awaitReady { !it.busy && it.revision > first.revision }
        assertEquals(listOf(0L to 1L, 2L to 1L, 3L to 1L, 1L to null), tree(second))
    }

    @Test
    fun pixelTools_refuseAGroup_andAHiddenGroupKeepsItsLayersFromBeingPainted() {
        val grouped = openGrouped()

        onMain { viewModel.beginStroke(StrokePoint(100f, 100f), erase = false) }
        assertEquals(EditorMessage.GroupNoPixels, awaitMessage())
        onMain { viewModel.orientLayer(Orientation.ROTATE_CLOCKWISE) }
        assertEquals(EditorMessage.GroupNoPixels, awaitMessage())
        // 그룹에는 마스크를 두지 않는다
        onMain { viewModel.addLayerMask() }
        Thread.sleep(300)
        assertEquals(grouped.revision, ready().revision)

        onMain { viewModel.setLayerVisible(LayerId(1), false) }
        val hidden = awaitReady { !it.busy && it.revision > grouped.revision }
        onMain { viewModel.selectLayer(LayerId(0)) }
        awaitReady { it.activeLayer == LayerId(0) }
        onMain { viewModel.beginStroke(StrokePoint(100f, 100f), erase = false) }
        assertEquals(EditorMessage.HiddenLayer, awaitMessage())
        assertEquals(hidden.revision, ready().revision)
    }

    @Test
    fun ungroup_bringsTheLayersOut_andPicksTheTopOne() {
        val grouped = openGrouped()

        onMain { viewModel.ungroupLayer() }
        val ungrouped = awaitReady { !it.busy && it.revision > grouped.revision }

        assertEquals(listOf(0L to null), tree(ungrouped))
        assertEquals(LayerId(0), ungrouped.activeLayer)
    }

    @Test
    fun duplicateAndDelete_workOnTheWholeGroup() {
        val grouped = openGrouped()

        onMain { viewModel.duplicateLayer() }
        val copied = awaitReady { !it.busy && it.revision > grouped.revision }
        // 자손과 함께 바로 위에 복제하고, 복제한 그룹 줄을 고른다
        assertEquals(listOf(0L to 1L, 1L to null, 2L to 3L, 3L to null), tree(copied))
        assertEquals(LayerId(3), copied.activeLayer)

        onMain { viewModel.deleteLayer() }
        val deleted = awaitReady { !it.busy && it.revision > copied.revision }
        assertEquals(listOf(0L to 1L, 1L to null), tree(deleted))
        assertEquals(LayerId(1), deleted.activeLayer)
        // 남은 그룹이 모든 레이어를 감싸면 지우지 않는다
        onMain { viewModel.deleteLayer() }
        Thread.sleep(300)
        assertEquals(deleted.revision, ready().revision)
    }

    @Test
    fun moveLayer_goesIntoAGroupAtOnce_andRefusesAPlaceThatBreaksTheGroups() {
        val grouped = openGrouped()
        // 아래부터 [0] 그룹 3, 2를 만든다(그룹을 고른 채 더하면 안으로 들어가므로 풀고 더한 뒤 다시 묶는다)
        onMain { viewModel.ungroupLayer() }
        val flat = awaitReady { !it.busy && it.revision > grouped.revision }
        onMain { viewModel.addLayer() }
        val two = awaitReady { !it.busy && it.revision > flat.revision }
        onMain {
            viewModel.selectLayer(LayerId(0))
            viewModel.groupLayer()
        }
        val start = awaitReady { !it.busy && it.revision > two.revision }
        assertEquals(listOf(0L to 3L, 3L to null, 2L to null), tree(start))

        onMain {
            viewModel.moveLayer(LayerId(2), 1, LayerId(3))
            // 기록을 기다리지 않고 목록이 바로 새 순서·그룹이다
            assertEquals(listOf(0L to 3L, 2L to 3L, 3L to null), tree(ready()))
        }
        val moved = awaitReady { !it.busy && it.revision > start.revision }
        assertEquals(listOf(0L to 3L, 2L to 3L, 3L to null), tree(moved))

        // 그룹을 자기 안으로 옮기려 하면 아무것도 하지 않는다
        onMain { viewModel.moveLayer(LayerId(3), 2, LayerId(3)) }
        Thread.sleep(300)
        assertEquals(moved.revision, ready().revision)
        assertNull(ready().layers.last().parent)
    }
}
