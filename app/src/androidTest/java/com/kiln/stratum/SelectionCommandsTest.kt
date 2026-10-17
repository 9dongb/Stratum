package com.kiln.stratum

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.canvas.CanvasFill
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.BlankCanvas
import com.kiln.stratum.engine.platform.CopySelectedToLayer
import com.kiln.stratum.engine.platform.DeleteSelected
import com.kiln.stratum.engine.platform.Deselect
import com.kiln.stratum.engine.platform.InvertSelection
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiveStroke
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.RotateImage
import com.kiln.stratum.engine.platform.SelectAll
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.platform.SelectSimilarColor
import com.kiln.stratum.engine.platform.SelectionPreview
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionPoint
import com.kiln.stratum.engine.selection.SelectionShape
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 선택 영역 만들기·합치기·자동 선택·지우기·복사, 선택 영역 안에만 그리는 브러시, 윤곽선을 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class SelectionCommandsTest {

    /** 흰 바탕 600×400(타일 3×2, 오른쪽 끝 타일은 88px) */
    private fun white(): Document = checkNotNull(BlankCanvas.create(600, 400, CanvasFill.Solid(Color.WHITE), "Background"))

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) = SelectionShape.Rectangle(left, top, right, bottom)

    private fun select(document: Document, shape: SelectionShape, mode: SelectionMode = SelectionMode.REPLACE, feather: Float = 0f) =
        SelectShape(shape, mode, feather).applyTo(document)

    private fun alpha(document: Document, x: Int, y: Int): Int = checkNotNull(document.selection).alphaAt(x, y)

    private fun rasterOf(document: Document, index: Int): Raster = (document.layers[index].content as RasterContent).raster

    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    @Test
    fun rectangle_selectsInside_withSharpEdges() {
        val selected = select(white(), rect(10f, 10f, 590f, 390f))
        val selection = checkNotNull(selected.selection)

        assertEquals(255, alpha(selected, 300, 200))
        assertEquals(255, alpha(selected, 10, 10))
        assertEquals(0, alpha(selected, 9, 200))
        assertEquals(0, alpha(selected, 590, 200))
        // 가운데 타일(256..512 × 0..256)은 위쪽 경계가 지나가므로 픽셀을 가진다. 문서 밖 자리는 없다.
        assertEquals(6, selection.tiles.size)
    }

    @Test
    fun wholeTileRectangle_isFullTilesOnly() {
        val selected = select(white(), rect(0f, 0f, 512f, 256f))

        val tiles = checkNotNull(selected.selection).tiles
        assertEquals(setOf(TileKey(0, 0), TileKey(1, 0)), tiles.keys)
        assertTrue(tiles.values.all { it === MaskTile.FULL })
    }

    @Test
    fun ellipse_isRound_andFeatherMakesSoftEdges_withoutTileSeams() {
        val round = select(white(), SelectionShape.Ellipse(100f, 50f, 500f, 350f))
        assertEquals(255, alpha(round, 300, 200))
        // 사각형 귀퉁이는 타원 밖이다
        assertEquals(0, alpha(round, 105, 55))

        val soft = select(white(), rect(100f, 100f, 400f, 300f), feather = 20f)
        // 경계에서 반쯤, 안으로 갈수록 짙다
        val edge = alpha(soft, 256, 100)
        assertTrue("edge $edge", edge in 90..165)
        assertTrue(alpha(soft, 256, 140) > 240)
        assertTrue(alpha(soft, 256, 70) < 20)
        // 타일 경계(x 255 | 256)에서 끊기지 않는다
        assertTrue(abs(alpha(soft, 255, 110) - alpha(soft, 256, 110)) <= 3)
    }

    @Test
    fun lasso_selectsTheDrawnShape() {
        val triangle = SelectionShape.Lasso(listOf(SelectionPoint(50f, 50f), SelectionPoint(550f, 50f), SelectionPoint(50f, 350f)))

        val selected = select(white(), triangle)

        assertEquals(255, alpha(selected, 100, 100))
        assertEquals(0, alpha(selected, 500, 300))
    }

    @Test
    fun modes_addSubtractIntersect() {
        val left = select(white(), rect(0f, 0f, 300f, 400f))

        val added = select(left, rect(300f, 0f, 600f, 200f), SelectionMode.ADD)
        assertEquals(255, alpha(added, 100, 300))
        assertEquals(255, alpha(added, 450, 100))
        assertEquals(0, alpha(added, 450, 300))

        val subtracted = select(left, rect(100f, 100f, 200f, 200f), SelectionMode.SUBTRACT)
        assertEquals(0, alpha(subtracted, 150, 150))
        assertEquals(255, alpha(subtracted, 50, 50))

        val intersected = select(left, rect(200f, 0f, 600f, 400f), SelectionMode.INTERSECT)
        assertEquals(255, alpha(intersected, 250, 200))
        assertEquals(0, alpha(intersected, 100, 200))
        assertEquals(0, alpha(intersected, 400, 200))
    }

    @Test
    fun emptyShape_deselects_andSubtractingEverythingLeavesNoSelection() {
        val selected = select(white(), rect(0f, 0f, 300f, 400f))

        assertNull(select(selected, rect(50f, 50f, 50f, 50f)).selection)
        assertNull(select(selected, rect(0f, 0f, 600f, 400f), SelectionMode.SUBTRACT).selection)
    }

    @Test
    fun selectAll_invert_deselect() {
        val all = SelectAll.applyTo(white())
        assertEquals(255, alpha(all, 599, 399))
        assertSame(all, SelectAll.applyTo(all))

        val inverted = InvertSelection.applyTo(select(white(), rect(0f, 0f, 300f, 400f)))
        assertEquals(0, alpha(inverted, 100, 100))
        assertEquals(255, alpha(inverted, 400, 100))

        assertNull(Deselect.applyTo(all).selection)
        // 선택 영역이 없으면 반전해도 바뀌지 않는다
        val document = white()
        assertSame(document, InvertSelection.applyTo(document))
    }

    @Test
    fun magicWand_selectsTheTouchedColor_contiguousOrEverywhere() {
        // 흰 바탕에 빨강 사각형 둘(떨어져 있음)
        var document = white()
        document = PaintStroke(LayerId(0), StrokeDabs.place(listOf(StrokePoint(100f, 100f), StrokePoint(150f, 100f)), 1f), BrushSettings(size = 60f, hardness = 1f, color = Color.RED), false).applyTo(document)
        document = PaintStroke(LayerId(0), StrokeDabs.place(listOf(StrokePoint(450f, 300f), StrokePoint(500f, 300f)), 1f), BrushSettings(size = 60f, hardness = 1f, color = Color.RED), false).applyTo(document)

        val near = SelectSimilarColor(LayerId(0), 120f, 100f, tolerance = 32, contiguous = true, mode = SelectionMode.REPLACE).applyTo(document)
        assertEquals(255, alpha(near, 125, 100))
        assertEquals(0, alpha(near, 475, 300))
        assertEquals(0, alpha(near, 300, 200))

        val everywhere = SelectSimilarColor(LayerId(0), 120f, 100f, tolerance = 32, contiguous = false, mode = SelectionMode.REPLACE).applyTo(document)
        assertEquals(255, alpha(everywhere, 475, 300))
        assertEquals(0, alpha(everywhere, 300, 200))

        // 흰 바탕을 누르면 바탕이 골라지고 빨강은 빠진다
        val background = SelectSimilarColor(LayerId(0), 300f, 200f, tolerance = 32, contiguous = true, mode = SelectionMode.REPLACE).applyTo(document)
        assertEquals(255, alpha(background, 10, 390))
        assertEquals(0, alpha(background, 125, 100))
    }

    @Test
    fun deleteSelected_clearsOnlyInside_exactlyElsewhere() {
        val document = select(white(), rect(100f, 100f, 300f, 300f), feather = 0f)

        val deleted = DeleteSelected(LayerId(0)).applyTo(document)

        val raster = rasterOf(deleted, 0)
        assertEquals(0, Color.alpha(pixel(raster, 200, 200)))
        assertEquals(Color.WHITE, pixel(raster, 50, 50))
        assertEquals(Color.WHITE, pixel(raster, 300, 200))
        // 선택 영역은 그대로다
        assertSame(document.selection, deleted.selection)
    }

    @Test
    fun copyToLayer_makesANewLayerAbove_andCutClearsTheSource() {
        val document = select(white(), rect(100f, 100f, 300f, 300f))

        val copied = CopySelectedToLayer(LayerId(0), "Copy", cut = false).applyTo(document)
        assertEquals(2, copied.layers.size)
        assertEquals("Copy", copied.layers[1].name)
        assertEquals(LayerId(1), copied.layers[1].id)
        val copy = rasterOf(copied, 1)
        assertEquals(Color.WHITE, pixel(copy, 200, 200))
        assertEquals(0, Color.alpha(pixel(copy, 50, 50)))
        assertEquals(Color.WHITE, pixel(rasterOf(copied, 0), 200, 200))
        // 끝나면 선택을 푼다
        assertNull(copied.selection)

        val cut = CopySelectedToLayer(LayerId(0), "Cut", cut = true).applyTo(document)
        assertEquals(0, Color.alpha(pixel(rasterOf(cut, 0), 200, 200)))
        assertEquals(Color.WHITE, pixel(rasterOf(cut, 1), 200, 200))
    }

    @Test
    fun brush_paintsOnlyInsideTheSelection_andLeavesTheRestExact() {
        val document = select(white(), rect(0f, 0f, 300f, 400f))
        val line = StrokeDabs.place(listOf(StrokePoint(100f, 200f), StrokePoint(500f, 200f)), 2f)

        val painted = PaintStroke(LayerId(0), line, BrushSettings(size = 40f, hardness = 1f, color = Color.BLUE), false).applyTo(document)

        val raster = rasterOf(painted, 0)
        assertEquals(Color.BLUE, pixel(raster, 200, 200))
        assertEquals(Color.WHITE, pixel(raster, 400, 200))
        // 고르지 않은 타일(오른쪽 열)은 앞 타일을 그대로 쓴다
        assertSame(rasterOf(document, 0).tiles[TileKey(2, 0)], raster.tiles[TileKey(2, 0)])
    }

    @Test
    fun liveStrokePreview_isClippedToo() {
        val document = select(white(), rect(0f, 0f, 300f, 400f))
        val line = StrokeDabs.place(listOf(StrokePoint(100f, 200f), StrokePoint(500f, 200f)), 2f)
        val stroke = LiveStroke(LayerId(0), line, BrushSettings(size = 40f, hardness = 1f, color = Color.BLUE), false, document.selection)

        val preview = checkNotNull(LayerPreviewCache().render(document, 0, listOf(stroke))).copy(android.graphics.Bitmap.Config.ARGB_8888, false)

        assertEquals(Color.BLUE, preview.getPixel(200, 200))
        assertEquals(Color.WHITE, preview.getPixel(400, 200))
    }

    @Test
    fun transforms_dropTheSelection() {
        val document = select(white(), rect(0f, 0f, 300f, 400f))

        assertNull(RotateImage(clockwise = true).applyTo(document).selection)
    }

    @Test
    fun outline_followsTheRectangleEdges() {
        val size = PixelSize(600, 400)
        val selection = checkNotNull(select(white(), rect(100f, 100f, 300f, 300f)).selection)

        val lines = SelectionPreview.outline(selection, PreviewLevels.wholeRegion(size, 0))

        assertEquals(1, lines.size)
        val xs = lines.single().filterIndexed { i, _ -> i % 2 == 0 }
        val ys = lines.single().filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(100f, xs.min(), 1f)
        assertEquals(300f, xs.max(), 1f)
        assertEquals(100f, ys.min(), 1f)
        assertEquals(300f, ys.max(), 1f)
    }

    @Test
    fun outline_ofAZoomedRegion_isInDocumentCoordinates() {
        val size = PixelSize(600, 400)
        val selection = checkNotNull(select(white(), rect(100f, 100f, 300f, 300f)).selection)
        val region = checkNotNull(PreviewLevels.region(size, 1, com.kiln.stratum.engine.raster.PixelRect(0, 0, 512, 400)))

        val lines = SelectionPreview.outline(selection, region)

        val xs = lines.flatMap { line -> line.filterIndexed { i, _ -> i % 2 == 0 } }
        assertEquals(100f, xs.min(), 2f)
        assertEquals(300f, xs.max(), 2f)
        assertNotNull(lines.firstOrNull())
    }

    @Test
    fun selectionTiles_fromHeap_canBeUsedForStrokes() {
        // 테스트용 힙 타일(문서 왼쪽 위 타일의 왼쪽 절반만 고름)
        val half = MaskTile.of(ByteArray(TILE_SIZE * TILE_SIZE) { i -> if (i % TILE_SIZE < 128) -1 else 0 })
        val document = white().withSelection(Selection.of(mapOf(TileKey(0, 0) to half)))
        val line = StrokeDabs.place(listOf(StrokePoint(20f, 100f), StrokePoint(240f, 100f)), 2f)

        val painted = PaintStroke(LayerId(0), line, BrushSettings(size = 20f, hardness = 1f, color = Color.BLUE), false).applyTo(document)

        val raster = rasterOf(painted, 0)
        assertEquals(Color.BLUE, pixel(raster, 60, 100))
        assertEquals(Color.WHITE, pixel(raster, 200, 100))
    }
}
