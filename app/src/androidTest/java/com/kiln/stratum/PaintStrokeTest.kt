package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 브러시·지우개 획을 원본 해상도 타일에 그리는 일(소프트웨어 그리기)을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class PaintStrokeTest {

    /** 흰 바탕 600×300 캔버스 */
    private fun white(): Document = checkNotNull(BlankCanvas.create(600, 300, CanvasFill.Solid(Color.WHITE), "Background"))

    private fun rasterOf(document: Document, index: Int = 0): Raster = (document.layers[index].content as RasterContent).raster

    /** 문서 좌표 ([x], [y])의 픽셀(알파를 곱하지 않은 ARGB) */
    private fun pixel(raster: Raster, x: Int, y: Int): Int {
        val tile = raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: return Color.TRANSPARENT
        return tile.pixelAt(x % TILE_SIZE, y % TILE_SIZE)
    }

    /** 가로 한 줄 획(왼쪽 [from]에서 오른쪽 [to]까지, 높이 [y]) */
    private fun line(from: Float, to: Float, y: Float, settings: BrushSettings) =
        StrokeDabs.place(listOf(StrokePoint(from, y), StrokePoint(to, y)), settings.spacing)

    private fun near(expected: Int, actual: Int, tolerance: Int) =
        abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    @Test
    fun brush_paintsAlongTheStroke_andLeavesTheRestExactlyAsItWas() {
        val document = white()
        val settings = BrushSettings(size = 20f, hardness = 1f, opacity = 1f, color = Color.RED)

        val painted = PaintStroke(LayerId(0), line(50f, 350f, 100f, settings), settings, erase = false).applyTo(document)

        val raster = rasterOf(painted)
        assertEquals(Color.RED, pixel(raster, 200, 100))
        assertEquals(Color.WHITE, pixel(raster, 200, 130))
        // 획이 닿지 않은 타일(오른쪽 아래)은 앞 문서와 같은 객체다(실행 취소 기록이 함께 씀)
        assertSame(rasterOf(document).tiles[TileKey(2, 1)], raster.tiles[TileKey(2, 1)])
    }

    @Test
    fun opacity_capsTheWholeStroke_evenWhereDabsOverlap() {
        val settings = BrushSettings(size = 30f, hardness = 1f, opacity = 0.5f, color = Color.BLACK)

        val painted = PaintStroke(LayerId(0), line(50f, 350f, 100f, settings), settings, erase = false).applyTo(white())

        // 자국이 열 번쯤 겹치는 가운데도 반만 어둡다(흰색과 검은색의 가운데)
        val center = pixel(rasterOf(painted), 200, 100)
        assertTrue(Integer.toHexString(center), near(Color.rgb(128, 128, 128), center, tolerance = 2))
    }

    @Test
    fun softBrush_fadesTowardsTheEdge() {
        val settings = BrushSettings(size = 80f, hardness = 0f, opacity = 1f, color = Color.BLACK)

        val painted = PaintStroke(LayerId(0), listOf(StrokePoint(300f, 150f)), settings, erase = false).applyTo(white())

        val raster = rasterOf(painted)
        val center = Color.red(pixel(raster, 300, 150))
        val middle = Color.red(pixel(raster, 320, 150))
        val edge = Color.red(pixel(raster, 338, 150))
        assertTrue("center $center, middle $middle, edge $edge", center < middle && middle < edge && edge < 255)
        assertEquals(Color.WHITE, pixel(raster, 345, 150))
    }

    @Test
    fun eraser_clearsPixels_andLeavesEmptyPlacesEmpty() {
        val settings = BrushSettings(size = 20f, hardness = 1f, opacity = 1f)
        val document = white()

        val erased = PaintStroke(LayerId(0), line(50f, 350f, 100f, settings), settings, erase = true).applyTo(document)
        assertEquals(0, Color.alpha(pixel(rasterOf(erased), 200, 100)))
        assertEquals(Color.WHITE, pixel(rasterOf(erased), 200, 130))

        // 빈 레이어를 지우면 바뀌는 것이 없다
        val withEmpty = AddLayer("Empty", 1).applyTo(document)
        assertSame(withEmpty, PaintStroke(LayerId(1), line(50f, 350f, 100f, settings), settings, erase = true).applyTo(withEmpty))
    }

    @Test
    fun brushOnAnEmptyLayer_createsOnlyTheTouchedTiles() {
        val withEmpty = AddLayer("Empty", 1).applyTo(white())
        val settings = BrushSettings(size = 10f, hardness = 1f, opacity = 1f, color = Color.BLUE)

        val painted = PaintStroke(LayerId(1), listOf(StrokePoint(20f, 20f)), settings, erase = false).applyTo(withEmpty)

        val raster = rasterOf(painted, 1)
        assertEquals(setOf(TileKey(0, 0)), raster.tiles.keys)
        assertEquals(Color.BLUE, pixel(raster, 20, 20))
        assertEquals(0, Color.alpha(pixel(raster, 40, 20)))
        // 메모리 어림은 닿는 타일 한 장이다
        assertEquals(TILE_SIZE * TILE_SIZE * 4L, PaintStroke(LayerId(1), listOf(StrokePoint(20f, 20f)), settings, erase = false).estimateNewBytes(withEmpty))
    }
}
