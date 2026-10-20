package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.NotEnoughMemoryException
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.inpaint.InpaintUnavailableException
import com.kiln.stratum.engine.platform.InpaintRegion
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AI 제거 명령(InpaintRegion)을 가짜 모델로 에뮬레이터에서 확인한다(결정 기록 'AI 제거·AI 선택'). */
@RunWith(AndroidJUnit4::class)
class InpaintRegionTest {

    private fun gray(level: Int) = Color.rgb(level, level, level)

    /** [width]×[height] 문서. 픽셀마다 [color]로 칠한 레이어 하나다(힙 타일). */
    private fun documentOf(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Document {
        val grid = TileGrid(width, height)
        val tiles = grid.keys().associateWith { key ->
            Tile.of(IntArray(TILE_SIZE * TILE_SIZE) { i -> color(key.column * TILE_SIZE + i % TILE_SIZE, key.row * TILE_SIZE + i / TILE_SIZE) })
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    private fun rasterOf(document: Document): Raster = (document.layers[0].content as RasterContent).raster

    private fun pixel(document: Document, x: Int, y: Int): Int =
        rasterOf(document).tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private fun painted(vararg points: Pair<Float, Float>, radius: Float) =
        InpaintArea.Painted(points.map { StrokePoint(it.first, it.second) }, radius)

    /** 회색 128 바탕 800×600에 빨간 원 */
    private fun grayWithRedSpot(x: Float = 400f, y: Float = 300f, radius: Float = 20f): Document =
        documentOf(800, 600) { px, py -> if (hypot(px + 0.5f - x, py + 0.5f - y) <= radius) Color.RED else gray(128) }

    private fun assertGray(color: Int, level: Int = 128, tolerance: Int = 3) {
        val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
        assertTrue("${Integer.toHexString(color)}", channels.all { abs(it - level) <= tolerance } && Color.alpha(color) == 255)
    }

    @Test
    fun remove_fillsTheStroke_andLeavesEveryOtherPixelExactly() {
        val document = grayWithRedSpot()
        val model = FakeInpaintModel()

        val removed = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), model).applyTo(document)

        assertEquals(1, model.calls)
        for ((x, y) in listOf(400 to 300, 415 to 300, 400 to 285, 386 to 314)) assertGray(pixel(removed, x, y))
        // 넓힌 띠(문서 4px)와 그 밖은 원래 값 그대로다
        for (y in 0 until 600 step 7) {
            for (x in 0 until 800 step 7) {
                if (hypot(x + 0.5f - 400f, y + 0.5f - 300f) > 40f) assertEquals("($x, $y)", pixel(document, x, y), pixel(removed, x, y))
            }
        }
    }

    @Test
    fun remove_sharesTheTilesItDoesNotTouch() {
        val document = grayWithRedSpot()

        val removed = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), FakeInpaintModel()).applyTo(document)

