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
import com.kiln.stratum.engine.face.FaceUnavailableException
import com.kiln.stratum.engine.face.FaceValues
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.FaceFinder
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.PixelSize
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

/**
 * 유동화 얼굴 모드(얼굴 인식 보정 1단계)의 흐름: 얼굴 찾기·고르기·알림·모드 기억. 빨간 덩어리를 얼굴로 보는 가짜 모델([FakeFaceModel])로
 * 진짜 찾는 순서와 그림 읽기를 거친다.
 */
@RunWith(AndroidJUnit4::class)
class EditorFaceTest {

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

    /** 얼굴을 다 찾은 얼굴 모드 */
    private fun awaitFaces(): LiquifyState = awaitLiquify { it.faceMode && !it.findingFaces && (it.faces != null || it.faceNotice != null) }

    /** 흰 캔버스 1200×900에 빨간 덩어리(지름 200px 붓으로 위아래 40px 그음: 가로 200, 세로 240)를 [centers]마다 칠한다 */
    private fun openWithRedBlobs(vararg centers: Pair<Float, Float>) {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        var ready = awaitReady { it.documentSize == SIZE }
        for ((x, y) in centers) {
            val dabs = StrokeDabs.place(listOf(StrokePoint(x, y - 20f), StrokePoint(x, y + 20f)), 1f)
            onMain { viewModel.edit(PaintStroke(LayerId(0), dabs, BrushSettings(size = 200f, hardness = 1f, color = Color.RED), false)) }
            val before = ready.revision
            ready = awaitReady { it.revision > before }
        }
    }

    private fun startFaceMode(): LiquifyState {
        onMain { viewModel.startLiquify() }
        awaitLiquify { true }
        onMain { viewModel.liquifyActions.setFaceMode() }
        return awaitFaces()
    }

    @Test
    fun oneFace_isFoundAndSelected_withItsEyesWhereTheFakeModelPutsThem() {
        openWithRedBlobs(400f to 450f)

        val found = startFaceMode()

        val faces = checkNotNull(found.faces)
        assertEquals(1, faces.size)
        assertEquals(0, found.selectedFace)
        assertNull(found.faceNotice)
        // 덩어리 가로 300..500, 세로 330..570: 두 눈은 위쪽 40% 줄(426)의 30%·70%(360, 440)
        assertEquals(360f, faces[0].x(468), 6f)
        assertEquals(440f, faces[0].x(473), 6f)
        assertEquals(426f, faces[0].eyeCenterY, 6f)
        assertTrue(model.detectCalls > 1)
    }

    @Test
    fun severalFaces_areNotPreselected_andATapPicksOne() {
        openWithRedBlobs(300f to 450f, 900f to 450f)

        val found = startFaceMode()

        assertEquals(2, found.faces?.size)
        assertEquals(-1, found.selectedFace)
        assertEquals(FaceNotice.Kind.SEVERAL, found.faceNotice?.kind)
        assertEquals(2, found.faceNotice?.count)

        // 얼굴이 없는 곳은 그대로, 오른쪽 얼굴을 톡하면 그것(왼쪽부터 둘째)을 고른다
        onMain { viewModel.liquifyActions.pickFace(600f, 100f) }
        onMain { viewModel.liquifyActions.pickFace(900f, 450f) }
        assertEquals(1, awaitLiquify { it.selectedFace >= 0 }.selectedFace)
        onMain { viewModel.liquifyActions.pickFace(300f, 450f) }
        assertEquals(0, awaitLiquify { it.selectedFace == 0 }.selectedFace)
    }

    @Test
    fun aSmallFace_isToldOnce_andCanStillBeChanged() {
        // 빨간 덩어리를 작게(붓 70px: 두 눈 사이 약 28px, 격자 칸 4px의 12칸 = 48px보다 짧음)
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        val ready = awaitReady { it.documentSize == SIZE }
        val dabs = StrokeDabs.place(listOf(StrokePoint(600f, 430f), StrokePoint(600f, 470f)), 1f)
        onMain { viewModel.edit(PaintStroke(LayerId(0), dabs, BrushSettings(size = 70f, hardness = 1f, color = Color.RED), false)) }
        awaitReady { it.revision > ready.revision }

        val found = startFaceMode()

        assertEquals(1, found.faces?.size)
        assertEquals(FaceNotice.Kind.SMALL, found.faceNotice?.kind)
        // 같은 얼굴은 다시 알리지 않고, 슬라이더는 그대로 쓸 수 있다
        val serial = checkNotNull(found.faceNotice).serial
        onMain {
            viewModel.liquifyActions.setFaceValue(FaceSlider.EYE_SIZE, 0.5f)
            viewModel.liquifyActions.finishFaceValue()
        }
        val changed = awaitLiquify { it.faceValues?.isZero == false }
        assertEquals(serial, changed.faceNotice?.serial)
        assertTrue(changed.changed)
    }

