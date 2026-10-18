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
import com.kiln.stratum.engine.platform.RetouchStroke
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.preview.PreviewLevels
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.retouch.RetouchMode
import com.kiln.stratum.engine.retouch.SourceOffset
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 복제 도장·복구 브러시·스팟 복구 브러시 획을 원본 해상도에 그리는지 에뮬레이터에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class RetouchStrokeTest {

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

    private fun dot(x: Float, y: Float) = listOf(StrokePoint(x, y))

    private val hard = BrushSettings(size = 40f, hardness = 1f, opacity = 1f)

    /** 회색 128 바탕 800×600에 가운데 ([x], [y]), 반지름 [radius]의 어두운 점(20) */
    private fun grayWithSpot(x: Float = 200f, y: Float = 200f, radius: Float = 10f): Document =
        documentOf(800, 600) { px, py -> if (hypot(px + 0.5f - x, py + 0.5f - y) <= radius) gray(20) else gray(128) }

    @Test
    fun clone_copiesThePixelsExactly_andLeavesTheSource() {
        // 빨강·초록 바둑판 무늬를 오른쪽 300픽셀 자리로 베낀다(그 자리 - 300에서 가져옴)
        val document = documentOf(800, 600) { x, y -> if (x < 400) (if ((x / 3 + y / 3) % 2 == 0) Color.RED else Color.GREEN) else gray(128) }

        val cloned = RetouchStroke(LayerId(0), dot(500f, 200f), hard, RetouchMode.CLONE, SourceOffset(300, 0)).applyTo(document)

        // 가운데는 가져온 픽셀 그대로다
        for (x in 490..510) assertEquals("x $x", pixel(document, x - 300, 200), pixel(cloned, x, 200))
        // 획 밖과 가져온 곳은 그대로다
        assertEquals(gray(128), pixel(cloned, 500, 240))
        assertEquals(pixel(document, 200, 200), pixel(cloned, 200, 200))
    }

    @Test
    fun clone_fromOutsideTheDocument_leavesThePixels() {
        val document = grayWithSpot()

        // 가져올 곳(x -200)이 문서 밖이다
        val cloned = RetouchStroke(LayerId(0), dot(100f, 100f), hard, RetouchMode.CLONE, SourceOffset(300, 0)).applyTo(document)

        assertEquals(gray(128), pixel(cloned, 100, 100))
    }

    @Test
    fun heal_takesTheTexture_butKeepsTheSurroundingBrightness() {
        // 왼쪽은 어두운 회색(60), 오른쪽은 밝은 줄무늬(200·230, 4픽셀 주기). 어두운 쪽에 줄무늬를 가져와도 밝기는 어두운 쪽을 따른다.
        val document = documentOf(800, 600) { x, _ -> if (x < 400) gray(60) else if (x % 4 < 2) gray(230) else gray(200) }

        val healed = RetouchStroke(LayerId(0), dot(150f, 300f), BrushSettings(size = 60f, hardness = 1f), RetouchMode.HEAL, SourceOffset(-450, 0))
            .applyTo(document)

        val values = (140..160).map { Color.red(pixel(healed, it, 300)) }
        // 복제였다면 200·230이었을 자리가 바탕 밝기(60) 근처다
        assertTrue("$values", abs(values.average() - 60) <= 10)
        // 줄무늬(무늬)는 가져왔다
        assertTrue("$values", values.max() - values.min() >= 15)
        // 획 밖은 그대로다
        assertEquals(gray(60), pixel(healed, 150, 340))
    }

    @Test
    fun heal_doesNotBringBackTheBlemishUnderTheStroke() {
        // 칠한 곳 안의 얼룩(20)은 결과에 섞이지 않는다
        val document = grayWithSpot(400f, 300f)

        val healed = RetouchStroke(LayerId(0), dot(400f, 300f), BrushSettings(size = 40f, hardness = 1f), RetouchMode.HEAL, SourceOffset(100, 0))
            .applyTo(document)

        assertTrue(abs(Color.red(pixel(healed, 400, 300)) - 128) <= 4)
    }

    @Test
    fun spotHeal_removesABlemish_withoutAskingForASource() {
        // 가로로 밝아지는 바탕(40 → 200)에 어두운 점. 스팟 복구는 둘레에서 가져와 그 자리 밝기로 메운다.
        val document = documentOf(800, 600) { x, y ->
            if (hypot(x + 0.5f - 420f, y + 0.5f - 300f) <= 8f) gray(0) else gray(40 + x * 160 / 800)
        }
        val expected = 40 + 420 * 160 / 800

        val healed = RetouchStroke(LayerId(0), dot(420f, 300f), BrushSettings(size = 30f, hardness = 0.8f), RetouchMode.SPOT_HEAL).applyTo(document)

        for (x in 412..428) assertTrue("x $x", abs(Color.red(pixel(healed, x, 300)) - (40 + x * 160 / 800)) <= 8)
        assertTrue(abs(Color.red(pixel(healed, 420, 300)) - expected) <= 6)
    }

    @Test
    fun spotHeal_besideAnEdge_keepsTheEdgeClean() {
        // 흰 바탕의 45° 검은 줄(두께 40) 바로 옆을 줄을 따라 칠한다. 줄을 따라 옮긴 곳에서 가져와, 옆은 희고 줄은 그대로다(번지지 않는다).
        val document = documentOf(1200, 1200) { x, y -> if (abs(x - y) <= 14) Color.BLACK else Color.WHITE }
        val beside = StrokeDabs.place(listOf(StrokePoint(400f, 445f), StrokePoint(600f, 645f)), 6f)

        val healed = RetouchStroke(LayerId(0), beside, BrushSettings(size = 60f, hardness = 0.5f), RetouchMode.SPOT_HEAL).applyTo(document)

        for (y in listOf(480, 520, 560, 600)) {
            for (x in y - 60..y - 20 step 5) assertTrue("($x, $y) ${Color.red(pixel(healed, x, y))}", Color.red(pixel(healed, x, y)) >= 235)
            assertTrue(Color.red(pixel(healed, y, y)) <= 20)
        }
    }

    @Test
    fun retouch_staysInsideTheSelection_andSharesUntouchedTiles() {
        // 왼쪽 400픽셀만 고르고, 경계를 지나는 획을 복제한다
        val document = SelectShape(SelectionShape.Rectangle(0f, 0f, 400f, 600f), SelectionMode.REPLACE).applyTo(grayWithSpot(100f, 300f))
        val line = StrokeDabs.place(listOf(StrokePoint(350f, 300f), StrokePoint(450f, 300f)), 2f)

        val cloned = RetouchStroke(LayerId(0), line, BrushSettings(size = 30f, hardness = 1f), RetouchMode.CLONE, SourceOffset(270, 0)).applyTo(document)

        // 고른 곳(x 375)은 가져온 곳(x 105, 점 안)의 색이고, 고르지 않은 곳(x 450)은 그대로다
        assertEquals(gray(20), pixel(cloned, 375, 300))
        assertEquals(gray(128), pixel(cloned, 450, 300))
        assertSame(rasterOf(document).tiles[TileKey(0, 0)], rasterOf(cloned).tiles[TileKey(0, 0)])
    }

    @Test
    fun nothingToTake_isNoChange() {
        val document = grayWithSpot()

        // 옮기지 않는 복제와, 둘레에 가져올 곳이 없는 스팟 복구(문서 전체를 덮는 획)
        assertSame(document, RetouchStroke(LayerId(0), dot(300f, 300f), hard, RetouchMode.CLONE, SourceOffset(0, 0)).applyTo(document))
        val everywhere = StrokeDabs.place(listOf(StrokePoint(0f, 0f), StrokePoint(800f, 600f)), 50f)
        assertSame(document, RetouchStroke(LayerId(0), everywhere, BrushSettings(size = 1000f), RetouchMode.SPOT_HEAL).applyTo(document))
    }

    @Test
    fun clonePreview_showsTheSourcePixels() {
        val document = grayWithSpot(200f, 200f, radius = 15f)
        val cache = LayerPreviewCache()

        val preview = checkNotNull(
            cache.render(document, level = 1, strokes = listOf(LiveStroke(LayerId(0), dot(500f, 200f), hard, erase = false, clone = SourceOffset(300, 0))))
        ).copy(Bitmap.Config.ARGB_8888, false)

        // 1/2 축소본에서 획 자리(250, 100)가 가져온 점의 색이다
        assertTrue(Color.red(preview.getPixel(250, 100)) < 40)
        assertTrue(abs(Color.red(preview.getPixel(250, 120)) - 128) <= 2)
        assertEquals(PreviewLevels.previewSize(PixelSize(800, 600), 1), PixelSize(preview.width, preview.height))
    }
}