        assertSame(rasterOf(document).tiles[TileKey(0, 0)], rasterOf(removed).tiles[TileKey(0, 0)])
        assertSame(rasterOf(document).tiles[TileKey(2, 2)], rasterOf(removed).tiles[TileKey(2, 2)])
        assertNotSame(rasterOf(document).tiles[TileKey(1, 1)], rasterOf(removed).tiles[TileKey(1, 1)])
    }

    @Test
    fun remove_staysInsideTheSelection() {
        // 왼쪽 절반만 고른다
        val selected = SelectShape(SelectionShape.Rectangle(0f, 0f, 400f, 600f), SelectionMode.REPLACE).applyTo(grayWithRedSpot())

        val removed = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), FakeInpaintModel()).applyTo(selected)

        val inside = pixel(removed, 385, 300)
        assertTrue("${Integer.toHexString(inside)} is no longer red", Color.red(inside) < 170 && Color.green(inside) > 90)
        assertEquals(Color.RED, pixel(removed, 405, 300))
        assertEquals(Color.RED, pixel(removed, 415, 300))
    }

    @Test
    fun fillSelection_replacesTheSelectedArea_andKeepsTheRestExactly() {
        val document = SelectShape(SelectionShape.Rectangle(370f, 270f, 430f, 330f), SelectionMode.REPLACE).applyTo(grayWithRedSpot())
        val model = FakeInpaintModel()

        val filled = InpaintRegion(LayerId(0), InpaintArea.Selected, model).applyTo(document)

        assertEquals(1, model.calls)
        assertGray(pixel(filled, 400, 300))
        assertGray(pixel(filled, 380, 290))
        for ((x, y) in listOf(369 to 300, 431 to 300, 400 to 269, 400 to 331, 300 to 300)) assertEquals("($x, $y)", pixel(document, x, y), pixel(filled, x, y))
        // 채운 뒤에도 선택은 남는다
        assertSame(document.selection, filled.selection)
    }

    @Test
    fun remove_atTheEdge_staysInsideTheDocument_andFillsUpToIt() {
        val document = grayWithRedSpot(x = 792f, y = 300f, radius = 12f)

        val removed = InpaintRegion(LayerId(0), painted(795f to 300f, radius = 22f), FakeInpaintModel()).applyTo(document)

        for (x in 785 until 800) assertGray(pixel(removed, x, 300))
    }

    @Test
    fun nothingToFill_isNoChange() {
        val document = grayWithRedSpot()
        val model = FakeInpaintModel()

        assertSame(document, InpaintRegion(LayerId(0), painted(-200f to -200f, radius = 30f), model).applyTo(document))
        assertSame(document, InpaintRegion(LayerId(0), InpaintArea.Selected, model).applyTo(document))
        assertSame(document, InpaintRegion(LayerId(7), painted(400f to 300f, radius = 30f), model).applyTo(document))
        val empty = Document.create(800, 600, "Empty", Raster.EMPTY)
        assertSame(empty, InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), model).applyTo(empty))
        assertEquals(0, model.calls)
    }

    @Test
    fun onATransparentLayer_theFillTakesItsAlphaFromAround() {
        // 왼쪽 절반은 불투명한 회색, 오른쪽 절반은 투명
        val document = documentOf(800, 600) { x, _ -> if (x < 400) gray(128) else Color.TRANSPARENT }

        val removed = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 40f), FakeInpaintModel()).applyTo(document)

        assertTrue(Color.alpha(pixel(removed, 368, 300)) > 200)
        assertTrue(Color.alpha(pixel(removed, 432, 300)) < 60)
    }

    @Test
    fun aModelThatPaintsTooBright_isMatchedToTheSurroundings() {
        val document = grayWithRedSpot()

        // 모든 출력을 약 25단계 밝게 내는 모델
        val removed = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), FakeInpaintModel(offset = 0.2f)).applyTo(document)

        assertGray(pixel(removed, 400, 300))
        assertGray(pixel(removed, 420, 300))
    }

    @Test
    fun twoSpotsFarApart_useTwoWindows() {
        val document = documentOf(800, 600) { _, _ -> gray(128) }
        val model = FakeInpaintModel()

        InpaintRegion(LayerId(0), painted(100f to 100f, 700f to 500f, radius = 20f), model).applyTo(document)

        assertEquals(2, model.calls)
    }

    @Test
    fun aRingPaintedAroundSomething_alsoRemovesTheInside() {
        val document = grayWithRedSpot(radius = 30f)
        // 반지름 45의 고리로 둘러 칠한다(안쪽 빨간 원은 칠하지 않음)
        val ring = (0 until 72).map { i ->
            val angle = i * Math.PI * 2 / 72
            (400f + 45f * kotlin.math.cos(angle).toFloat()) to (300f + 45f * kotlin.math.sin(angle).toFloat())
        }

        val removed = InpaintRegion(LayerId(0), painted(*ring.toTypedArray(), radius = 8f), FakeInpaintModel()).applyTo(document)

        assertGray(pixel(removed, 400, 300))
        assertGray(pixel(removed, 420, 310))
    }

    @Test
    fun outOfMemoryInTheModel_becomesNotEnoughMemory() {
        val command = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), FakeInpaintModel(failure = OutOfMemoryError()))

        assertThrows(NotEnoughMemoryException::class.java) { command.applyTo(grayWithRedSpot()) }
    }

    @Test
    fun anUnavailableModel_isPassedOn() {
        val command = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), FakeInpaintModel(failure = InpaintUnavailableException("No device")))

        assertThrows(InpaintUnavailableException::class.java) { command.applyTo(grayWithRedSpot()) }
    }

    @Test
    fun estimates_countTheTilesAroundTheStroke_andTheModelLoadSeparately() {
        val document = grayWithRedSpot()
        val command = InpaintRegion(LayerId(0), painted(400f to 300f, radius = 30f), FakeInpaintModel(pendingLoadBytes = 123L))

        // 자국 범위(369..431, 269..331)에서 64px 넓힌 곳은 타일 (1, 0)·(1, 1) 두 장이다
        assertEquals(2L * TILE_SIZE * TILE_SIZE * 4, command.estimateNewBytes(document))
        // 창 하나의 계산 메모리(64MiB)와 모델 불러오기
        assertEquals(64L * 1024 * 1024 + 123L, command.estimateWorkBytes(document))
    }
}
