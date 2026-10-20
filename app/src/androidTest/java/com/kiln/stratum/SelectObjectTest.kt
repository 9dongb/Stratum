package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.history.EditHistory
import com.kiln.stratum.engine.platform.Deselect
import com.kiln.stratum.engine.platform.ObjectSegmenter
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.SelectObject
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.segment.SegmentUnavailableException
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AI 선택 명령(SelectObject)과 물체 찾기 서비스(ObjectSegmenter)를 가짜 분할 모델로 에뮬레이터에서 확인한다(결정 기록 'AI 제거·AI 선택'). */
@RunWith(AndroidJUnit4::class)
class SelectObjectTest {

    /** 흰 600×400 문서에 검은 원 둘(왼쪽 (150, 200), 오른쪽 (450, 200), 반지름 60) */
    private fun twoDiscs(): Document {
        val width = 600
        val height = 400
        val tiles = TileGrid(width, height).keys().associateWith { key ->
            Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { i ->
                val x = key.column * TILE_SIZE + i % TILE_SIZE + 0.5f
                val y = key.row * TILE_SIZE + i / TILE_SIZE + 0.5f
                if (hypot(x - 150f, y - 200f) <= 60f || hypot(x - 450f, y - 200f) <= 60f) Color.BLACK else Color.WHITE
            })
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    private fun alpha(document: Document, x: Int, y: Int): Int = checkNotNull(document.selection).alphaAt(x, y)

    private fun tap(segmenter: ObjectSegmenter, document: Document, x: Float, y: Float, mode: SelectionMode = SelectionMode.REPLACE) =
        SelectObject(x, y, mode, segmenter).applyTo(document)

    @Test
    fun tappingADisc_selectsItsShape_asOneUndoStep() {
        val segmenter = ObjectSegmenter(FakeSegmentModel())
        val history = EditHistory(twoDiscs())

        assertTrue(history.execute(SelectObject(150f, 200f, SelectionMode.REPLACE, segmenter)))

        val selected = history.document
        assertEquals(255, alpha(selected, 150, 200))
        assertEquals(255, alpha(selected, 195, 200))
        assertEquals(0, alpha(selected, 225, 200))
        assertEquals(0, alpha(selected, 450, 200))
        assertEquals(0, alpha(selected, 20, 20))
        // 실행 취소 한 번이면 선택이 없던 문서로 돌아간다
        assertTrue(history.undo())
        assertNull(history.document.selection)
        assertFalse(history.canUndo)
    }

    @Test
    fun modes_combineWithThePreviousSelection() {
        val segmenter = ObjectSegmenter(FakeSegmentModel())
        val left = tap(segmenter, twoDiscs(), 150f, 200f)

        val both = tap(segmenter, left, 450f, 200f, SelectionMode.ADD)
        assertEquals(255, alpha(both, 150, 200))
        assertEquals(255, alpha(both, 450, 200))

        val right = tap(segmenter, both, 150f, 200f, SelectionMode.SUBTRACT)
        assertEquals(0, alpha(right, 150, 200))
        assertEquals(255, alpha(right, 450, 200))

        // 오른쪽 원의 왼쪽 반만 고른 뒤 교차로 원을 누르면 겹친 곳만 남는다
        val half = SelectShape(SelectionShape.Rectangle(300f, 0f, 450f, 400f), SelectionMode.REPLACE).applyTo(twoDiscs())
        val overlap = tap(segmenter, half, 450f, 200f, SelectionMode.INTERSECT)
        assertEquals(255, alpha(overlap, 420, 200))
        assertEquals(0, alpha(overlap, 480, 200))
        assertEquals(0, alpha(overlap, 320, 50))
    }

    @Test
    fun thePictureIsReadOnce_andAgainOnlyAfterItChanges() {
        val model = FakeSegmentModel()
        val segmenter = ObjectSegmenter(model)
        val document = twoDiscs()

        val first = tap(segmenter, document, 150f, 200f)
        tap(segmenter, first, 450f, 200f, SelectionMode.ADD)
        assertEquals(1, model.encodes)
        assertEquals(2, model.decodes)

        // 선택 영역만 바뀌면 그대로 쓴다
        tap(segmenter, Deselect.applyTo(first), 150f, 200f)
        assertEquals(1, model.encodes)

        // 픽셀이 바뀌면 다시 읽는다
        val painted = PaintStroke(LayerId(0), listOf(StrokePoint(300f, 350f)), BrushSettings(size = 20f, color = Color.BLACK), false).applyTo(document)
        tap(segmenter, painted, 150f, 200f)
        assertEquals(2, model.encodes)

        // 불투명도가 바뀌어도 보이는 그림이 달라 다시 읽는다
        tap(segmenter, SetLayerOpacity(LayerId(0), 0.5f).applyTo(painted), 150f, 200f)
        assertEquals(3, model.encodes)
    }

    @Test
    fun prepare_readsAhead_andTheTapUsesIt() {
        val model = FakeSegmentModel()
        val segmenter = ObjectSegmenter(model)
        val document = twoDiscs()

        segmenter.prepare(document)
        segmenter.prepare(document)
        assertEquals(1, model.encodes)

        assertEquals(255, alpha(tap(segmenter, document, 150f, 200f), 150, 200))
        assertEquals(1, model.encodes)
    }

    @Test
    fun outsideTheDocument_orNothingFound_changesNothing() {
        val model = FakeSegmentModel()
        val segmenter = ObjectSegmenter(model)
        val document = twoDiscs()

        assertSame(document, tap(segmenter, document, -5f, 100f))
        assertSame(document, tap(segmenter, document, 600f, 100f))
        assertEquals(0, model.encodes)

        model.found = false
        assertSame(document, tap(segmenter, document, 150f, 200f))
    }

    @Test
    fun anUnavailableModel_isPassedOn() {
        val segmenter = ObjectSegmenter(FakeSegmentModel(failure = SegmentUnavailableException("No device")))

        assertThrows(SegmentUnavailableException::class.java) { tap(segmenter, twoDiscs(), 150f, 200f) }
    }

    @Test
    fun workBytes_countTheReadAndTheModelLoad_untilTheyAreDone() {
        val segmenter = ObjectSegmenter(FakeSegmentModel(loadBytes = 500L * 1024 * 1024))
        val document = twoDiscs()
        val command = SelectObject(150f, 200f, SelectionMode.REPLACE, segmenter)

        val before = command.estimateWorkBytes(document)
        val selected = command.applyTo(document)
        val after = command.estimateWorkBytes(selected)

        assertTrue("$before", before >= 500L * 1024 * 1024)
        assertTrue("$after < $before", after < 100L * 1024 * 1024)
        // 그림이 바뀌면 다시 읽기 몫이 붙는다
        assertTrue(command.estimateWorkBytes(SetLayerOpacity(LayerId(0), 0.5f).applyTo(document)) > after)
    }

    @Test
    fun release_forgetsThePicture_andReleasesTheModel() {
        val model = FakeSegmentModel()
        val segmenter = ObjectSegmenter(model)
        val document = twoDiscs()
        tap(segmenter, document, 150f, 200f)

        segmenter.release()
        tap(segmenter, document, 150f, 200f)

        assertEquals(1, model.releases)
        assertEquals(2, model.encodes)
    }
}
