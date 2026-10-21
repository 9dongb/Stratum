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

    /** 흰 [width]×[height] 문서에 검은 원 하나(가운데, 반지름 [radius]). 한 가지 색 타일은 한 장을 함께 쓴다. */
    private fun bigDisc(width: Int, height: Int, radius: Float): Document {
        val cx = width / 2f
        val cy = height / 2f
        val white = Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { Color.WHITE })
        val tiles = TileGrid(width, height).keys().associateWith { key ->
            val left = key.column * TILE_SIZE
            val top = key.row * TILE_SIZE
            // 원에 닿지 않는 타일은 흰 타일 하나를 함께 쓴다
            val nearest = hypot((cx - cx.coerceIn(left.toFloat(), left + TILE_SIZE.toFloat())), (cy - cy.coerceIn(top.toFloat(), top + TILE_SIZE.toFloat())))
            if (nearest > radius + 2) white else Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { i ->
                if (hypot(left + i % TILE_SIZE + 0.5f - cx, top + i / TILE_SIZE + 0.5f - cy) <= radius) Color.BLACK else Color.WHITE
            })
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    @Test
    fun inABigPicture_theSelectionEdgeIsSmoothAndCrisp() {
        // 3600×2400(마스크는 1/4 크기, 한 칸 4px): 원의 경계가 마스크 칸의 계단을 따라 물결치지 않고, 2~3px 안에서 0에서 255로 바뀐다
        val radius = 700f
        val document = bigDisc(3600, 2400, radius)

        val selected = tap(ObjectSegmenter(FakeSegmentModel()), document, 1800f, 1200f)

        val crossings = ArrayList<Float>()
        for (step in 0 until 72) {
            val angle = step * Math.PI / 36
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            // 반지름 방향으로 0.25px씩 걸으며 알파가 128 아래로 내려가는 자리와, 10~245 사이인 폭
            var crossing = Float.NaN
            var soft = 0
            var r = radius - 12f
            while (r < radius + 12f) {
                val a = alpha(selected, (1800f + dx * r).toInt(), (1200f + dy * r).toInt())
                if (a in 11..244) soft++
                if (crossing.isNaN() && a < 128) crossing = r
                r += 0.25f
            }
            assertTrue("angle $step: soft band ${soft / 4f}px", soft / 4f <= 4f)
            crossings += crossing
        }
        val mean = crossings.average().toFloat()
        val wobble = crossings.maxOf { kotlin.math.abs(it - mean) }
        assertEquals(radius, mean, 4f)
        assertTrue("edge wobbles by $wobble px", wobble <= 2f)
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
