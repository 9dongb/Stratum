package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.R
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.transform.Orientation
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

/** 조정 레이어를 더하고 고치는 흐름(ViewModel, 결정 기록 '피부 보정'의 1f): 더하기, 조정 패널로 값 바꾸기, 브러시·지우개는 마스크를 칠함, 픽셀 도구 막기. */
@RunWith(AndroidJUnit4::class)
class EditorAdjustmentLayerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && predicate(it) } } as EditorState.Ready
    }

    private fun awaitMessage(): EditorMessage = runBlocking { withTimeout(10_000) { viewModel.messages.first() } }

    private fun pixel(ready: EditorState.Ready, x: Int, y: Int): Int {
        val bitmap = ready.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val level = ready.base.region.level
        return bitmap.getPixel(x shr level, y shr level)
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 2 && abs(Color.green(a) - Color.green(b)) <= 2 && abs(Color.blue(a) - Color.blue(b)) <= 2

    private fun isGray(color: Int): Boolean = abs(Color.red(color) - Color.green(color)) <= 3 && abs(Color.green(color) - Color.blue(color)) <= 3

    private val orange = Color.rgb(220, 120, 40)

    private fun openOrange(): EditorState.Ready {
        onMain { viewModel.newCanvas(400, 300, CanvasFill.Solid(orange)) }
        return awaitReady { !it.busy && it.documentSize == PixelSize(400, 300) }
    }

    /** 주황 캔버스 위에 채도를 다 뺀 조정 레이어 */
    private fun grayAdjustment(): EditorState.Ready {
        val opened = openOrange()
        onMain { viewModel.addAdjustmentLayer() }
        val added = awaitReady { !it.busy && it.layers.size == 2 }
        assertEquals(context.getString(R.string.layer_adjustment_name, 1), added.layers.last().name)
        assertTrue(added.layers.last().adjustment)
        assertEquals(added.layers.last().id, added.activeLayer)
        // 바꾸는 것이 없는 조정 레이어는 아무것도 바꾸지 않는다
        assertEquals(pixel(opened, 100, 100), pixel(added, 100, 100))
        onMain { viewModel.recordAdjustments { it.copy(saturation = -1f) } }
        return awaitReady { !it.busy && it.revision > added.revision && isGray(pixel(it, 100, 100)) }
    }

    @Test
    fun adjustmentLayer_changesEverythingBelow_throughTheAdjustPanelValues() {
        val gray = grayAdjustment()

        assertTrue(isGray(pixel(gray, 300, 250)))
        // 조정 레이어는 브러시·지우개가 마스크를 칠한다(늘 마스크가 칠하는 쪽)
        assertTrue(gray.editingMask)
        assertNull(gray.maskOverlay)
    }

    @Test
    fun eraser_hidesTheAdjustmentThere_andBrushBringsItBack() {
        val gray = grayAdjustment()

        onMain {
            viewModel.setEraser(BrushSettings(size = 60f, hardness = 1f, opacity = 1f))
            viewModel.beginStroke(StrokePoint(100f, 100f), erase = true)
            viewModel.endStroke()
        }
        val hidden = awaitReady { !it.busy && it.revision > gray.revision }
        assertTrue(near(orange, pixel(hidden, 100, 100)))
        assertTrue(isGray(pixel(hidden, 300, 250)))

        onMain {
            viewModel.setBrush(BrushSettings(size = 60f, hardness = 1f, opacity = 1f, color = Color.RED))
            viewModel.beginStroke(StrokePoint(100f, 100f), erase = false)
            viewModel.endStroke()
        }
        val revealed = awaitReady { !it.busy && it.revision > hidden.revision }
        assertTrue(isGray(pixel(revealed, 100, 100)))
    }

    @Test
    fun pixelTools_areRefused_onAnAdjustmentLayer_andItsMaskStays() {
        val gray = grayAdjustment()

        onMain { viewModel.beginTone(StrokePoint(100f, 100f), dodge = true) }
        assertEquals(EditorMessage.AdjustmentNoPixels, awaitMessage())
        // 레이어 돌리기(이동 도구)도 돌릴 픽셀이 없다
        onMain { viewModel.orientLayer(Orientation.ROTATE_CLOCKWISE) }
        assertEquals(EditorMessage.AdjustmentNoPixels, awaitMessage())
        // 조정 레이어는 마스크를 지우지 않는다(늘 마스크를 칠함)
        onMain { viewModel.deleteLayerMask() }
        Thread.sleep(300)
        val after = viewModel.state.value as EditorState.Ready
        assertEquals(gray.revision, after.revision)
        assertTrue(checkNotNull(after.active).hasMask)
    }

    @Test
    fun maskThumbnail_togglesTheRedOverlay_andTheBrushKeepsPaintingTheMask() {
        // 조정 레이어는 늘 마스크를 칠해, 마스크 작은 그림은 빨간 막만 켜고 끈다(결정 기록 '피부 보정'의 1i)
        val gray = grayAdjustment()
        val adjustment = checkNotNull(gray.activeLayer)

        onMain { viewModel.editLayerMask(adjustment) }
        awaitReady { it.maskOverlay != null }
        onMain { viewModel.editLayerMask(adjustment) }
        val off = awaitReady { it.maskOverlay == null }

        assertTrue(off.editingMask)
    }

    @Test
    fun mergingTheAdjustmentDown_bakesItIntoThePixelsBelow() {
        val gray = grayAdjustment()

        onMain { viewModel.mergeLayerDown() }
        val merged = awaitReady { !it.busy && it.revision > gray.revision }

        assertEquals(1, merged.layers.size)
        assertFalse(merged.layers.single().adjustment)
        assertTrue(isGray(pixel(merged, 100, 100)))
    }

    @Test
    fun maskThumbnail_showsTheRedOverlay_andAnotherLayerPaintsPixels() {
        val gray = grayAdjustment()
        val adjustment = checkNotNull(gray.activeLayer)

        onMain { viewModel.editLayerMask(adjustment) }
        awaitReady { it.maskOverlay != null }

        onMain { viewModel.selectLayer(gray.layers.first().id) }
        val bottom = awaitReady { it.activeLayer == gray.layers.first().id }
        assertFalse(bottom.editingMask)
        assertNull(bottom.maskOverlay)
        // 다시 조정 레이어를 고르면 늘 마스크를 칠한다(빨간 막은 마스크 작은 그림을 누를 때만)
        onMain { viewModel.selectLayer(adjustment) }
        val back = awaitReady { it.activeLayer == adjustment }
        assertTrue(back.editingMask)
        assertNull(back.maskOverlay)
        assertEquals(Adjustments(saturation = -1f), back.active?.adjustments)
    }
}
