package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.effect.EffectFill
import com.kiln.stratum.engine.effect.EffectKind
import com.kiln.stratum.engine.platform.EffectStroke
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiveStroke
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 흐림·선명·모자이크 획을 원본 해상도에 그리는지(이음매·칸·선택 영역·알파·게인맵), 끄는 동안의 미리 보기를 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class EffectStrokeTest {

    private fun gray(level: Int) = Color.rgb(level, level, level)

    /** 픽셀마다 [color](문서 x, y) 값인 [width]×[height] 문서(타일을 직접 채운다). [color]가 0이면 그 타일은 비운다. */
    private fun document(width: Int, height: Int, color: (Int, Int) -> Int): Document {
        val tiles = HashMap<TileKey, Tile?>()
        for (row in 0 until (height + TILE_SIZE - 1) / TILE_SIZE) {
            for (column in 0 until (width + TILE_SIZE - 1) / TILE_SIZE) {
                val pixels = IntArray(TILE_PIXEL_COUNT) { i -> color(column * TILE_SIZE + i % TILE_SIZE, row * TILE_SIZE + i / TILE_SIZE) }
                if (pixels.any { it != 0 }) tiles[TileKey(column, row)] = Tile.of(pixels)
            }
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    /** 왼쪽은 [left], x = [edge]부터 오른쪽은 [right]인 문서 */
    private fun split(width: Int, height: Int, edge: Int, left: Int = Color.BLACK, right: Int = Color.WHITE): Document =
        document(width, height) { x, _ -> if (x < edge) left else right }

    private fun rasterOf(document: Document, index: Int = 0): Raster = (document.layers[index].content as RasterContent).raster

    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    /** 세로 획: x = [x]에서 y [top]..[bottom] */
    private fun vertical(x: Float, top: Float, bottom: Float): List<StrokePoint> =
        StrokeDabs.place(listOf(StrokePoint(x, top), StrokePoint(x, bottom)), 4f)

    private val hard = BrushSettings(size = 100f, hardness = 1f)

    @Test
    fun blur_softensTheEdgeUnderTheStroke_andLeavesTheRestAsItWas() {
        val document = split(768, 512, edge = 256)

        val blurred = rasterOf(EffectStroke(LayerId(0), vertical(256f, 100f, 400f), hard, EffectFill(EffectKind.BLUR, strength = 1f)).applyTo(document))

        // 경계 양옆이 회색이 된다(σ 10px)
        val left = Color.red(pixel(blurred, 254, 250))
        val right = Color.red(pixel(blurred, 258, 250))
        assertTrue("left $left right $right", left in 60..140 && right in 115..200 && left < right)
        // 경계에서 먼 곳(3σ보다 멀리)은 그대로에 가깝다
        assertTrue(Color.red(pixel(blurred, 220, 250)) < 3)
        // 획 밖(아래)은 값이 그대로다
        assertEquals(Color.BLACK, pixel(blurred, 250, 480))
        assertEquals(Color.WHITE, pixel(blurred, 262, 480))
    }

    @Test
    fun blur_hasNoSeams_whereTheWorkIsSplitIntoBlocks() {
        // 경계가 묶음(1024px) 경계에 있는 문서와, 한 타일 옮겨 묶음 안에 있는 문서는 같은 자리끼리 결과가 같다
        val fill = EffectFill(EffectKind.BLUR, strength = 1f)
        val onBlockEdge = rasterOf(EffectStroke(LayerId(0), vertical(1024f, 100f, 400f), hard, fill).applyTo(split(2048, 512, edge = 1024)))
        val inBlock = rasterOf(EffectStroke(LayerId(0), vertical(768f, 100f, 400f), hard, fill).applyTo(split(2048, 512, edge = 768)))

        for (dx in -40..40 step 4) {
            for (y in listOf(150, 250, 350)) {
                val a = Color.red(pixel(onBlockEdge, 1024 + dx, y))
                val b = Color.red(pixel(inBlock, 768 + dx, y))
                assertTrue("dx $dx y $y: $a vs $b", abs(a - b) <= 1)
            }
        }
    }

    @Test
    fun mosaic_fillsEachCellOfTheDocumentGridWithItsAverage() {
        // 가로로 밝아지는 그림(빨강 = x / 2, 초록 = y / 2)
        val document = document(512, 512) { x, y -> Color.rgb(x / 2, y / 2, 0) }
        val brush = BrushSettings(size = 400f, hardness = 1f)

        val mosaic = rasterOf(EffectStroke(LayerId(0), listOf(StrokePoint(256f, 256f)), brush, EffectFill(EffectKind.MOSAIC, cell = 32)).applyTo(document))

        // 칸 (192..223, 192..223)은 한 가지 색이고, 그 칸의 평균이다(빨강 (96 + 111) / 2 = 103.5)
        val cell = pixel(mosaic, 192, 192)
        for (x in 192..223 step 5) for (y in 192..223 step 5) assertEquals(cell, pixel(mosaic, x, y))
        assertTrue("${Color.red(cell)}", abs(Color.red(cell) - 103.5f) <= 1f && abs(Color.green(cell) - 103.5f) <= 1f)
        // 칸 경계(문서 (0, 0)에서 32px마다)에서 바뀐다
        assertTrue(pixel(mosaic, 224, 200) != cell && pixel(mosaic, 191, 200) != cell)
        // 획 밖(구석)은 그대로다
        assertEquals(Color.rgb(1, 1, 0), pixel(mosaic, 2, 2))
    }

    @Test
    fun sharpen_raisesContrastAtAnEdge_andKeepsAlpha() {
        val opaque = split(512, 256, edge = 256, left = gray(100), right = gray(160))
        val sharpened = rasterOf(EffectStroke(LayerId(0), vertical(256f, 0f, 256f), hard, EffectFill(EffectKind.SHARPEN, strength = 1f)).applyTo(opaque))

        assertTrue(Color.red(pixel(sharpened, 255, 128)) < 100)
        assertTrue(Color.red(pixel(sharpened, 256, 128)) > 160)
        assertEquals(255, Color.alpha(pixel(sharpened, 255, 128)))
        // 반투명한 레이어도 알파는 그대로다
        val half = split(512, 256, edge = 256, left = Color.argb(128, 100, 100, 100), right = Color.argb(128, 160, 160, 160))
        val sharpenedHalf = rasterOf(EffectStroke(LayerId(0), vertical(256f, 0f, 256f), hard, EffectFill(EffectKind.SHARPEN, strength = 1f)).applyTo(half))
        assertTrue(abs(Color.alpha(pixel(sharpenedHalf, 255, 128)) - 128) <= 1)
        assertTrue(abs(Color.alpha(pixel(sharpenedHalf, 256, 128)) - 128) <= 1)
    }

    @Test
    fun blur_spreadsIntoTransparentPlaces_andAnOpaqueEdgeBecomesSoft() {
        // 오른쪽 타일이 비어 있는(투명) 레이어
        val document = document(512, 256) { x, _ -> if (x < 256) Color.BLACK else 0 }

        val blurred = rasterOf(EffectStroke(LayerId(0), vertical(256f, 0f, 256f), hard, EffectFill(EffectKind.BLUR, strength = 1f)).applyTo(document))

        // 투명한 쪽으로 번지고(빈 자리였던 타일이 생김), 불투명한 쪽 끝은 반투명해진다(위에 얹지 않고 바꾼다)
        assertTrue(Color.alpha(pixel(blurred, 258, 128)) in 40..140)
        assertTrue(Color.alpha(pixel(blurred, 253, 128)) in 120..230)
    }

    @Test
    fun effects_stayInsideTheSelection_andShareUntouchedTiles() {
        val document = SelectShape(SelectionShape.Rectangle(0f, 0f, 256f, 512f), SelectionMode.REPLACE).applyTo(split(768, 512, edge = 256))

        val blurred = EffectStroke(LayerId(0), vertical(256f, 100f, 400f), hard, EffectFill(EffectKind.BLUR, strength = 1f)).applyTo(document)

        val raster = rasterOf(blurred)
        assertTrue(Color.red(pixel(raster, 254, 250)) > 20)
        // 고르지 않은 오른쪽은 그대로다
        assertEquals(Color.WHITE, pixel(raster, 257, 250))
        // 획이 닿지 않은 타일은 같은 객체다
        assertSame(rasterOf(document).tiles[TileKey(2, 0)], raster.tiles[TileKey(2, 0)])
        // 빈(투명한) 레이어에는 바꿀 색이 없어 그대로다
        val withEmpty = AddLayer("Empty", document.layers.size).applyTo(document)
        assertSame(withEmpty, EffectStroke(withEmpty.layers.last().id, vertical(100f, 100f, 200f), hard, EffectFill(EffectKind.MOSAIC)).applyTo(withEmpty))
    }

    @Test
    fun blurAndMosaic_changeTheGainmapToo_butSharpenLeavesIt() {
        val base = split(512, 512, edge = 256)
        val gainmap = HdrGainmap(128, 128, rasterOf(document(128, 128) { x, _ -> if (x < 64) gray(40) else gray(220) }))
        val document = base.copy(layers = listOf(base.layers[0].copy(gainmap = gainmap)))
        val line = vertical(256f, 100f, 400f)

        val blurred = EffectStroke(LayerId(0), line, hard, EffectFill(EffectKind.BLUR, strength = 1f)).applyTo(document)
        val mosaic = EffectStroke(LayerId(0), line, hard, EffectFill(EffectKind.MOSAIC, cell = 32)).applyTo(document)
        val sharpened = EffectStroke(LayerId(0), line, hard, EffectFill(EffectKind.SHARPEN, strength = 1f)).applyTo(document)

        assertNotSame(gainmap, blurred.layers[0].gainmap)
        assertNotSame(gainmap, mosaic.layers[0].gainmap)
        assertSame(gainmap, sharpened.layers[0].gainmap)
        // 게인맵 경계도 흐려진다(게인맵은 원본의 1/4)
        val value = Color.red(pixel(checkNotNull(blurred.layers[0].gainmap).raster, 63, 60))
        assertTrue("$value", value in 60..200)
    }

    @Test
    fun preview_showsTheEffect_likeTheRecordedStroke() {
        val document = split(768, 512, edge = 256)
        val fill = EffectFill(EffectKind.BLUR, strength = 1f)
        val line = vertical(256f, 100f, 400f)

        val recorded = rasterOf(EffectStroke(LayerId(0), line, hard, fill).applyTo(document))
        val preview = checkNotNull(
            LayerPreviewCache().render(document, level = 0, strokes = listOf(LiveStroke(LayerId(0), line, hard, erase = false, effect = fill)))
        ).copy(Bitmap.Config.ARGB_8888, false)

        for (x in listOf(250, 254, 258, 262)) {
            val a = Color.red(preview.getPixel(x, 250))
            val b = Color.red(pixel(recorded, x, 250))
            assertTrue("x $x: $a vs $b", abs(a - b) <= 3)
        }
        assertEquals(Color.BLACK, preview.getPixel(250, 480))
    }
}