    @Test
    fun noFace_tellsSo() {
        openWithRedBlobs()

        val found = startFaceMode()

        assertTrue(found.faces?.isEmpty() == true)
        assertEquals(FaceNotice.Kind.NOT_FOUND, found.faceNotice?.kind)
    }

    @Test
    fun anUnavailableModel_tellsSo() {
        model.failure = FaceUnavailableException("test")
        openWithRedBlobs(400f to 450f)

        val found = startFaceMode()

        assertNull(found.faces)
        assertEquals(FaceNotice.Kind.UNAVAILABLE, found.faceNotice?.kind)
    }

    @Test
    fun aBrushTool_leavesFaceMode_andComingBack_reusesTheFaces() {
        openWithRedBlobs(400f to 450f)
        startFaceMode()
        val searches = model.detectCalls

        onMain { viewModel.liquifyActions.setTool(LiquifyTool.BLOAT) }
        val brush = awaitLiquify { !it.faceMode }
        assertEquals(LiquifyTool.BLOAT, brush.tool)
        assertNull(brush.faces)

        onMain { viewModel.liquifyActions.setFaceMode() }
        val again = awaitLiquify { it.faceMode && it.faces != null }
        assertEquals(1, again.faces?.size)
        assertEquals(searches, model.detectCalls)
    }

    @Test
    fun faceMode_isRemembered_andFindsFacesWhenLiquifyOpensAgain() {
        openWithRedBlobs(400f to 450f)
        startFaceMode()
        onMain { viewModel.liquifyActions.cancel() }
        runBlocking { withTimeout(30_000) { viewModel.liquify.first { it == null } } }
        val searches = model.detectCalls

        onMain { viewModel.startLiquify() }
        val reopened = awaitFaces()

        assertTrue(reopened.faceMode)
        assertEquals(1, reopened.faces?.size)
        assertTrue(model.detectCalls > searches)
    }

    @Test
    fun aPushedFace_isShownWhereThePixelsMoved() {
        openWithRedBlobs(400f to 450f)
        val before = checkNotNull(startFaceMode().faces)[0]

        // 얼굴 가운데를 오른쪽으로 민다
        onMain {
            viewModel.liquifyActions.setTool(LiquifyTool.PUSH)
            viewModel.liquifyActions.setSize(400f)
            viewModel.liquifyActions.setStrength(1f)
            viewModel.liquifyActions.begin(400f, 430f)
            for (step in 1..10) viewModel.liquifyActions.move(400f + step * 6f, 430f)
            viewModel.liquifyActions.end()
        }
        awaitLiquify { it.changed }
        onMain { viewModel.liquifyActions.setFaceMode() }
        val after = checkNotNull(awaitLiquify { it.faceMode && it.faces != null }.faces)[0]

        assertTrue("eyes ${before.eyeCenterX} → ${after.eyeCenterX}", after.eyeCenterX > before.eyeCenterX + 10f)
        assertEquals(before.eyeCenterY, after.eyeCenterY, 6f)
        // 옮겨 보이는 자리(누른 자리 + 변위가 원래 얼굴 안)를 톡해도 그 얼굴이다
        onMain { viewModel.liquifyActions.pickFace(after.eyeCenterX, after.eyeCenterY + 40f) }
        assertEquals(0, awaitLiquify { it.faceMode }.selectedFace)
    }

    /** [ready]의 전체 축소본에서 문서 (x, y) 자리가 빨간지 */
    private fun red(ready: EditorState.Ready, x: Int, y: Int): Boolean {
        val shown = ready.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val scale = 1 shl ready.base.region.level
        val pixel = shown.getPixel(x / scale, y / scale)
        return Color.red(pixel) > 200 && Color.green(pixel) < 80
    }

