package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.adjust.Adjustments
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.document.AdjustmentContent
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.Layer
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.effect.EffectFill
import com.kiln.stratum.engine.effect.EffectKind
import com.kiln.stratum.engine.inpaint.InpaintArea
import com.kiln.stratum.engine.liquify.LiquifyMesh
import com.kiln.stratum.engine.liquify.LiquifyTool
import com.kiln.stratum.engine.platform.AddLayerMask
import com.kiln.stratum.engine.platform.CopySelectedToLayer
import com.kiln.stratum.engine.platform.DeleteSelected
import com.kiln.stratum.engine.platform.EffectStroke
import com.kiln.stratum.engine.platform.FlipImage
import com.kiln.stratum.engine.platform.InpaintRegion
import com.kiln.stratum.engine.platform.InvertLayerMask
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LayerShift
import com.kiln.stratum.engine.platform.LiquifyLayer
import com.kiln.stratum.engine.platform.MergeDown
import com.kiln.stratum.engine.platform.MergeVisible
import com.kiln.stratum.engine.platform.PaintLayerMask
import com.kiln.stratum.engine.platform.PaintStroke
import com.kiln.stratum.engine.platform.PreviewRenderer
import com.kiln.stratum.engine.platform.ResizeImage
import com.kiln.stratum.engine.platform.RetouchStroke
import com.kiln.stratum.engine.platform.SmudgeStroke
import com.kiln.stratum.engine.platform.ToneStroke
import com.kiln.stratum.engine.platform.TranslateLayer
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import com.kiln.stratum.engine.tone.ToneFill
import com.kiln.stratum.engine.transform.MaskTransform
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 이동 도구가 캔버스 밖으로 밀어낸 픽셀(결정 기록 '캔버스 밖 픽셀', 사용자 피드백 13차 1)이 다른 편집 뒤에도 남는지 본다.
 * 600×300 캔버스(끝 열은 캔버스 안쪽이 88px, 끝 줄은 44px)에 끝 타일이 캔버스 밖까지 자홍색을 담고, 캔버스 밖 자리에도 자홍 타일이 있다.
 * 편집은 캔버스 안쪽만 바꾸고 캔버스 밖 픽셀·마스크·게인맵은 그대로여야 한다. 복사는 캔버스 안 픽셀만 가져간다(포토샵과 같다).
 */
@RunWith(AndroidJUnit4::class)
class OffCanvasSurvivalTest {

    private val size = PixelSize(600, 300)
    private val grid = TileGrid(600, 300)
    private val gray = Color.rgb(120, 120, 120)
    private val magenta = Color.MAGENTA
    private val outside = listOf(TileKey(-1, 0), TileKey(3, 1))

    /** 캔버스 안쪽 [width]×[height]는 회색, 그 밖(타일이 담은 나머지)은 자홍인 꽉 찬 타일 */
    private fun edgeTile(width: Int, height: Int): Tile =
        Tile.of(IntArray(TILE_PIXEL_COUNT) { i -> if (i % TILE_SIZE < width && i / TILE_SIZE < height) gray else magenta })

