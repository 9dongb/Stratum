package com.kiln.stratum.ui.edit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.FakeFaceModel
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.face.FaceSlider
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.PixelSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 다시 고치는 유동화(얼굴 인식 보정 3단계)의 흐름: 적용하면 기록이 남고, 다시 열면 그 기록에서 이어서 고치며, 모두 0이면 원래 픽셀로,
 * 다른 편집이 그 레이어 픽셀을 바꾸면 묻지 않고 굳히고 알린다(사용자 결정 "묻지 않고 굳히기"). 얼굴은 빨간 덩어리를 얼굴로 보는 가짜 모델이다.
 */
@RunWith(AndroidJUnit4::class)
class EditorLiquifyRecordTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)
    private val model = FakeFaceModel().also { viewModel.faceFinder = FaceFinder(it) }

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    private fun awaitLiquify(predicate: (LiquifyState) -> Boolean): LiquifyState = runBlocking {
        withTimeout(30_000) { checkNotNull(viewModel.liquify.first { it != null && predicate(it) }) }
    }

    private fun awaitClosed() = runBlocking { withTimeout(30_000) { viewModel.liquify.first { it == null } } }

    /** 흰 캔버스 1200×900의 (400, 450)에 빨간 덩어리(가로 200, 세로 240) 하나 */
    private fun openWithAFace(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        val ready = awaitReady { it.documentSize == SIZE }
        val dabs = StrokeDabs.place(listOf(StrokePoint(400f, 430f), StrokePoint(400f, 470f)), 1f)
        onMain { viewModel.edit(PaintStroke(LayerId(0), dabs, BrushSettings(size = 200f, hardness = 1f, color = Color.RED), false)) }
        return awaitReady { it.revision > ready.revision }
    }

    /** 유동화를 열어 얼굴 모드로 얼굴을 찾는다 */
    private fun openFaceMode(): LiquifyState {
        onMain { viewModel.startLiquify() }
        awaitLiquify { true }
        onMain { viewModel.liquifyActions.setFaceMode() }
        return awaitLiquify { it.faceMode && !it.findingFaces && it.faces != null }
    }

    /** 얼굴 슬라이더를 [value]로 끌어 놓는다 */
    private fun slide(slider: FaceSlider, value: Float) {
        onMain {
            viewModel.liquifyActions.setFaceValue(slider, value)
            viewModel.liquifyActions.finishFaceValue()
        }
        awaitLiquify { it.faceValues?.get(slider) == value }
    }

    /** 적용하고 기록될 때까지 기다린다 */
    private fun apply(after: Long): EditorState.Ready {
        onMain { viewModel.liquifyActions.apply() }
        awaitClosed()
        return awaitReady { it.revision > after }
    }

    /** [ready]의 전체 축소본에서 문서 (x, y) 자리가 빨간지 */
    private fun red(ready: EditorState.Ready, x: Int, y: Int): Boolean {
        val shown = ready.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val scale = 1 shl ready.base.region.level
        val pixel = shown.getPixel(x / scale, y / scale)
        return Color.red(pixel) > 200 && Color.green(pixel) < 80
    }

    @Test
    fun reopening_continuesFromTheRecord_withoutFindingFacesAgain() {
        val painted = openWithAFace()
        openFaceMode()
        slide(FaceSlider.EYE_SIZE, 0.5f)
        val applied = apply(painted.revision)
        assertTrue(applied.layers[0].liquify)
        val searches = model.detectCalls

        onMain { viewModel.startLiquify() }
        val reopened = awaitLiquify { it.faces != null }

        // 기록의 얼굴·값을 이어받고(다시 찾지 않음), 바꾸기 전에는 적용할 것이 없다
        assertEquals(searches, model.detectCalls)
        assertTrue(reopened.faceMode)
        assertEquals(0, reopened.selectedFace)
        assertEquals(0.5f, reopened.faceValues?.get(FaceSlider.EYE_SIZE))
        assertTrue(reopened.changed)
        assertFalse(reopened.canApply)

        slide(FaceSlider.EYE_SIZE, 0.8f)
        assertTrue(awaitLiquify { true }.canApply)
        val again = apply(applied.revision)
        assertTrue(again.layers[0].liquify)

        // 다시 적용도 실행 취소 한 단계다(앞 적용의 기록으로 돌아감)
        onMain { viewModel.undo() }
        assertTrue(awaitReady { it.revision > again.revision }.layers[0].liquify)
        onMain { viewModel.startLiquify() }
        assertEquals(0.5f, awaitLiquify { it.faces != null }.faceValues?.get(FaceSlider.EYE_SIZE))
    }

    @Test
    fun reopeningWithoutChanges_closesWithoutAStep() {
        val painted = openWithAFace()
        openFaceMode()
        slide(FaceSlider.NOSE_WIDTH, -0.4f)
        val applied = apply(painted.revision)

        onMain { viewModel.startLiquify() }
        awaitLiquify { it.faces != null }
        onMain { viewModel.liquifyActions.apply() }
        awaitClosed()

        assertEquals(applied.revision, (viewModel.state.value as EditorState.Ready).revision)
    }

    @Test
    fun resettingEverything_andApplying_restoresTheOriginalPixels_andDropsTheRecord() {
        val painted = openWithAFace()
        assertFalse(red(painted, 497, 500))
        openFaceMode()
        slide(FaceSlider.FACE_WIDTH, 1f)
        val applied = apply(painted.revision)
        assertTrue(red(applied, 497, 500))

        onMain { viewModel.startLiquify() }
        awaitLiquify { it.faces != null }
        onMain { viewModel.liquifyActions.reset() }
        val reset = awaitLiquify { it.faceValues?.isZero == true }
        assertFalse(reset.changed)
        assertTrue("going back to the original is something to apply", reset.canApply)
        val restored = apply(applied.revision)

        assertFalse(restored.layers[0].liquify)
        assertFalse(red(restored, 497, 500))
    }

    @Test
    fun paintingTheLayer_bakesTheLiquifyWithANotice_andUndoBringsItBack() {
        val painted = openWithAFace()
        openFaceMode()
        slide(FaceSlider.EYE_SIZE, 0.6f)
        val applied = apply(painted.revision)

        val dot = StrokeDabs.place(listOf(StrokePoint(1000f, 100f)), 1f)
        onMain { viewModel.edit(PaintStroke(LayerId(0), dot, BrushSettings(size = 20f, hardness = 1f, color = Color.BLUE), false)) }
        val baked = awaitReady { it.revision > applied.revision }

        assertFalse(baked.layers[0].liquify)
        assertEquals(EditorMessage.LiquifyBaked, runBlocking { withTimeout(10_000) { viewModel.messages.first { it == EditorMessage.LiquifyBaked } } })
        onMain { viewModel.undo() }
        assertTrue(awaitReady { it.revision > baked.revision }.layers[0].liquify)
    }

    @Test
    fun theMenusConvertToPixels_bakesWithoutANotice() {
        val painted = openWithAFace()
        openFaceMode()
        slide(FaceSlider.EYE_SIZE, 0.6f)
        val applied = apply(painted.revision)

        onMain { viewModel.rasterizeActiveLayer() }
        val baked = awaitReady { it.revision > applied.revision }

        assertFalse(baked.layers[0].liquify)
        assertNull(runBlocking { withTimeoutOrNull(1_000) { viewModel.messages.first { it == EditorMessage.LiquifyBaked } } })
    }

    @Test
    fun mergingTheLiquifiedLayerAway_bakesWithANotice_butDeletingItDoesNot() {
        val painted = openWithAFace()
        onMain { viewModel.addLayer() }
        val added = awaitReady { it.revision > painted.revision && it.layers.size == 2 }
        val dot = StrokeDabs.place(listOf(StrokePoint(400f, 430f)), 1f)
        onMain { viewModel.edit(PaintStroke(LayerId(1), dot, BrushSettings(size = 300f, hardness = 1f, color = Color.BLUE), false)) }
        val drawn = awaitReady { it.revision > added.revision }
        onMain { viewModel.startLiquify() }
        awaitLiquify { true }
        pushTheFace()
        val applied = apply(drawn.revision)
        assertTrue(applied.layers.single { it.id == LayerId(1) }.liquify)

        // 아래와 병합: 기록이 있던 위 레이어가 아래 레이어에 합쳐져 없어져도 굳힌 것이다
        onMain { viewModel.mergeLayerDown() }
        val merged = awaitReady { it.revision > applied.revision && it.layers.size == 1 }
        assertEquals(EditorMessage.LiquifyBaked, runBlocking { withTimeout(10_000) { viewModel.messages.first { it == EditorMessage.LiquifyBaked } } })

        // 실행 취소로 기록이 돌아오고, 그 레이어를 지우는 것은 굳힌 것이 아니라 알리지 않는다
        onMain { viewModel.undo() }
        val restored = awaitReady { it.revision > merged.revision && it.layers.size == 2 }
        assertTrue(restored.layers.single { it.id == LayerId(1) }.liquify)
        onMain {
            viewModel.selectLayer(LayerId(1))
            viewModel.deleteLayer()
        }
        val deleted = awaitReady { it.revision > restored.revision && it.layers.size == 1 }
        assertEquals(LayerId(0), deleted.layers.single().id)
        assertNull(runBlocking { withTimeoutOrNull(1_000) { viewModel.messages.first { it == EditorMessage.LiquifyBaked } } })
    }

    /** 얼굴 가운데를 오른쪽으로 민다(늘 같은 획) */
    private fun pushTheFace() {
        onMain {
            viewModel.liquifyActions.setTool(LiquifyTool.PUSH)
            viewModel.liquifyActions.setSize(400f)
            viewModel.liquifyActions.setStrength(1f)
            viewModel.liquifyActions.begin(400f, 430f)
            for (step in 1..10) viewModel.liquifyActions.move(400f + step * 6f, 430f)
            viewModel.liquifyActions.end()
        }
        awaitLiquify { it.changed }
    }

    @Test
    fun facesFoundAfterReopening_areFoundOnTheOriginalPixels() {
        // 손으로만 민 유동화(얼굴 없음)를 다시 열어 얼굴을 찾으면, 원래 픽셀에서 찾아 민 만큼 옮겨 보인다(두 번 옮기지 않음)
        val painted = openWithAFace()
        // 견줄 자리: 기록 없이 같은 획을 민 뒤 찾은 얼굴(원래 픽셀에서 찾음)
        onMain { viewModel.startLiquify() }
        awaitLiquify { true }
        pushTheFace()
        onMain { viewModel.liquifyActions.setFaceMode() }
        val expected = checkNotNull(awaitLiquify { it.faceMode && !it.findingFaces && it.faces != null }.faces)[0]
        onMain {
            viewModel.liquifyActions.setTool(LiquifyTool.PUSH)
            viewModel.liquifyActions.cancel()
        }
        awaitClosed()

        // 얼굴 모드 없이 같은 획을 밀어 적용한다(기록에 얼굴이 없음)
        onMain { viewModel.startLiquify() }
        awaitLiquify { true }
        pushTheFace()
        val applied = apply(painted.revision)
        assertTrue(applied.layers[0].liquify)

        onMain { viewModel.startLiquify() }
        awaitLiquify { true }
        onMain { viewModel.liquifyActions.setFaceMode() }
        val found = checkNotNull(awaitLiquify { it.faceMode && !it.findingFaces && it.faces != null }.faces)[0]

        assertTrue("pushed ${expected.eyeCenterX}", expected.eyeCenterX > 410f)
        assertEquals(expected.eyeCenterX, found.eyeCenterX, 1f)
        assertEquals(expected.eyeCenterY, found.eyeCenterY, 1f)
    }

    private companion object {
        val SIZE = PixelSize(1200, 900)
    }
}
