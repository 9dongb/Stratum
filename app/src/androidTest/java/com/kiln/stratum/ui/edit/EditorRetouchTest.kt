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
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 리터칭 도구의 편집 흐름(가져올 곳·간격·기록·실행 취소·스팟 복구)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EditorRetouchTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = EditorViewModel(context)

    @After
    fun deleteProjects() = deleteTestProjects(context, viewModel)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private fun awaitReady(predicate: (EditorState.Ready) -> Boolean): EditorState.Ready = runBlocking {
        withTimeout(30_000) { viewModel.state.first { it is EditorState.Ready && !it.busy && predicate(it) } as EditorState.Ready }
    }

    /** 흰 캔버스 1000×800(화면에 맞추면 원본 크기 축소본)에 검은 점(가운데 (200, 200), 지름 30)을 찍는다 */
    private fun openWithDot(): EditorState.Ready {
        onMain {
            viewModel.onCanvasSize(1000, 800)
            viewModel.newCanvas(SIZE.width, SIZE.height, CanvasFill.Solid(Color.WHITE))
        }
        val opened = awaitReady { it.documentSize == SIZE }
        onMain { viewModel.edit(PaintStroke(LayerId(0), listOf(StrokePoint(200f, 200f)), BrushSettings(size = 30f, hardness = 1f, color = Color.BLACK), false)) }
        return awaitReady { it.revision > opened.revision }
    }

    private fun shown(state: EditorState.Ready): Bitmap = state.base.bitmap.copy(Bitmap.Config.ARGB_8888, false)

    private fun dark(bitmap: Bitmap, x: Int, y: Int) = Color.red(bitmap.getPixel(x, y)) < 100

    private fun stroke(mode: RetouchMode, vararg points: StrokePoint) = onMain {
        viewModel.retouchActions.begin(points.first().x, points.first().y, mode)
        for (point in points.drop(1)) viewModel.retouchActions.extend(point.x, point.y)
        viewModel.retouchActions.end()
    }

    @Test
    fun clone_takesFromTheSource_isOneUndoStep_andKeepsTheSpacing() {
        val before = openWithDot()

        onMain { viewModel.retouchActions.setSource(200f, 200f) }
        stroke(RetouchMode.CLONE, StrokePoint(600f, 400f))
        val cloned = awaitReady { it.revision > before.revision }

        assertTrue(dark(shown(cloned), 600, 400))
        assertTrue(cloned.canUndo)
        // 첫 획이 간격(칠한 곳 - 가져올 곳)을 정했고, 다음 획도 같은 간격으로 가져온다
        assertEquals(SourceOffset(400, 200), viewModel.cloneSource.value?.offset)
        stroke(RetouchMode.CLONE, StrokePoint(650f, 400f))
        val again = awaitReady { it.revision > cloned.revision }
        assertFalse(dark(shown(again), 650, 400))
        assertEquals(SourceOffset(400, 200), viewModel.cloneSource.value?.offset)

        // 실행 취소 두 번이면 처음으로 돌아간다
        onMain { viewModel.undo() }
        val undone = awaitReady { it.revision > again.revision }
        onMain { viewModel.undo() }
        assertFalse(dark(shown(awaitReady { it.revision > undone.revision }), 600, 400))
    }

    @Test
    fun aligned_theMarkerStaysWhereTheStrokeLastTookFrom() {
        val before = openWithDot()
        onMain { viewModel.retouchActions.setSource(200f, 200f) }

        stroke(RetouchMode.CLONE, StrokePoint(600f, 400f), StrokePoint(650f, 400f))
        awaitReady { it.revision > before.revision }

        // 표시는 획이 마지막으로 가져온 자리(650 - 400, 400 - 200)로 옮겨 가 계속 보이고, 간격은 그대로다(사용자 요청)
        assertEquals(CloneSource(250f, 200f, SourceOffset(400, 200)), viewModel.cloneSource.value)
    }

    @Test
    fun locked_everyStrokeTakesFromTheMarkerAgain() {
        val before = openWithDot()
        onMain {
            viewModel.retouchActions.setSource(200f, 200f)
            viewModel.retouchActions.toggleSourceLock()
        }

        stroke(RetouchMode.CLONE, StrokePoint(600f, 400f))
        val first = awaitReady { it.revision > before.revision }
        stroke(RetouchMode.CLONE, StrokePoint(700f, 300f))
        val second = awaitReady { it.revision > first.revision }

        // 두 획 모두 표시 자리(점)에서 가져왔다. 정렬(풀린 상태)이었다면 두 번째 획은 (300, 100)의 흰 곳을 가져온다.
        assertTrue(dark(shown(second), 600, 400))
        assertTrue(dark(shown(second), 700, 300))
        // 표시는 고정된 자리에 남고, 간격은 남기지 않는다
        assertEquals(CloneSource(200f, 200f, offset = null, locked = true), viewModel.cloneSource.value)

        // 다시 꾹 누르면 풀린다
        onMain { viewModel.retouchActions.toggleSourceLock() }
        assertEquals(CloneSource(200f, 200f), viewModel.cloneSource.value)
    }

    @Test
    fun movingTheMarker_takesFromThereNext_andStaysInTheDocument() {
        val before = openWithDot()
        onMain { viewModel.retouchActions.setSource(500f, 500f) }
        stroke(RetouchMode.CLONE, StrokePoint(800f, 600f))
        val cloned = awaitReady { it.revision > before.revision }

        // 표시를 점으로 끌어 옮기면 다음 획이 간격을 새로 정한다
        onMain { viewModel.retouchActions.moveSource(200f, 200f) }
        assertEquals(CloneSource(200f, 200f), viewModel.cloneSource.value)
        stroke(RetouchMode.CLONE, StrokePoint(600f, 400f))
        assertTrue(dark(shown(awaitReady { it.revision > cloned.revision }), 600, 400))
        assertEquals(SourceOffset(400, 200), viewModel.cloneSource.value?.offset)

        // 문서 밖으로 끌면 가장자리에 멈춘다
        onMain { viewModel.retouchActions.moveSource(-50f, 5000f) }
        assertEquals(CloneSource(0f, 799f), viewModel.cloneSource.value)
    }

    @Test
    fun aFirstStrokeOnTheSource_orADroppedOne_leavesNoSpacing() {
        openWithDot()
        onMain { viewModel.retouchActions.setSource(200f, 200f) }

        // 가져올 곳에서 바로 칠하면 간격이 0이라 남기지 않는다(남기면 다음 획도 모두 제자리를 베껴 아무것도 바뀌지 않는다)
        onMain {
            viewModel.retouchActions.begin(200f, 200f, RetouchMode.CLONE)
            viewModel.retouchActions.end()
        }
        assertNull(viewModel.cloneSource.value?.offset)

        // 두 손가락으로 버린 첫 획도 간격을 남기지 않는다
        onMain {
            viewModel.retouchActions.begin(600f, 400f, RetouchMode.CLONE)
            viewModel.retouchActions.cancel()
        }
        assertEquals(CloneSource(200f, 200f), viewModel.cloneSource.value)
    }

    @Test
    fun healWithoutASource_isIgnored() {
        val before = openWithDot()

        stroke(RetouchMode.HEAL, StrokePoint(600f, 400f))

        // 기록하지 않는다(그릴 것이 없다)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val after = viewModel.state.value as EditorState.Ready
        assertEquals(before.revision, after.revision)
        assertFalse(after.canRedo)
    }

    @Test
    fun spotHeal_removesTheDot() {
        val before = openWithDot()
        onMain { viewModel.setRetouch(BrushSettings(size = 50f, hardness = 0.8f)) }

        stroke(RetouchMode.SPOT_HEAL, StrokePoint(200f, 200f))

        val healed = awaitReady { it.revision > before.revision }
        assertFalse(dark(shown(healed), 200, 200))
        assertTrue(healed.canUndo)
    }

    @Test
    fun onANewEmptyLayer_spotHealTakesFromBelowOnlyWhenChosen() {
        // 결정 기록 '피부 보정'의 5단계: 빈 레이어에서는 '이 레이어'면 가져올 것이 없고, '아래 포함'이면 아래 사진의 점을 지운 결과를 그 레이어에 그린다
        openWithDot()
        onMain {
            viewModel.addLayer()
            viewModel.setRetouch(BrushSettings(size = 50f, hardness = 0.8f))
        }
        val added = awaitReady { it.layers.size == 2 }
        val layer = checkNotNull(added.activeLayer)
        val photoPixels = checkNotNull(viewModel.recordedDocument).layers.first().content
        assertEquals(false, viewModel.retouchSampleBelow.value)

        stroke(RetouchMode.SPOT_HEAL, StrokePoint(200f, 200f))
        val unchanged = awaitReady { true }
        assertTrue(dark(shown(unchanged), 200, 200))

        onMain { viewModel.retouchActions.setSampleBelow(true) }
        assertEquals(true, viewModel.retouchSampleBelow.value)
        stroke(RetouchMode.SPOT_HEAL, StrokePoint(200f, 200f))
        val healed = awaitReady { it.revision > added.revision }
        assertFalse(dark(shown(healed), 200, 200))
        // 아래 사진은 그대로이고 새 레이어에 그렸다
        val document = checkNotNull(viewModel.recordedDocument)
        assertSame(photoPixels, document.layers.first().content)
        assertTrue((document.layers.first { it.id == layer }.content as RasterContent).raster.tiles.isNotEmpty())
    }

    @Test
    fun theSourceIsForgotten_whenTheDocumentChangesSize() {
        openWithDot()
        onMain { viewModel.retouchActions.setSource(200f, 200f) }
        assertEquals(CloneSource(200f, 200f), viewModel.cloneSource.value)

        // 문서 밖은 가져올 곳이 되지 않는다
        onMain { viewModel.retouchActions.setSource(-5f, 200f) }
        assertEquals(CloneSource(200f, 200f), viewModel.cloneSource.value)

        val rotated = runBlocking {
            onMain { viewModel.edit(RotateImage(clockwise = true)) }
            awaitReady { it.documentSize == PixelSize(SIZE.height, SIZE.width) }
        }
        assertEquals(PixelSize(800, 1000), rotated.documentSize)
        assertNull(viewModel.cloneSource.value)
    }

    private companion object {
        val SIZE = PixelSize(1000, 800)
    }
}
