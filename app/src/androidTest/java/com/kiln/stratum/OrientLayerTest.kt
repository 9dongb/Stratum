package com.kiln.stratum

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.platform.AddTextLayer
import com.kiln.stratum.engine.platform.OrientLayer
import com.kiln.stratum.engine.platform.SelectShape
import com.kiln.stratum.engine.platform.TranslateLayer
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.contentBounds
import com.kiln.stratum.engine.selection.SelectionMode
import com.kiln.stratum.engine.selection.SelectionShape
import com.kiln.stratum.engine.text.TextStyle
import com.kiln.stratum.engine.transform.Orientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 레이어 돌리기·뒤집기([OrientLayer], 사용자 피드백 12차 2)를 에뮬레이터의 안드로이드 복사로 확인한다:
 * 레이어 내용 사각형의 가운데를 축으로 픽셀 값 그대로 돌리고, 캔버스 밖으로 나간 곳은 잘리며, 왼쪽으로 돌린 뒤 오른쪽으로 돌리면 제자리다.
 * 다른 레이어·선택 영역은 그대로이고, 게인맵도 같은 비율로 돌린다. 비트맵 타일의 내용 사각형도 찾는다.
 */
@RunWith(AndroidJUnit4::class)
class OrientLayerTest {

    /** 자리마다 다른 불투명한 색. 어느 픽셀이 어디로 갔는지 알 수 있다. */
    private fun colorAt(x: Int, y: Int): Int = 0xFF000000.toInt() or ((y * 4099 + x) and 0xFFFFFF)

    /** [width]×[height] 캔버스에서 [block] 안만 자리마다 다른 색이고 나머지는 투명한 래스터(힙 타일) */
    private fun blockRaster(width: Int, height: Int, block: PixelRect): Raster {
        val grid = TileGrid(width, height)
        val tiles = grid.keys().mapNotNull { key ->
            val bounds = grid.bounds(key)
            val tile = PixelRect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
            if (tile.intersect(block) == null) return@mapNotNull null
            key to Tile.of(
                IntArray(TILE_PIXEL_COUNT) { index ->
                    val x = bounds.left + index % TILE_SIZE
                    val y = bounds.top + index / TILE_SIZE
                    if (x in block.left until block.right && y in block.top until block.bottom) colorAt(x, y) else 0
                }
            )
        }.toMap()
        return Raster.EMPTY.withTiles(tiles)
    }

