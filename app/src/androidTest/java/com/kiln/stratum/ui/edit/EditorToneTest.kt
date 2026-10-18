package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.tone.ToneRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 명암 도구(닷지·번)의 편집 흐름(설정·기록·실행 취소)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorToneTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    /** 회색(128) 캔버스 1000×800(화면에 맞추면 원본 크기 축소본) */
    private fun openGray(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.rgb(128, 128, 128)))
        }
        return awaitReady { it.documentSize == SIZE }
    }

    private fun red(state: EditorState.Ready, x: Int, y: Int): Int = Color.red(state.base.bitmap.copy(Bitmap.Config.ARGB_8888, false).getPixel(x, y))

    private fun stroke(dodge: Boolean) = onMain {
        viewModel.toneActions.begin(500f, 400f, dodge)
        viewModel.toneActions.extend(520f, 400f)
        viewModel.toneActions.end()
    }

    @Test
    fun dodge_brightens_isOneUndoStep_andBurnDarkens() {
        val before = openGray()
        onMain { viewModel.setTone(ToneOptions(BrushSettings(size = 80f, hardness = 1f), ToneRange.MIDTONES, exposure = 1f)) }

        stroke(dodge = true)
        val dodged = awaitReady { it.revision > before.revision }
        assertTrue("${red(dodged, 500, 400)}", red(dodged, 500, 400) > 170)
        assertEquals(128, red(dodged, 500, 300))
        assertTrue(dodged.canUndo)

        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > dodged.revision }
        assertEquals(128, red(undone, 500, 400))

        stroke(dodge = false)
        val burned = awaitReady { it.revision > undone.revision }
        assertTrue("${red(burned, 500, 400)}", red(burned, 500, 400) < 90)
    }

    @Test
    fun settings_areKept_andUsedByTheNextStroke() {
        val before = openGray()
        val chosen = ToneOptions(BrushSettings(size = 80f, hardness = 1f), ToneRange.HIGHLIGHTS, exposure = 0.01f)
        onMain { viewModel.setTone(chosen) }
        assertEquals(chosen, viewModel.tone.value)

        stroke(dodge = true)

        // 노출 1%면 거의 바뀌지 않는다
        val dodged = awaitReady { it.revision > before.revision }
        assertTrue(red(dodged, 500, 400) - 128 <= 2)
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
