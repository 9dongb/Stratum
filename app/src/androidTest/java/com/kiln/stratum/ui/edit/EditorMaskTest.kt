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
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 레이어 마스크를 칠하는 흐름(ViewModel, 결정 기록 '피부 보정'의 1d): 더하기, 브러시·지우개로 마스크 칠하기, 빨간 막, 다른 도구 막기, 픽셀로 돌아가기. */
@RunWith(AndroidJUnit4::class)
class EditorMaskTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && predicate(it) } } as EditorState.Ready
    }

    private fun awaitMessage(): EditorMessage = runBlocking { withTimeout(10_000) { viewModel.messages.first() } }

    /** 축소본(또는 같은 크기의 그림)에서 문서 ([x], [y])의 픽셀 */
    private fun pixel(image: PreviewImage, x: Int, y: Int): Int {
        val bitmap = image.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val level = image.region.level
        return bitmap.getPixel(x shr level, y shr level)
    }

    private fun near(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 3 && abs(Color.green(a) - Color.green(b)) <= 3 && abs(Color.blue(a) - Color.blue(b)) <= 3

    private val orange = Color.rgb(200, 120, 40)

    /** 주황 캔버스 위에 파랑으로 꽉 칠한 레이어(고른 레이어) */
    private fun blueOverOrange(): EditorState.Ready {
        onMain { viewModel.newCanvas(400, 300, CanvasFill.Solid(orange)) }
        val opened = awaitReady { !it.busy && it.documentSize == PixelSize(400, 300) }
        onMain { viewModel.addLayer() }
        awaitReady { !it.busy && it.layers.size == 2 }
        onMain { viewModel.setBrush(BrushSettings(size = 1000f, hardness = 1f, opacity = 1f, color = Color.BLUE)) }
        dab(200f, 150f, erase = false)
        return awaitReady { !it.busy && it.revision > opened.revision + 1 && pixel(it.base, 10, 10) == Color.BLUE }
    }

    /** ([x], [y])에 찍은 한 번의 획 */
    private fun dab(x: Float, y: Float, erase: Boolean) = onMain {
        viewModel.beginStroke(StrokePoint(x, y), erase)
        viewModel.endStroke()
    }

    private fun addMask(before: EditorState.Ready): EditorState.Ready {
        onMain { viewModel.addLayerMask() }
        return awaitReady { !it.busy && it.revision > before.revision && it.active?.hasMask == true }
    }

    @Test
    fun addedMask_isPainted_eraserHidesAndBrushReveals() {
        val masked = addMask(blueOverOrange())

        assertTrue(masked.editingMask)
        assertNotNull(masked.active?.mask)
        // 다 보이는 마스크라 빨간 막에 가린 곳이 없다
        assertEquals(0, Color.alpha(pixel(checkNotNull(masked.maskOverlay), 100, 100)))

        // 지우개는 마스크를 가린다: 아래 주황이 보이고, 그 자리 빨간 막이 생긴다
        onMain { viewModel.setEraser(BrushSettings(size = 60f, hardness = 1f, opacity = 1f)) }
        dab(100f, 100f, erase = true)
        val hidden = awaitReady { !it.busy && it.revision > masked.revision }
        assertTrue(near(orange, pixel(hidden.base, 100, 100)))
        assertEquals(Color.BLUE, pixel(hidden.base, 300, 200))
        assertTrue(Color.alpha(pixel(checkNotNull(hidden.maskOverlay), 100, 100)) > 200)
        assertEquals(0, Color.alpha(pixel(checkNotNull(hidden.maskOverlay), 300, 200)))

        // 브러시는 다시 보이게 한다(파랑 픽셀은 그대로 남아 있다)
        onMain { viewModel.setBrush(BrushSettings(size = 60f, hardness = 1f, opacity = 1f, color = Color.GREEN)) }
        dab(100f, 100f, erase = false)
        val revealed = awaitReady { !it.busy && it.revision > hidden.revision }
        assertEquals(Color.BLUE, pixel(revealed.base, 100, 100))
    }

    @Test
    fun otherPixelTools_areRefused_whilePaintingTheMask() {
        addMask(blueOverOrange())

        onMain { viewModel.beginTone(StrokePoint(100f, 100f), dodge = true) }

        assertEquals(EditorMessage.MaskBrushOnly, awaitMessage())
    }

    @Test
    fun thumbnails_switchBetweenPixelsAndMask_andAnotherLayerPaintsPixels() {
        val masked = addMask(blueOverOrange())
        val top = checkNotNull(masked.activeLayer)

        onMain { viewModel.editLayerPixels(top) }
        val pixels = awaitReady { !it.editingMask }
        assertNull(pixels.maskOverlay)

        onMain { viewModel.editLayerMask(top) }
        awaitReady { it.editingMask && it.maskOverlay != null }

        onMain { viewModel.selectLayer(LayerId(0)) }
        val other = awaitReady { !it.editingMask }
        assertEquals(LayerId(0), other.activeLayer)
        // 마스크가 없는 레이어는 마스크를 칠할 수 없다
        onMain { viewModel.editLayerMask(LayerId(0)) }
        assertFalse((viewModel.state.value as EditorState.Ready).editingMask)
    }

    @Test
    fun anotherLayerChosenByAnEdit_paintsItsPixels() {
        // 마스크를 칠하던 레이어를 지우면 고르게 되는 레이어는 마스크가 있어도 픽셀을 칠한다(결정 기록 '피부 보정'의 1i)
        val masked = addMask(blueOverOrange())
        onMain {
            viewModel.selectLayer(LayerId(0))
            viewModel.addLayerMask()
        }
        awaitReady { !it.busy && it.revision > masked.revision && it.activeLayer == LayerId(0) && it.active?.hasMask == true }
        onMain { viewModel.editLayerMask(checkNotNull(masked.activeLayer)) }
        val painting = awaitReady { it.activeLayer == masked.activeLayer && it.editingMask }

        onMain { viewModel.deleteLayer() }
        val deleted = awaitReady { !it.busy && it.revision > painting.revision && it.layers.size == 1 }

        assertEquals(LayerId(0), deleted.activeLayer)
        assertFalse(deleted.editingMask)
    }

    @Test
    fun deletingTheMask_returnsToPixels_andUndoBringsItBack() {
        val masked = addMask(blueOverOrange())

        onMain { viewModel.deleteLayerMask() }
        val deleted = awaitReady { !it.busy && it.revision > masked.revision }
        assertFalse(checkNotNull(deleted.active).hasMask)
        assertFalse(deleted.editingMask)

        onMain { viewModel.undo() }
        val undone = awaitReady { !it.busy && it.revision > deleted.revision }
        assertTrue(checkNotNull(undone.active).hasMask)
    }
}