    private fun pixel(raster: Raster, x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    private fun Document.raster(index: Int = 0): Raster = (layers[index].content as RasterContent).raster

    /** 한 픽셀 옮겼다 되돌려 타일을 안드로이드 비트맵 타일로 바꾼다(내용이 캔버스 끝에 닿지 않을 때만, 픽셀은 그대로) */
    private fun withBitmapTiles(document: Document, layer: LayerId = LayerId(0)): Document =
        TranslateLayer(layer, -1, 0).applyTo(TranslateLayer(layer, 1, 0).applyTo(document))

    @Test
    fun turnsTheContentAroundItsCenter_pixelExact() {
        val block = PixelRect(200, 100, 300, 140)
        val document = withBitmapTiles(Document.create(600, 300, "Background", blockRaster(600, 300, block)))
        // 비트맵 타일에서도 내용 사각형을 찾는다
        assertEquals(block, document.raster().contentBounds())

        val turned = OrientLayer(LayerId(0), Orientation.ROTATE_CLOCKWISE).applyTo(document).raster()

        // 100×40이 40×100이 되어, 가운데 (250, 120)에 놓인다: 왼쪽 위 (230, 70)
        assertEquals(PixelRect(230, 70, 270, 170), turned.contentBounds())
        for ((lx, ly) in listOf(0 to 0, 99 to 0, 0 to 39, 99 to 39, 37 to 21)) {
            // 시계 방향: 내용 안 (lx, ly)는 (39 - ly, lx)로 간다
            assertEquals("($lx, $ly)", colorAt(block.left + lx, block.top + ly), pixel(turned, 230 + 39 - ly, 70 + lx))
        }
        assertEquals(0, pixel(turned, 200, 100))
        assertEquals(0, pixel(turned, 299, 139))
    }

    @Test
    fun turningLeftThenRight_comesBackExactly_evenWithAHalfPixelCenter() {
        // 101×40: 가로·세로 차이가 홀수라 가운데가 반 픽셀에 걸린다
        val block = PixelRect(250, 120, 351, 160)
        val document = Document.create(600, 300, "Background", blockRaster(600, 300, block))

        val left = OrientLayer(LayerId(0), Orientation.ROTATE_COUNTERCLOCKWISE).applyTo(document)
        val back = OrientLayer(LayerId(0), Orientation.ROTATE_CLOCKWISE).applyTo(left).raster()

        assertEquals(block, back.contentBounds())
        for (y in block.top until block.bottom step 3) {
            for (x in block.left until block.right step 4) {
                assertEquals("($x, $y)", colorAt(x, y), pixel(back, x, y))
            }
        }
    }

    @Test
    fun flips_stayInPlace() {
        val block = PixelRect(200, 100, 300, 140)
        val document = Document.create(600, 300, "Background", blockRaster(600, 300, block))

        val horizontal = OrientLayer(LayerId(0), Orientation.FLIP_HORIZONTAL).applyTo(document).raster()
        val vertical = OrientLayer(LayerId(0), Orientation.FLIP_VERTICAL).applyTo(document).raster()

        assertEquals(block, horizontal.contentBounds())
        assertEquals(colorAt(299, 100), pixel(horizontal, 200, 100))
        assertEquals(colorAt(210, 130), pixel(horizontal, 289, 130))
        assertEquals(colorAt(200, 139), pixel(vertical, 200, 100))
        assertEquals(colorAt(250, 105), pixel(vertical, 250, 134))
    }

    @Test
    fun aFullPhotoOnAWideCanvas_isCutTopAndBottom_andTheSidesBecomeClear() {
        val document = Document.create(600, 300, "Background", blockRaster(600, 300, PixelRect(0, 0, 600, 300)))

        val turned = OrientLayer(LayerId(0), Orientation.ROTATE_CLOCKWISE).applyTo(document).raster()

        // 300×600이 되어 (150, -150)에 놓인다: 가운데 300×300만 남고 양옆은 투명하다
        assertEquals(PixelRect(150, 0, 450, 300), turned.contentBounds())
        // 캔버스 (tx, ty) ← 내용 (ty + 150, 299 - (tx - 150))
        assertEquals(colorAt(150, 299), pixel(turned, 150, 0))
        assertEquals(colorAt(449, 0), pixel(turned, 449, 299))
        assertEquals(0, pixel(turned, 149, 10))
        assertEquals(0, pixel(turned, 450, 10))
    }

    @Test
    fun otherLayers_theSelection_andTheLayerProperties_stay() {
        val base = Document.create(600, 300, "Background", blockRaster(600, 300, PixelRect(10, 10, 50, 30)))
        val twoLayers = AddLayer("Layer 1", 1).applyTo(base)
        val selected = SelectShape(SelectionShape.Rectangle(0f, 0f, 100f, 100f), SelectionMode.REPLACE).applyTo(twoLayers)

        val turned = OrientLayer(LayerId(0), Orientation.ROTATE_180).applyTo(selected)

        assertSame(selected.layers[1], turned.layers[1])
        assertSame(selected.selection, turned.selection)
        assertEquals(selected.layers[0].copy(content = turned.layers[0].content), turned.layers[0])
        // 그 레이어가 없거나, 다 투명하거나(빈 새 레이어), 그대로인 방향이면 바뀌지 않는다
        assertSame(selected, OrientLayer(LayerId(9), Orientation.ROTATE_CLOCKWISE).applyTo(selected))
        assertSame(selected, OrientLayer(selected.layers[1].id, Orientation.ROTATE_CLOCKWISE).applyTo(selected))
        assertSame(selected, OrientLayer(LayerId(0), Orientation.IDENTITY).applyTo(selected))
    }

    @Test
    fun theGainmap_turnsTheSameWay() {
        val block = PixelRect(200, 100, 300, 140)
        val base = Document.create(600, 300, "Background", blockRaster(600, 300, block))
        val gainmap = HdrGainmap(150, 75, blockRaster(150, 75, PixelRect(0, 0, 150, 75)))
        val document = base.copy(layers = listOf(base.layers[0].copy(gainmap = gainmap)))

        val turned = checkNotNull(OrientLayer(LayerId(0), Orientation.ROTATE_CLOCKWISE).applyTo(document).layers[0].gainmap)

        // 1/4 게인맵: 내용 (50, 25)~(75, 35)가 25×10에서 10×25가 되어 (230, 70)의 1/4 자리 (58, 18)에 놓인다
        assertEquals(150, turned.width)
        assertEquals(PixelRect(58, 18, 68, 43), turned.raster.contentBounds())
        assertEquals(colorAt(50, 25), pixel(turned.raster, 67, 18))
        assertEquals(colorAt(74, 34), pixel(turned.raster, 58, 42))
    }

    @Test
    fun aTextLayer_becomesPixels() {
        val blank = Document.create(600, 300, "Background", Raster.EMPTY)
        val withText = AddTextLayer("Hello", x = 50f, y = 50f, style = TextStyle(size = 60f, color = android.graphics.Color.BLACK), name = "Hello", index = 1)
            .applyTo(blank)
        val before = checkNotNull(withText.raster(1).contentBounds())

        val turned = OrientLayer(withText.layers[1].id, Orientation.ROTATE_CLOCKWISE).applyTo(withText)

        assertNull(turned.layers[1].text)
        val after = checkNotNull(turned.raster(1).contentBounds())
        assertEquals(before.width, after.height)
        assertEquals(before.height, after.width)
        assertTrue("$before -> $after", after != before)
    }
}