    private fun layerRaster(): Raster {
        val tiles = HashMap<TileKey, Tile?>()
        for (key in grid.keys()) {
            val inside = grid.bounds(key)
            tiles[key] = if (inside.width == TILE_SIZE && inside.height == TILE_SIZE) Tile.solid(gray) else edgeTile(inside.width, inside.height)
        }
        for (key in outside) tiles[key] = Tile.solid(magenta)
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 게인맵(문서의 1/4, 150×75): 캔버스 안쪽은 회색, 끝 타일이 담은 나머지와 캔버스 밖 자리는 자홍 */
    private fun gainmap(): HdrGainmap =
        HdrGainmap(150, 75, Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to edgeTile(150, 75), TileKey(1, 0) to Tile.solid(magenta))))

    private fun document(): Document {
        val base = Document.create(size.width, size.height, "Background", layerRaster())
        return base.copy(layers = listOf(base.layers[0].copy(gainmap = gainmap())))
    }

    private fun Document.raster(index: Int = 0): Raster = (layers[index].content as RasterContent).raster

    /** 오른쪽·아래 끝을 따라가는 획(문서 좌표) */
    private val edgeStroke: List<StrokePoint> =
        (40..290 step 10).map { StrokePoint(580f, it.toFloat()) } + (570 downTo 40 step 10).map { StrokePoint(it.toFloat(), 288f) }

    private val brush = BrushSettings(size = 40f, hardness = 1f, color = Color.BLUE)

    /** 끝 타일이 담은 캔버스 밖 픽셀 몇 곳(타일 안 좌표)이 자홍이고, 캔버스 밖 자리 타일은 같은 객체인지 */
    private fun assertOffCanvasKept(before: Document, after: Document, what: String) {
        val raster = after.raster()
        for (key in outside) assertSame("$what $key", before.raster().tiles[key], raster.tiles[key])
        val samples = mapOf(
            TileKey(2, 0) to listOf(100 to 10, 255 to 128),
            TileKey(0, 1) to listOf(10 to 100, 200 to 255),
            TileKey(1, 1) to listOf(128 to 60),
            TileKey(2, 1) to listOf(100 to 10, 10 to 100, 255 to 255)
        )
        for ((key, points) in samples) {
            val tile = checkNotNull(raster.tiles[key]) { "$what $key" }
            assertEquals("$what $key size", TILE_SIZE, tile.width)
            assertEquals("$what $key size", TILE_SIZE, tile.height)
            for ((x, y) in points) assertEquals("$what $key ($x, $y)", magenta, tile.pixelAt(x, y))
        }
        // 게인맵도 같다
        val gainmap = checkNotNull(after.layers[0].gainmap) { what }.raster
        assertSame("$what gainmap outside", before.layers[0].gainmap!!.raster.tiles[TileKey(1, 0)], gainmap.tiles[TileKey(1, 0)])
        val gainTile = checkNotNull(gainmap.tiles[TileKey(0, 0)])
        for ((x, y) in listOf(200 to 10, 10 to 200, 255 to 255)) assertEquals("$what gainmap ($x, $y)", magenta, gainTile.pixelAt(x, y))
    }

    /** 캔버스 안쪽 오른쪽 끝(580, 150)의 픽셀이 바뀌었는지(편집이 실제로 걸렸는지) */
    private fun assertChangedInside(before: Document, after: Document, what: String) {
        val key = TileKey(2, 0)
        val was = before.raster().tiles.getValue(key).pixelAt(580 - 512, 150)
        val now = after.raster().tiles.getValue(key).pixelAt(580 - 512, 150)
        assertNotEquals("$what should change the canvas inside", was, now)
    }

    @Test
    fun strokes_keepPixelsPastTheCanvasEdge() {
        val document = document()
        // 테두리 바깥쪽으로 칠할 것이 있게 회색 위에 빨간 점을 하나 찍어 둔다(흐림·스머지가 바꿀 무늬)
        val dotted = PaintStroke(LayerId(0), listOf(StrokePoint(585f, 150f)), brush.copy(size = 12f, color = Color.RED), erase = false).applyTo(document)
        assertOffCanvasKept(document, dotted, "dot")
        val edits = listOf(
            "brush" to PaintStroke(LayerId(0), edgeStroke, brush, erase = false),
            "eraser" to PaintStroke(LayerId(0), edgeStroke, brush, erase = true),
            "blur" to EffectStroke(LayerId(0), edgeStroke, brush, EffectFill(EffectKind.BLUR, strength = 1f)),
            "sharpen" to EffectStroke(LayerId(0), edgeStroke, brush, EffectFill(EffectKind.SHARPEN, strength = 1f)),
            "mosaic" to EffectStroke(LayerId(0), edgeStroke, brush, EffectFill(EffectKind.MOSAIC, cell = 16)),
            "smudge" to SmudgeStroke(LayerId(0), edgeStroke, brush, 0.9f),
            "dodge" to ToneStroke(LayerId(0), edgeStroke, brush, ToneFill(dodge = true, exposure = 1f)),
            "clone" to RetouchStroke(LayerId(0), edgeStroke, brush, RetouchMode.CLONE, SourceOffset(300, 0)),
            "heal" to RetouchStroke(LayerId(0), edgeStroke, brush, RetouchMode.HEAL, SourceOffset(300, 0)),
            "inpaint" to InpaintRegion(LayerId(0), InpaintArea.Painted(edgeStroke, 20f), FakeInpaintModel())
        )
        for ((what, command) in edits) {
            val after = command.applyTo(dotted)
            assertOffCanvasKept(dotted, after, what)
        }
        // 칠하기·지우기는 캔버스 안쪽을 실제로 바꿨다
        assertChangedInside(dotted, PaintStroke(LayerId(0), edgeStroke, brush, erase = false).applyTo(dotted), "brush")
        assertChangedInside(dotted, PaintStroke(LayerId(0), edgeStroke, brush, erase = true).applyTo(dotted), "eraser")
    }

    @Test
    fun liquify_keepsPixelsPastTheCanvasEdge() {
        val document = document()
        val mesh = LiquifyMesh.identity(size)
        repeat(6) { step -> mesh.brush(LiquifyTool.PUSH, 540f + step * 5f, 150f, radius = 60f, strength = 1f, moveX = 5f, moveY = 0f) }

        val warped = LiquifyLayer(LayerId(0), mesh).applyTo(document)

        assertOffCanvasKept(document, warped, "liquify")
        // 기록의 원래 픽셀도 그대로다
        assertSame(document.raster(), warped.layers[0].liquify?.base)
    }

    @Test
    fun deleteAndCut_leaveOffCanvasPixels_andCopyTakesTheCanvasOnly() {
        val selected = document().copy(selection = Selection.all(size))

        val deleted = DeleteSelected(LayerId(0)).applyTo(selected)
        assertOffCanvasKept(selected, deleted, "delete")
        // 캔버스 안쪽은 지워졌다(안쪽 자리는 비고, 끝 타일은 캔버스 안쪽만 투명)
        assertFalse(TileKey(0, 0) in deleted.raster().tiles)
        assertEquals(Color.TRANSPARENT, deleted.raster().tiles.getValue(TileKey(2, 0)).pixelAt(10, 10))

        val copied = CopySelectedToLayer(LayerId(0), "Copy", cut = true).applyTo(selected)
        assertOffCanvasKept(selected, copied, "cut")
        val copy = copied.raster(1)
        // 복사한 레이어는 캔버스 안 픽셀만이다(캔버스 밖 자리 없음, 끝 타일은 캔버스 안쪽 크기)
        assertTrue(copy.tiles.keys.all { grid.contains(it) })
        assertEquals(88, copy.tiles.getValue(TileKey(2, 0)).width)
        assertEquals(44, copy.tiles.getValue(TileKey(2, 1)).height)
        assertEquals(gray, copy.tiles.getValue(TileKey(2, 0)).pixelAt(10, 10))
    }

    @Test
    fun masks_coverOffCanvasPixels_andMaskEditsKeepThem() {
        val document = document()

        // 다 보이는 마스크는 캔버스 밖 픽셀 자리도 덮는다
        val masked = AddLayerMask(LayerId(0)).applyTo(document)
        val mask = checkNotNull(masked.layers[0].mask)
        for (key in outside) assertSame(MaskTile.FULL, mask.tiles[key])

        // 마스크를 칠해도(가리기) 끝 자리의 캔버스 밖 알파는 그대로 보인다
        val painted = checkNotNull(PaintLayerMask(LayerId(0), edgeStroke, brush, reveal = false).applyTo(masked).layers[0].mask)
        val edge = checkNotNull(painted.tiles[TileKey(2, 0)])
        assertEquals(TILE_SIZE, edge.width)
        assertEquals(255, edge.alphaAt(100, 10))
        assertTrue(edge.alphaAt(580 - 512, 150) < 255)
        for (key in outside) assertSame(MaskTile.FULL, painted.tiles[key])

        // 끝 자리에서 캔버스 밖을 가린 마스크를 뒤집으면 캔버스 밖 픽셀이 보인다. 마스크가 없던 캔버스 밖 자리도 보인다.
        val partial = MaskTile.of(ByteArray(TILE_PIXEL_COUNT) { i -> (if (i % TILE_SIZE < 88) 100 else 0).toByte() })
        val hiding = Selection.of(grid.keys().associateWith { if (it == TileKey(2, 0)) partial else MaskTile.FULL })
        val withHiding = document.copy(layers = listOf(document.layers[0].copy(mask = hiding)))
        val inverted = checkNotNull(InvertLayerMask(LayerId(0)).applyTo(withHiding).layers[0].mask)
        val flipped = checkNotNull(inverted.tiles[TileKey(2, 0)])
        assertEquals(155, flipped.alphaAt(10, 10))
        assertEquals(255, flipped.alphaAt(100, 10))
        for (key in outside) assertSame(MaskTile.FULL, inverted.tiles[key])
        assertFalse(TileKey(0, 0) in inverted.tiles)
    }

    private fun near(expected: Int, actual: Int, tolerance: Int = 2): Boolean =
        abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance &&
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance

    private fun hex(color: Int) = Integer.toHexString(color)

    @Test
    fun mergeDown_keepsPixelsPastTheCanvasEdge_andAppliesTheUpperMask() {
        val lower = document()
        // 위 레이어(불투명도 50%): 끝 자리(2, 0)의 캔버스 안쪽과 캔버스 밖 자리 둘에 파랑. 마스크는 캔버스 밖 (3, 1)을 가린다.
        val blue = Raster.EMPTY.withTiles(
            mapOf(
                TileKey(2, 0) to Tile.solid(Color.BLUE).cropped(88, TILE_SIZE),
                TileKey(-1, 0) to Tile.solid(Color.BLUE),
                TileKey(3, 1) to Tile.solid(Color.BLUE)
            )
        )
        val mask = Selection.of((grid.keys() + TileKey(-1, 0)).associateWith { MaskTile.FULL })
        val upper = Layer(LayerId(1), "Blue", RasterContent(blue), opacity = 0.5f, mask = mask)
        val document = lower.copy(layers = lower.layers + upper, nextLayerId = 2)

        val raster = MergeDown(LayerId(1)).applyTo(document).raster()

        // 마스크가 가린 캔버스 밖 자리는 아래 타일 그대로다
        assertSame(document.raster().tiles[TileKey(3, 1)], raster.tiles[TileKey(3, 1)])
        // 둘 다 있는 캔버스 밖 자리는 반반 섞였다
        val outsideMix = raster.tiles.getValue(TileKey(-1, 0)).pixelAt(100, 100)
        assertTrue(hex(outsideMix), near(Color.rgb(128, 0, 255), outsideMix))
        // 끝 자리: 캔버스 안쪽은 섞이고, 캔버스 밖으로 담은 자홍은 그대로다
        val edge = raster.tiles.getValue(TileKey(2, 0))
        assertEquals(TILE_SIZE, edge.width)
        assertEquals(TILE_SIZE, edge.height)
        assertTrue(hex(edge.pixelAt(10, 10)), near(Color.rgb(60, 60, 188), edge.pixelAt(10, 10)))
        for ((x, y) in listOf(100 to 10, 255 to 255)) assertTrue("($x, $y) ${hex(edge.pixelAt(x, y))}", near(magenta, edge.pixelAt(x, y), 1))
    }

    @Test
    fun mergingAnAdjustmentLayer_appliesItPastTheCanvasEdge_unlessItsMaskHidesTheBorder() {
        val base = document()
        val adjustment = Layer(LayerId(1), "Adjust", AdjustmentContent.BASIC, adjustments = Adjustments(saturation = -0.6f))
        // 같은 조정을 캔버스 안의 자홍에 건 색
        val reference = Document.create(256, 256, "M", Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to Tile.solid(magenta))))
        val expected = MergeVisible.applyTo(reference.copy(layers = reference.layers + adjustment, nextLayerId = 2))
            .raster().tiles.getValue(TileKey(0, 0)).pixelAt(100, 100)
        assertNotEquals(magenta, expected)

        val raster = MergeVisible.applyTo(base.copy(layers = base.layers + adjustment, nextLayerId = 2)).raster()

        val samples = listOf(Triple(TileKey(-1, 0), 100, 100), Triple(TileKey(3, 1), 10, 10), Triple(TileKey(2, 0), 100, 10), Triple(TileKey(0, 1), 10, 100))
        for ((key, x, y) in samples) {
            val pixel = raster.tiles.getValue(key).pixelAt(x, y)
            assertTrue("$key ($x, $y) ${hex(pixel)} != ${hex(expected)}", near(expected, pixel, 1))
        }

        // 가장자리를 가린 마스크(선택 영역 모양)의 조정은 캔버스 밖에 걸리지 않는다
        val inner = Selection.of(mapOf(TileKey(1, 0) to MaskTile.FULL))
        val masked = MergeVisible.applyTo(base.copy(layers = base.layers + adjustment.copy(mask = inner), nextLayerId = 2)).raster()
        val untouched = masked.tiles.getValue(TileKey(-1, 0)).pixelAt(100, 100)
        assertTrue(hex(untouched), near(magenta, untouched, 1))
    }

    @Test
    fun resize_scalesPixelsPastTheCanvasEdge_withTheirMask() {
        val document = AddLayerMask(LayerId(0)).applyTo(document())

        val resized = ResizeImage(300, 150).applyTo(document)

        val raster = resized.raster()
        // 캔버스 밖 자리(-1, 0)의 자홍은 반으로 줄어 (-128..0, 0..128)에 있다
        val outsideTile = checkNotNull(raster.tiles[TileKey(-1, 0)])
        assertEquals(TILE_SIZE, outsideTile.width)
        assertTrue(hex(outsideTile.pixelAt(200, 50)), near(magenta, outsideTile.pixelAt(200, 50), 1))
        assertEquals(Color.TRANSPARENT, outsideTile.pixelAt(50, 50))
        // 끝 자리(2, 0)가 캔버스 밖으로 담은 자홍(600..768)은 300..384가 된다(대상 끝 자리 (1, 0)의 캔버스 밖)
        val edge = checkNotNull(raster.tiles[TileKey(1, 0)])
        assertEquals(TILE_SIZE, edge.width)
        assertTrue(hex(edge.pixelAt(350 - 256, 20)), near(magenta, edge.pixelAt(350 - 256, 20), 1))
        assertTrue(hex(edge.pixelAt(10, 20)), near(gray, edge.pixelAt(10, 20), 1))
        // 다 보이는 마스크도 그 픽셀 자리까지 따라 바뀌어 보인다
        val mask = checkNotNull(resized.layers[0].mask)
        assertEquals(255, checkNotNull(mask.tiles[TileKey(-1, 0)]).alphaAt(200, 50))
        assertEquals(255, checkNotNull(mask.tiles[TileKey(1, 0)]).alphaAt(350 - 256, 20))
    }
    /** [size] 캔버스를 꼭 덮는 회색 사진 레이어(끝 타일은 캔버스 안쪽 크기) */
    private fun photoOf(size: PixelSize): Raster {
        val photoGrid = TileGrid(size.width, size.height)
        return Raster.EMPTY.withTiles(photoGrid.keys().associateWith { key -> photoGrid.bounds(key).let { Tile.solid(gray, it.width, it.height) } })
    }

    /** 1000×800 다 보이는 마스크에서 안쪽 (1, 1) 타일만 다 가린 것(타일 없음, 문서 256..512 × 256..512) */
    private val holeSize = PixelSize(1000, 800)
    private val withHole: Selection by lazy { Selection.of(Selection.all(holeSize).tiles - TileKey(1, 1)) }

    @Test
    fun hiddenMaskArea_staysHidden_afterMovingTheLayerOffTheCanvasAndBack() {
        // 검토에서 고침: 가린 곳을 캔버스 밖으로 밀었다 되들이면 다시 보였다(캔버스 밖에서 그 자리를 모르는 곳으로 봄)
        val base = Document.create(holeSize.width, holeSize.height, "Photo", photoOf(holeSize))
        val document = base.copy(layers = listOf(base.layers[0].copy(mask = withHole)))

        val out = TranslateLayer(LayerId(0), -512, 0).applyTo(document)
        assertEquals(0, checkNotNull(out.layers[0].mask).alphaAt(-100, 300))
        val back = checkNotNull(TranslateLayer(LayerId(0), 512, 0).applyTo(out).layers[0].mask)

        for ((x, y) in listOf(300 to 300, 500 to 500, 260 to 260)) assertEquals("($x, $y)", 0, back.alphaAt(x, y))
        for ((x, y) in listOf(100 to 300, 600 to 300, 300 to 100, 990 to 790, 5 to 5)) assertEquals("($x, $y)", 255, back.alphaAt(x, y))
    }

    @Test
    fun flippedRevealAllMask_thenMoved_revealsTheEmptiedStrip() {
        // 검토에서 고침: 끝 자리 다 보이는 타일이 캔버스 밖까지 담은 몫이 뒤집기로 반대편 캔버스 밖에 0이 섞인 타일로 남아, 그 뒤 옮기면 빈 띠가 가려졌다
        val base = Document.create(holeSize.width, holeSize.height, "Photo", photoOf(holeSize))
        val document = AddLayerMask(LayerId(0)).applyTo(base)

        val flipped = FlipImage(horizontal = true).applyTo(document)
        assertTrue(checkNotNull(flipped.layers[0].mask).tiles.keys.all { TileGrid(holeSize.width, holeSize.height).contains(it) })
        val moved = checkNotNull(TranslateLayer(LayerId(0), 100, 0).applyTo(flipped).layers[0].mask)

        for ((x, y) in listOf(0 to 300, 50 to 10, 99 to 799, 500 to 400)) assertEquals("($x, $y)", 255, moved.alphaAt(x, y))
    }

    @Test
    fun adjustmentMask_keepsAHiddenAreaHiddenPastTheCanvas_whenMovedMergedAndResized() {
        // 조정 레이어의 다 보이는 마스크(가장자리가 다 보여 끝이 없음)에서 가린 곳을 캔버스 밖으로 밀면 0인 타일로 남는다(검토에서 고침)
        val photo = Raster.EMPTY.withTiles(photoOf(holeSize).tiles + listOf(TileKey(-2, 1), TileKey(-1, 1)).associateWith { Tile.solid(gray) })
        val adjustment = Layer(LayerId(1), "Adjust", AdjustmentContent.BASIC, adjustments = Adjustments(exposure = 1f), mask = withHole)
        val document = Document(holeSize.width, holeSize.height, listOf(Layer(LayerId(0), "Photo", RasterContent(photo)), adjustment), nextLayerId = 2)

        // 왼쪽으로 700 밀면 가린 곳(256..512)은 캔버스 밖 -444..-188로 나간다
        val pushed = TranslateLayer(LayerId(1), -700, 0).applyTo(document)
        val pushedMask = checkNotNull(pushed.layers[1].mask)
        assertEquals(0, pushedMask.alphaAt(-300, 300))
        assertEquals(255, pushedMask.alphaAt(-100, 300))
        assertTrue(MaskTransform.revealsOutside(pushedMask, holeSize))

        // 병합하면 캔버스 밖의 가린 곳에는 조정이 걸리지 않고, 그 밖의 캔버스 밖 픽셀에는 걸린다(그릴 때 0인 타일이 보임을 덮어씀)
        val merged = MergeVisible.applyTo(pushed).raster()
        val hidden = merged.tiles.getValue(TileKey(-2, 1)).pixelAt(212, 44)
        val adjusted = merged.tiles.getValue(TileKey(-1, 1)).pixelAt(156, 44)
        assertTrue(hex(hidden), near(gray, hidden, 1))
        assertTrue(hex(adjusted), !near(gray, adjusted, 8))

        // 크기를 반으로 바꿔도 끝이 없는 마스크 그대로(가장자리 다 보임)이고, 가린 곳도 반으로 줄어 남는다(-222..-94)
        val resized = ResizeImage(500, 400).applyTo(pushed)
        val half = checkNotNull(resized.layers[1].mask)
        assertTrue(MaskTransform.revealsOutside(half, PixelSize(500, 400)))
        assertEquals(0, half.alphaAt(-158, 192))
        assertEquals(255, half.alphaAt(-40, 192))
        // 되들이면 가린 곳이 캔버스 안(128..256)으로 돌아온다
        val back = checkNotNull(TranslateLayer(LayerId(1), 350, 0).applyTo(resized).layers[1].mask)
        assertEquals(0, back.alphaAt(192, 192))
        for ((x, y) in listOf(50 to 192, 400 to 192, 192 to 50, 192 to 350)) assertEquals("($x, $y)", 255, back.alphaAt(x, y))
    }
    @Test
    fun draggingAnAdjustmentBack_showsItsHiddenAreaFromPastTheCanvas_beforeLettingGo() {
        // 조정 레이어의 끝이 없는 마스크에서 가린 곳(256..512)을 캔버스 밖으로 밀었다가 되들이는 동안의 미리 보기는 놓은 뒤와 같다(검토에서 고침:
        // 캔버스 안 마스크 그림만 옮겨, 들어오는 가린 곳이 놓기 전까지 조정이 걸려 보였다)
        val adjustment = Layer(LayerId(1), "Adjust", AdjustmentContent.BASIC, adjustments = Adjustments(exposure = 1f), mask = withHole)
        val document = Document(holeSize.width, holeSize.height, listOf(Layer(LayerId(0), "Photo", RasterContent(photoOf(holeSize))), adjustment), nextLayerId = 2)
        val pushed = TranslateLayer(LayerId(1), -512, 0).applyTo(document)

        val preview = checkNotNull(LayerPreviewCache().render(pushed, level = 0, shift = LayerShift(LayerId(1), 512, 0))).copy(Bitmap.Config.ARGB_8888, false)
        val back = TranslateLayer(LayerId(1), 512, 0).applyTo(pushed)
        val committed = checkNotNull(PreviewRenderer.render(back, level = 0)).copy(Bitmap.Config.ARGB_8888, false)

        for ((x, y) in listOf(300 to 300, 500 to 500, 100 to 300, 700 to 300, 300 to 100, 900 to 700)) {
            assertTrue("($x, $y) ${hex(preview.getPixel(x, y))} vs ${hex(committed.getPixel(x, y))}", near(committed.getPixel(x, y), preview.getPixel(x, y), 2))
        }
        // 가린 곳은 조정이 걸리지 않은 회색이다
        assertTrue(hex(preview.getPixel(300, 300)), near(gray, preview.getPixel(300, 300), 2))
        assertTrue(hex(preview.getPixel(100, 300)), !near(gray, preview.getPixel(100, 300), 8))
    }
}
