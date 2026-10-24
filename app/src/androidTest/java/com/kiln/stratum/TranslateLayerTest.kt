package com.kiln.stratum

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.platform.TranslateLayer
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileAt
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.engine.platform.AddTextLayer
import com.kiln.stratum.engine.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 이동 도구의 기록([TranslateLayer], 결정 기록 '이동 도구')을 에뮬레이터의 안드로이드 복사로 확인한다:
 * 픽셀 값 그대로 옮기고, 캔버스 밖으로 나간 부분은 잘리며, 다른 레이어·선택 영역은 그대로이고, 게인맵도 같은 비율로 옮긴다.
 */
@RunWith(AndroidJUnit4::class)
class TranslateLayerTest {

    /** 자리마다 다른 불투명한 색. 어느 픽셀이 어디로 갔는지 알 수 있다. */
    private fun colorAt(x: Int, y: Int): Int = 0xFF000000.toInt() or ((y * 4099 + x) and 0xFFFFFF)

    /** 자리마다 다른 색의 [width]×[height] 래스터(끝 타일의 캔버스 밖 부분은 투명) */
    private fun photo(width: Int, height: Int): Raster {
        val grid = TileGrid(width, height)
        val tiles = grid.keys().associateWith { key ->
            val bounds = grid.bounds(key)
            Tile.of(
                IntArray(TILE_PIXEL_COUNT) { index ->
                    val x = index % TILE_SIZE
                    val y = index / TILE_SIZE
                    if (x < bounds.width && y < bounds.height) colorAt(bounds.left + x, bounds.top + y) else 0
                }
            )
        }
        return Raster.EMPTY.withTiles(tiles)
    }

    /** 문서 좌표([x], [y], 캔버스 밖 포함)의 픽셀. 타일이 없거나 담지 않은 곳은 투명 */
    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[tileAt(x, y)]?.pixelAt(Math.floorMod(x, TILE_SIZE), Math.floorMod(y, TILE_SIZE)) ?: 0

    private fun Document.raster(index: Int = 0): Raster = (layers[index].content as RasterContent).raster

    @Test
    fun movesThePixelsExactly_andCutsWhatLeavesTheCanvas() {
        val document = Document.create(600, 300, "Background", photo(600, 300))

        val moved = TranslateLayer(LayerId(0), 130, -40).applyTo(document).raster()

        for ((x, y) in listOf(130 to 0, 300 to 100, 599 to 259, 131 to 259, 400 to 7)) {
            assertEquals("($x, $y)", colorAt(x - 130, y + 40), pixel(moved, x, y))
        }
        // 옮겨서 빈 자리(왼쪽 130px, 아래 40px)는 투명이다
        assertEquals(0, pixel(moved, 129, 10))
        assertEquals(0, pixel(moved, 300, 260))
        assertEquals(0, pixel(moved, 0, 299))
        // 오른쪽·위로 나간 픽셀은 캔버스 밖에 남는다(포토샵처럼, 결정 기록 '캔버스 밖 픽셀')
        assertEquals(colorAt(599, 100), pixel(moved, 729, 60))
        assertEquals(colorAt(10, 20), pixel(moved, 140, -20))
        assertEquals(colorAt(599, 0), pixel(moved, 729, -40))
        // 되돌리면 원래 그림 그대로다
        val back = TranslateLayer(LayerId(0), -130, 40).applyTo(document.copy(layers = listOf(document.layers[0].copy(content = RasterContent(moved))))).raster()
        for (y in 0 until 300 step 7) {
            for (x in 0 until 600 step 11) assertEquals("($x, $y)", colorAt(x, y), pixel(back, x, y))
        }
        assertTrue(back.tiles.keys.all { TileGrid(600, 300).contains(it) })
    }