    @Test
    fun aFaceSliderDrag_isOneUndoStep_andUndoRedoBringTheValuesBack() {
        openWithRedBlobs(400f to 450f)
        startFaceMode()

        onMain {
            viewModel.liquifyActions.setFaceValue(FaceSlider.FACE_WIDTH, 0.3f)
            viewModel.liquifyActions.setFaceValue(FaceSlider.FACE_WIDTH, 0.65f)
            viewModel.liquifyActions.setFaceValue(FaceSlider.FACE_WIDTH, 1f)
            viewModel.liquifyActions.finishFaceValue()
        }
        val changed = awaitLiquify { it.faceValues?.get(FaceSlider.FACE_WIDTH) == 1f }
        assertTrue(changed.changed)
        assertTrue(changed.canUndo)

        onMain { viewModel.liquifyActions.undo() }
        val undone = awaitLiquify { it.faceValues?.isZero == true }
        assertFalse(undone.changed)
        assertFalse("one drag is one step", undone.canUndo)
        assertTrue(undone.canRedo)

        onMain { viewModel.liquifyActions.redo() }
        assertEquals(1f, awaitLiquify { it.faceValues?.isZero == false }.faceValues?.get(FaceSlider.FACE_WIDTH))
    }

    @Test
    fun aWiderFace_movesTheShownOutline_andApplyChangesThePixelsInOneStep() {
        openWithRedBlobs(400f to 450f)
        val before = awaitReady { true }
        val outline = checkNotNull(startFaceMode().faces)[0]
        // 얼굴 윤곽의 오른쪽 볼 점(454)
        val cheek = 454
        assertTrue(red(before, 488, 500))
        assertFalse(red(before, 497, 500))

        onMain {
            viewModel.liquifyActions.setFaceValue(FaceSlider.FACE_WIDTH, 1f)
            viewModel.liquifyActions.finishFaceValue()
        }
        val wider = awaitLiquify { it.faceValues?.get(FaceSlider.FACE_WIDTH) == 1f && it.faces != null }
        assertTrue("outline ${outline.x(cheek)} → ${wider.faces!![0].x(cheek)}", wider.faces!![0].x(cheek) > outline.x(cheek) + 2f)

        onMain { viewModel.liquifyActions.apply() }
        runBlocking { withTimeout(30_000) { viewModel.liquify.first { it == null } } }
        val after = awaitReady { it.revision > before.revision }
        assertTrue(after.canUndo)
        // 덩어리 가장자리(y 500에서 x 495)가 바깥으로 약 6px(한쪽 0.08U = 6.4px, 윤곽 바로 바깥이라 조금 덜) 넓어졌다
        assertTrue(red(after, 497, 500))

        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > after.revision }
        assertFalse(red(undone, 497, 500))
    }

    @Test
    fun noSelectedFace_ignoresTheSliders_andResetFaceClearsOnlyThatFace() {
        openWithRedBlobs(300f to 450f, 900f to 450f)
        startFaceMode()

        onMain { viewModel.liquifyActions.setFaceValue(FaceSlider.EYE_SIZE, 0.5f) }
        val ignored = awaitLiquify { true }
        assertNull(ignored.faceValues)
        assertFalse(ignored.changed)

        // 오른쪽 얼굴을 골라 바꾸고, 왼쪽으로 옮겨 바꾼 뒤 왼쪽만 초기화한다
        onMain {
            viewModel.liquifyActions.pickFace(900f, 450f)
            viewModel.liquifyActions.setFaceValue(FaceSlider.NOSE_WIDTH, -0.4f)
            viewModel.liquifyActions.finishFaceValue()
            viewModel.liquifyActions.pickFace(300f, 450f)
            viewModel.liquifyActions.setFaceValue(FaceSlider.EYE_SIZE, 0.5f)
            viewModel.liquifyActions.finishFaceValue()
        }
        assertEquals(0.5f, awaitLiquify { it.selectedFace == 0 && it.faceValues?.isZero == false }.faceValues?.get(FaceSlider.EYE_SIZE))
        onMain { viewModel.liquifyActions.resetFace() }
        val reset = awaitLiquify { it.faceValues?.isZero == true }
        assertTrue("the other face keeps its change", reset.changed)
        onMain { viewModel.liquifyActions.pickFace(900f, 450f) }
        assertEquals(-0.4f, awaitLiquify { it.selectedFace == 1 }.faceValues?.get(FaceSlider.NOSE_WIDTH))
    }

    @Test
    fun resetAll_clearsTheFaceValues_andCancelLeavesTheDocument() {
        openWithRedBlobs(400f to 450f)
        val before = awaitReady { true }
        startFaceMode()
        onMain {
            viewModel.liquifyActions.setFaceValue(FaceSlider.EYE_SIZE, 1f)
            viewModel.liquifyActions.finishFaceValue()
            viewModel.liquifyActions.resetFaceValue(FaceSlider.EYE_SIZE)
        }
        assertTrue(awaitLiquify { it.faceValues?.isZero == true }.canUndo)
        onMain { viewModel.liquifyActions.undo() }
        awaitLiquify { it.faceValues?.get(FaceSlider.EYE_SIZE) == 1f }

        onMain { viewModel.liquifyActions.reset() }
        assertFalse(awaitLiquify { it.faceValues?.isZero == true }.changed)
        onMain { viewModel.liquifyActions.undo() }
        awaitLiquify { it.faceValues?.get(FaceSlider.EYE_SIZE) == 1f }

        onMain { viewModel.liquifyActions.cancel() }
        runBlocking { withTimeout(30_000) { viewModel.liquify.first { it == null } } }
        assertEquals(before.revision, (viewModel.state.value as EditorState.Ready).revision)
    }

    @Test
    fun undoingAStrokeMadeBeforeTheFacesWereFound_keepsTheFaceSliders() {
        openWithRedBlobs(400f to 450f)
        onMain { viewModel.startLiquify() }
        awaitLiquify { true }
        // 얼굴을 찾기 전에 한 획(그 되돌리기 단계에는 얼굴 값이 없다)
        onMain {
            viewModel.liquifyActions.setTool(LiquifyTool.PUSH)
            viewModel.liquifyActions.begin(1000f, 150f)
            viewModel.liquifyActions.move(1040f, 150f)
            viewModel.liquifyActions.end()
        }
        awaitLiquify { it.changed }
        onMain { viewModel.liquifyActions.setFaceMode() }
        awaitFaces()

        onMain { viewModel.liquifyActions.undo() }

        val undone = awaitLiquify { !it.changed }
        assertEquals(FaceValues.ZERO, undone.faceValues)
        onMain {
            viewModel.liquifyActions.setFaceValue(FaceSlider.EYE_SIZE, 0.4f)
            viewModel.liquifyActions.finishFaceValue()
        }
        assertEquals(0.4f, awaitLiquify { it.faceValues?.isZero == false }.faceValues?.get(FaceSlider.EYE_SIZE))
    }

    @Test
    fun aSliderDragBackToItsStart_leavesUndoAndRedoAsTheyWere() {
        openWithRedBlobs(400f to 450f)
        startFaceMode()
        onMain {
            viewModel.liquifyActions.setFaceValue(FaceSlider.NOSE_WIDTH, 0.5f)
            viewModel.liquifyActions.finishFaceValue()
            viewModel.liquifyActions.undo()
        }
        assertFalse(awaitLiquify { it.faceValues?.isZero == true && it.canRedo }.canUndo)

        // 끌었다가 제자리로 돌아오면 아무 단계도 남기지 않고, 다시 실행도 그대로다
        onMain {
            viewModel.liquifyActions.setFaceValue(FaceSlider.NOSE_WIDTH, 0.3f)
            viewModel.liquifyActions.setFaceValue(FaceSlider.NOSE_WIDTH, 0f)
            viewModel.liquifyActions.finishFaceValue()
        }
        val after = awaitLiquify { true }
        assertFalse(after.canUndo)
        assertTrue(after.canRedo)
        onMain { viewModel.liquifyActions.redo() }
        assertEquals(0.5f, awaitLiquify { it.faceValues?.isZero == false }.faceValues?.get(FaceSlider.NOSE_WIDTH))
    }

    @Test
    fun releasingTheAiModels_releasesTheFaceModel() {
        onMain { viewModel.releaseAiModels() }

        assertEquals(1, model.releases)
        assertFalse(model.detectCalls > 0)
    }

    private companion object {
        val SIZE = PixelSize(1200, 900)
    }
}
