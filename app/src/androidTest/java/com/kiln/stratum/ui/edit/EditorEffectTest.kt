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
import com.kiln.stratum.engine.effect.EffectKind
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

/** 흐림·선명·스머지·모자이크 도구의 편집 흐름(기록·실행 취소·설정·모자이크 첫 셀 크기·숨긴 레이어)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorEffectTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    /** 흰 캔버스 1000×800(화면에 맞추면 원본 크기 축소본)에 검은 점(가운데 (500, 400), 지름 200, 단단함 1)을 찍는다 */
    private fun openWithDot(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        val opened = awaitReady { it.documentSize == SIZE }
        onMain { viewModel.edit(PaintStroke(LayerId(0), listOf(StrokePoint(500f, 400f)), BrushSettings(size = 200f, hardness = 1f, color = Color.BLACK), false)) }
        return awaitReady { it.revision > opened.revision }
    }

    private fun shown(state: EditorState.Ready): Bitmap = state.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)

    private fun stroke(kind: EffectKind, vararg points: StrokePoint) = onMain {
        viewModel.effectActions.begin(points.first().x, points.first().y, kind)
        for (point in points.drop(1)) viewModel.effectActions.extend(point.x, point.y)
        viewModel.effectActions.end()
    }

    @Test
    fun blur_softensTheDotEdge_andIsOneUndoStep() {
        val before = openWithDot()
        onMain { viewModel.setBlur(BlurOptions(BrushSettings(size = 300f, hardness = 1f), strength = 1f)) }

        // 점의 오른쪽 끝(x = 600)을 흐린다
        stroke(EffectKind.BLUR, StrokePoint(600f, 380f), StrokePoint(600f, 420f))
        val blurred = awaitReady { it.revision > before.revision }

        val edge = Color.red(shown(blurred).getPixel(603, 400))
        assertTrue("$edge", edge in 60..220)
        assertTrue(blurred.canUndo)
        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > blurred.revision }
        assertEquals(Color.WHITE, shown(undone).getPixel(603, 400))
    }

    @Test
    fun mosaic_coversTheDotWithCellsOfTheChosenSize() {
        val before = openWithDot()
        onMain { viewModel.setMosaic(MosaicOptions(BrushSettings(size = 400f, hardness = 1f), cell = 50)) }

        stroke(EffectKind.MOSAIC, StrokePoint(500f, 400f))
        val covered = shown(awaitReady { it.revision > before.revision })

        // 칸 (550..599, 400..449)은 점 끝에 걸쳐 한 가지 회색이 된다
        val cell = covered.getPixel(551, 401)
        assertEquals(cell, covered.getPixel(598, 448))
        assertTrue("${Color.red(cell)}", Color.red(cell) in 1..254)
        // 칸 경계가 바뀐다
        assertTrue(covered.getPixel(601, 401) != cell)
    }

    @Test
    fun sharpen_usesTheBlurToolSettings() {
        val before = openWithDot()
        onMain { viewModel.setBlur(BlurOptions(BrushSettings(size = 100f, hardness = 1f), strength = 1f)) }

        stroke(EffectKind.SHARPEN, StrokePoint(600f, 400f))
        val sharpened = awaitReady { it.revision > before.revision }

        // 흰 바탕과 검은 점 사이는 이미 끝값이라 선명해도 그대로 끝값이다(넘치지 않는다)
        assertEquals(Color.WHITE, shown(sharpened).getPixel(640, 400))
        assertTrue(sharpened.canUndo)
    }

    @Test
    fun smudge_dragsTheDotOut_andIsOneUndoStep() {
        val before = openWithDot()
        onMain { viewModel.setBlur(BlurOptions(BrushSettings(size = 80f, hardness = 1f), strength = 0.9f)) }

        // 점 안(520)에서 오른쪽 흰 곳으로 끈다
        onMain {
            viewModel.effectActions.beginSmudge(520f, 400f)
            for (x in 530..760 step 10) viewModel.effectActions.extend(x.toFloat(), 400f)
            viewModel.effectActions.end()
        }
        val smudged = awaitReady { it.revision > before.revision }

        assertTrue("${Color.red(shown(smudged).getPixel(650, 400))}", Color.red(shown(smudged).getPixel(650, 400)) < 200)
        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > smudged.revision }
        assertEquals(Color.WHITE, shown(undone).getPixel(650, 400))
    }

    @Test
    fun mosaicCell_startsAtTwoPercentOfTheShortSide_untilItIsChanged() {
        openWithDot()
        // 1000×800의 짧은 변 800의 2%
        assertEquals(16, viewModel.mosaic.value.cell)

        onMain { viewModel.setMosaic(viewModel.mosaic.value.copy(cell = 40)) }
        onMain { viewModel.newCanvas(3000, 2000, CanvasFill.Solid(Color.WHITE)) }
        awaitReady { it.documentSize == PixelSize(3000, 2000) }

        // 사용자가 바꾼 뒤로는 다른 문서를 열어도 그대로다
        assertEquals(40, viewModel.mosaic.value.cell)
    }

    @Test
    fun aHiddenLayer_isNotChanged() {
        val before = openWithDot()
        onMain { viewModel.edit(SetLayerVisible(LayerId(0), false)) }
        val hidden = awaitReady { it.revision > before.revision }

        stroke(EffectKind.MOSAIC, StrokePoint(500f, 400f))

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val after = viewModel.state.value as EditorState.Ready
        assertEquals(hidden.revision, after.revision)
        assertFalse(after.canRedo)
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