    @Test
    fun movingByWholeTiles_reusesTheTiles_andWhatGoesAllTheWayOutComesBack() {
        val document = Document.create(1024, 512, "Background", photo(1024, 512))

        val moved = TranslateLayer(LayerId(0), 256, 0).applyTo(document).raster()

        assertSame(document.raster().tiles[TileKey(0, 0)], moved.tiles[TileKey(1, 0)])
        assertTrue(moved.tiles[TileKey(0, 0)] == null)
        // 캔버스 밖으로 다 나가도 타일은 캔버스 밖 자리에 그대로 남고, 되들이면 같은 타일로 돌아온다
        val out = TranslateLayer(LayerId(0), 1024, 0).applyTo(document)
        assertTrue(out.raster().tiles.keys.none { TileGrid(1024, 512).contains(it) })
        val back = TranslateLayer(LayerId(0), -1024, 0).applyTo(out).raster()
        for ((key, tile) in document.raster().tiles) assertSame("$key", tile, back.tiles[key])
        assertEquals(document.raster().tiles.keys, back.tiles.keys)
    }

    @Test
    fun otherLayers_theSelection_andTheLayerProperties_stay() {
        val base = Document.create(600, 300, "Background", photo(600, 300))
        val twoLayers = AddLayer("Layer 1", 1).applyTo(base)
        val selected = SelectShape(SelectionShape.Rectangle(0f, 0f, 100f, 100f), SelectionMode.REPLACE).applyTo(twoLayers)

        val moved = TranslateLayer(LayerId(0), 50, 50).applyTo(selected)

        assertSame(selected.layers[1], moved.layers[1])
        assertSame(selected.selection, moved.selection)
        assertEquals(selected.layers[0].copy(content = moved.layers[0].content), moved.layers[0])
        // 그 레이어가 없거나 옮기지 않으면 그대로다
        assertSame(selected, TranslateLayer(LayerId(9), 50, 50).applyTo(selected))
        assertSame(selected, TranslateLayer(LayerId(0), 0, 0).applyTo(selected))
    }

    @Test
    fun theGainmap_movesByTheSameProportion() {
        val base = Document.create(600, 300, "Background", photo(600, 300))
        val gainmap = HdrGainmap(150, 75, photo(150, 75))
        val document = base.copy(layers = listOf(base.layers[0].copy(gainmap = gainmap)))

        // 문서에서 (100, 40)이면 1/4 게인맵에서는 (25, 10)이다
        val moved = checkNotNull(TranslateLayer(LayerId(0), 100, 40).applyTo(document).layers[0].gainmap)

        assertEquals(150, moved.width)
        assertEquals(colorAt(0, 0), pixel(moved.raster, 25, 10))
        assertEquals(colorAt(60, 30), pixel(moved.raster, 85, 40))
        assertEquals(0, pixel(moved.raster, 10, 5))
    }

    @Test
    fun textLayer_isRedrawnAtTheNewPlace_andComesBackWholeFromOutsideTheCanvas() {
        // 글자 레이어(사용자 피드백 8차 1-6)는 픽셀을 옮기지 않고 글자를 새 자리에 다시 그린다
        val blank = Document.create(600, 300, "Background", Raster.EMPTY)
        val withText = AddTextLayer("Hello", x = 50f, y = 50f, style = TextStyle(size = 60f, color = android.graphics.Color.BLACK), name = "Hello", index = 1)
            .applyTo(blank)
        val spec = checkNotNull(withText.layers[1].text)

        val moved = TranslateLayer(withText.layers[1].id, 100, 40).applyTo(withText)
        assertEquals(spec.moved(100f, 40f), moved.layers[1].text)

        // 캔버스 밖으로 거의 다 내보냈다가 되돌려도 글자가 잘리지 않고 그대로다(고칠 수 있는 채로 남음)
        val out = TranslateLayer(withText.layers[1].id, 560, 0).applyTo(withText)
        val back = TranslateLayer(withText.layers[1].id, -560, 0).applyTo(out)
        assertEquals(spec, back.layers[1].text)
        val original = withText.raster(1)
        val restored = back.raster(1)
        for (y in 50 until 50 + spec.height.toInt() step 3) {
            for (x in 50 until 50 + spec.width.toInt() step 3) {
                assertEquals("($x, $y)", pixel(original, x, y), pixel(restored, x, y))
            }
        }
    }
}
