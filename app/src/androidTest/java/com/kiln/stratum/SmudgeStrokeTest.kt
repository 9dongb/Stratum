package com.kiln.stratum

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.brush.BrushSettings
import com.kiln.stratum.engine.brush.StrokeDabs
import com.kiln.stratum.engine.brush.StrokePoint
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.LayerPreviewCache
import com.kiln.stratum.engine.platform.LiveStroke
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.platform.SmudgePreview
import com.kiln.stratum.engine.platform.SmudgeStroke
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 스머지 획이 집은 색을 끌고 가는지(강도에 따라 멀리, 선택 영역, 문서 끝, 미리 보기)를 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class SmudgeStrokeTest {

    /** 픽셀마다 [color](문서 x, y) 값인 [width]×[height] 문서(타일을 직접 채운다) */
    private fun document(width: Int, height: Int, color: (Int, Int) -> Int): Document {
        val tiles = HashMap<TileKey, Tile?>()
        for (row in 0 until (height + TILE_SIZE - 1) / TILE_SIZE) {
            for (column in 0 until (width + TILE_SIZE - 1) / TILE_SIZE) {
                tiles[TileKey(column, row)] = Tile.of(IntArray(TILE_PIXEL_COUNT) { i -> color(column * TILE_SIZE + i % TILE_SIZE, row * TILE_SIZE + i / TILE_SIZE) })
            }
        }
        return Document.create(width, height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    /** 왼쪽은 검정, x = [edge]부터 오른쪽은 흰색 */
    private fun split(width: Int, height: Int, edge: Int): Document = document(width, height) { x, _ -> if (x < edge) Color.BLACK else Color.WHITE }

    private fun rasterOf(document: Document): Raster = (document.layers[0].content as RasterContent).raster

    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private val brush = BrushSettings(size = 60f, hardness = 1f)

    /** 가로 획(편집 화면처럼 붓 간격으로 자국을 놓는다) */
    private fun horizontal(from: Float, to: Float, y: Float): List<StrokePoint> =
        StrokeDabs.place(listOf(StrokePoint(from, y), StrokePoint(to, y)), brush.spacing)

    @Test
    fun smudge_dragsTheColorAlong_fartherWithMoreStrength() {
        val document = split(512, 256, edge = 200)
        val line = horizontal(170f, 400f, 128f)

        val weak = rasterOf(SmudgeStroke(LayerId(0), line, brush, 0.3f).applyTo(document))
        val strong = rasterOf(SmudgeStroke(LayerId(0), line, brush, 0.9f).applyTo(document))

        // 흰 쪽으로 검정이 끌려와 어두워진다
        assertTrue("${Color.red(pixel(strong, 230, 128))}", Color.red(pixel(strong, 230, 128)) < 200)
        // 강도가 셀수록 멀리 끌린다
        val farWeak = Color.red(pixel(weak, 330, 128))
        val farStrong = Color.red(pixel(strong, 330, 128))
        assertTrue("weak $farWeak strong $farStrong", farStrong < farWeak - 20)
        // 획 밖은 그대로다
        assertEquals(Color.WHITE, pixel(strong, 330, 220))
        assertEquals(Color.BLACK, pixel(strong, 100, 220))
    }

    @Test
    fun smudge_laysColorOnlyInsideTheSelection() {
        val document = SelectShape(SelectionShape.Rectangle(0f, 0f, 256f, 256f), SelectionMode.REPLACE).applyTo(split(512, 256, edge = 200))

        val smudged = rasterOf(SmudgeStroke(LayerId(0), horizontal(170f, 400f, 128f), brush, 0.9f).applyTo(document))

        assertTrue(Color.red(pixel(smudged, 230, 128)) < 200)
        assertEquals(Color.WHITE, pixel(smudged, 300, 128))
    }

    @Test
    fun atTheDocumentEdge_noTransparencyIsDraggedIn() {
        val document = document(256, 256) { _, _ -> Color.WHITE }

        val smudged = rasterOf(SmudgeStroke(LayerId(0), horizontal(5f, 150f, 128f), brush, 0.9f).applyTo(document))

        // 문서 밖을 투명이 아니라 가장자리 색으로 집으므로 투명해지지 않는다
        assertEquals(255, Color.alpha(pixel(smudged, 2, 128)))
        assertEquals(255, Color.alpha(pixel(smudged, 40, 128)))
    }

    @Test
    fun aTapOrNoStrength_changesNothing_andUntouchedTilesAreShared() {
        val document = split(768, 256, edge = 200)

        assertSame(document, SmudgeStroke(LayerId(0), listOf(StrokePoint(100f, 100f)), brush, 0.9f).applyTo(document))
        assertSame(document, SmudgeStroke(LayerId(0), horizontal(170f, 300f, 128f), brush, 0f).applyTo(document))
        val smudged = rasterOf(SmudgeStroke(LayerId(0), horizontal(170f, 300f, 128f), brush, 0.9f).applyTo(document))
        assertSame(rasterOf(document).tiles[TileKey(2, 0)], smudged.tiles[TileKey(2, 0)])
    }

    @Test
    fun preview_matchesTheRecordedStroke_evenWhenTheDabsComeInParts() {
        val document = split(512, 256, edge = 200)
        val line = horizontal(170f, 400f, 128f)
        val recorded = rasterOf(SmudgeStroke(LayerId(0), line, brush, 0.9f).applyTo(document))

        // 끄는 동안처럼 자국을 나눠 넣는다(미리 보기는 새 자국만 이어서 그린다)
        val cache = LayerPreviewCache()
        val smudge = SmudgePreview(brush, 0.9f)
        cache.render(document, level = 0, strokes = listOf(LiveStroke(LayerId(0), line.take(10), brush, erase = false, smudge = smudge)))
        val preview = checkNotNull(
            cache.render(document, level = 0, strokes = listOf(LiveStroke(LayerId(0), line, brush, erase = false, smudge = smudge)))
        ).copy(Bitmap.Config.ARGB_8888, false)

        for (x in listOf(210, 250, 300, 350, 390)) {
            val a = Color.red(preview.getPixel(x, 128))
            val b = Color.red(pixel(recorded, x, 128))
            assertTrue("x $x: $a vs $b", abs(a - b) <= 3)
        }
    }
}
