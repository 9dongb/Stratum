package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.settings.AppSettings
import com.kiln.stratum.settings.PaintColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 스포이드(결정 기록 '스포이드')가 편집 흐름(ViewModel)에서 그 자리 색을 브러시·글자 색과 최근 색으로 쓰는지 확인한다. 앱의 설정은 건드리지 않는다. */
@RunWith(AndroidJUnit4::class)
class EditorEyedropperTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val settings = AppSettings.inMemory()
    private val viewModel = EditorViewModel(context, SavedStateHandle(), settings)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun openCanvas(fill: CanvasFill) {
        onMain { viewModel.newCanvas(400, 300, fill) }
        runBlocking {
            withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && it.documentSize == PixelSize(400, 300) } }
        }
    }

    private fun awaitEyedropperDone() = runBlocking { withTimeout(10_000) { viewModel.eyedropper.first { it == null } } }

    @Test
    fun picking_showsTheColor_thenMakesItTheForegroundColor() {
        openCanvas(CanvasFill.Solid(ORANGE))

        onMain { viewModel.beginEyedropper(ColorTarget.BRUSH, 100f, 100f) }
        // 고르는 동안 고리에 그 자리 색이 보인다
        val shown = runBlocking { withTimeout(10_000) { viewModel.eyedropper.first { it?.color != null } } }
        assertEquals(EyedropperState(ColorTarget.BRUSH, 100f, 100f, ORANGE), shown)

        onMain { viewModel.endEyedropper() }
        awaitEyedropperDone()

        // 고른 색은 전경색이 되어 브러시·글자가 그 색으로 칠한다(배경색은 그대로)
        assertEquals(PaintColors(ORANGE, PaintColors.WHITE), settings.values.value.paintColors)
        assertEquals(ORANGE, viewModel.brush.value.color)
        assertEquals(ORANGE, viewModel.textStyle.value.color)
    }

    @Test
    fun releasingBeforeTheColorIsRead_stillUsesTheLastPlace_forTheText() {
        openCanvas(CanvasFill.Solid(ORANGE))

        onMain {
            viewModel.beginEyedropper(ColorTarget.TEXT, 10f, 10f)
            viewModel.moveEyedropper(300f, 200f)
            viewModel.endEyedropper()
        }
        awaitEyedropperDone()

        assertEquals(ORANGE, viewModel.textStyle.value.color)
        assertEquals(ORANGE, settings.values.value.paintColors.foreground)
    }

    @Test
    fun transparentPlace_orCancelling_keepsTheColors() {
        openCanvas(CanvasFill.Transparent)
        val brush = viewModel.brush.value.color

        onMain { viewModel.beginEyedropper(ColorTarget.BRUSH, 100f, 100f) }
        onMain { viewModel.endEyedropper() }
        awaitEyedropperDone()

        assertEquals(brush, viewModel.brush.value.color)
        assertEquals(PaintColors.DEFAULT, settings.values.value.paintColors)

        onMain {
            viewModel.beginEyedropper(ColorTarget.BRUSH, 100f, 100f)
            viewModel.cancelEyedropper()
        }
        assertNull(viewModel.eyedropper.value)
        assertEquals(brush, viewModel.brush.value.color)
    }

    private companion object {
        val ORANGE = Color.rgb(230, 120, 30)
    }
}
