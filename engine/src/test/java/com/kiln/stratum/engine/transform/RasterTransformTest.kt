package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.command.AddLayer
import com.kiln.stratum.engine.command.SetLayerBlendMode
import com.kiln.stratum.engine.command.SetLayerOpacity
import com.kiln.stratum.engine.document.BlendMode
import com.kiln.stratum.engine.document.Document
import com.kiln.stratum.engine.document.HdrGainmap
import com.kiln.stratum.engine.document.LayerId
import com.kiln.stratum.engine.document.RasterContent
import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RasterTransformTest {

    private val composer = HeapTileComposer()

    /** 자리마다 다른 불투명한 색. 어느 픽셀이 어디로 갔는지 알 수 있다. */
    private fun colorAt(x: Int, y: Int): Int = 0xFF000000.toInt() or ((y * 4099 + x) and 0xFFFFFF)

    /** 불러온 사진처럼 자리마다 다른 색의 문서. 끝 타일의 캔버스 밖 부분은 투명이다. */
    private fun photo(size: PixelSize): Document {
        val grid = TileGrid(size.width, size.height)
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
        return Document.create(size.width, size.height, "Background", Raster.EMPTY.withTiles(tiles))
    }

    private fun Document.raster(index: Int = 0): Raster = (layers[index].content as RasterContent).raster

    private fun Document.pixelAt(x: Int, y: Int): Int {
        val tile = raster().tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: return 0
        return tile.pixelAt(x % TILE_SIZE, y % TILE_SIZE)
    }

    /** [document]의 모든 픽셀이 [expected]와 같은지 본다. */
    private fun assertPixels(document: Document, expected: (x: Int, y: Int) -> Int) {
        for (y in 0 until document.height) {
            for (x in 0 until document.width) {
                val actual = document.pixelAt(x, y)
                if (actual != expected(x, y)) fail("($x, $y) is ${actual.toUInt().toString(16)}")
            }
        }
    }

    /** [document] 레이어 0에 [photo] 무늬의 게인맵(크기 [size])을 단 문서 */
    private fun withGainmap(document: Document, size: PixelSize): Document {
        val gainmap = HdrGainmap(size.width, size.height, photo(size).raster())
        return document.copy(layers = listOf(document.layers[0].copy(gainmap = gainmap)))
    }

    private fun HdrGainmap.pixelAt(x: Int, y: Int): Int =
        raster.tiles[TileKey(x / TILE_SIZE, y / TILE_SIZE)]?.pixelAt(x % TILE_SIZE, y % TILE_SIZE) ?: 0

    @Test
    fun gainmap_turnsWithTheDocument() {
        val source = withGainmap(photo(PixelSize(800, 600)), PixelSize(200, 150))

        val turned = RasterTransform.apply(source, TransformPlan.orient(PixelSize(800, 600), Orientation.ROTATE_CLOCKWISE), composer)

        val gainmap = checkNotNull(turned.layers[0].gainmap)
        assertEquals(150, gainmap.width)
        assertEquals(200, gainmap.height)
        for ((x, y) in listOf(0 to 0, 149 to 199, 37 to 120)) {
            val from = Orientation.ROTATE_CLOCKWISE.inverse.mapPixel(x, y, 150, 200)
            assertEquals(colorAt(from.x, from.y), gainmap.pixelAt(x, y))
        }
    }

    @Test
    fun gainmap_isCroppedAtTheSameProportion_toTheNearestGainmapPixel() {
        val source = withGainmap(photo(PixelSize(800, 600)), PixelSize(200, 150))

        // 문서 (402, 2)~(800, 599)는 게인맵 (100.5, 0.5)~(200, 149.75): 가장 가까운 칸 경계는 (101, 1)~(200, 150)
        val cropped = RasterTransform.apply(source, TransformPlan.crop(PixelSize(800, 600), PixelRect(402, 2, 800, 599)), composer)

        val gainmap = checkNotNull(cropped.layers[0].gainmap)
        assertEquals(99, gainmap.width)
        assertEquals(149, gainmap.height)
        assertEquals(colorAt(101, 1), gainmap.pixelAt(0, 0))
        assertEquals(colorAt(199, 149), gainmap.pixelAt(98, 148))
        // 아주 좁게 잘라도 게인맵은 적어도 한 칸이다
        val thin = RasterTransform.apply(source, TransformPlan.crop(PixelSize(800, 600), PixelRect(0, 0, 1, 1)), composer)
        assertEquals(1, checkNotNull(thin.layers[0].gainmap).width)
    }

    @Test
    fun orient_movesEveryPixel() {
        val source = photo(PixelSize(600, 300))
        for (orientation in Orientation.entries) {
            val plan = TransformPlan.orient(PixelSize(600, 300), orientation)

            val result = RasterTransform.apply(source, plan, composer)

            assertEquals(plan.targetSize, PixelSize(result.width, result.height))
            assertPixels(result) { x, y ->
                val from = orientation.inverse.mapPixel(x, y, result.width, result.height)
                colorAt(from.x, from.y)
            }
        }
    }

    @Test
    fun fourTurns_bringBackTheSamePixels() {
        val size = PixelSize(601, 301)
        var document = photo(size)
        repeat(4) {
            document = RasterTransform.apply(
                document,
                TransformPlan.orient(PixelSize(document.width, document.height), Orientation.ROTATE_CLOCKWISE),
                composer
            )
        }

        assertEquals(size, PixelSize(document.width, document.height))
        assertPixels(document) { x, y -> colorAt(x, y) }
    }

    @Test
    fun crop_keepsTheRectangle() {
        val source = photo(PixelSize(1001, 521))
        val rect = PixelRect(100, 50, 700, 400)

        val result = RasterTransform.apply(source, TransformPlan.crop(PixelSize(1001, 521), rect), composer)

        assertPixels(result) { x, y -> colorAt(x + rect.left, y + rect.top) }
    }

    @Test
    fun crop_atTopLeft_reusesTilesThatFit() {
        val source = photo(PixelSize(1001, 521))

        val result = RasterTransform.apply(source, TransformPlan.crop(PixelSize(1001, 521), PixelRect(0, 0, 512, 300)), composer)

        // 위 줄 두 장은 캔버스 안쪽이 꼭 맞아 그대로 쓴다
        assertSame(source.raster().tiles[TileKey(0, 0)], result.raster().tiles[TileKey(0, 0)])
        assertSame(source.raster().tiles[TileKey(1, 0)], result.raster().tiles[TileKey(1, 0)])
        // 아래 줄은 캔버스 안쪽이 44줄로 줄어, 잘라 낸 픽셀이 남지 않게 새로 만든다
        assertNotSame(source.raster().tiles[TileKey(0, 1)], result.raster().tiles[TileKey(0, 1)])
        assertEquals(2, composer.composedCount)
        assertEquals(0, result.raster().tiles.getValue(TileKey(0, 1)).pixelAt(0, 44))
        assertPixels(result) { x, y -> colorAt(x, y) }
    }

    @Test
    fun solidCanvas_staysOneTile() {
        val white = Tile.solid(0xFFFFFFFF.toInt())
        val size = PixelSize(1001, 521)
        val canvas = Document.create(size.width, size.height, "Background", Raster.filled(TileGrid(size.width, size.height), white))
        val plans = Orientation.entries.map { TransformPlan.orient(size, it) } + TransformPlan.crop(size, PixelRect(3, 7, 900, 500))

        for (plan in plans) {
            val result = RasterTransform.apply(canvas, plan, composer)

            assertEquals(TileGrid(result.width, result.height).keys().toSet(), result.raster().tiles.keys)
            assertTrue(result.raster().tiles.values.all { it === white })
        }
        assertEquals(0, composer.composedCount)
    }

    @Test
    fun missingTiles_stayMissing() {
        // 왼쪽 위 타일 하나만 있는 600×300 레이어를 180° 돌린다
        val tile = photo(PixelSize(600, 300)).raster().tiles.getValue(TileKey(0, 0))
        val layer = Raster.EMPTY.withTiles(mapOf(TileKey(0, 0) to tile))
        val source = Document.create(600, 300, "Background", layer)

        val result = RasterTransform.apply(source, TransformPlan.orient(PixelSize(600, 300), Orientation.ROTATE_180), composer)

        // 그 타일의 픽셀(x 0~255, y 0~255)은 오른쪽 아래(x 344~599, y 44~299)로 가서 대상 타일 네 장에 걸친다
        assertEquals(
            setOf(TileKey(1, 0), TileKey(2, 0), TileKey(1, 1), TileKey(2, 1)),
            result.raster().tiles.keys
        )
        assertPixels(result) { x, y ->
            val from = Orientation.ROTATE_180.mapPixel(x, y, 600, 300)
            if (from.x < TILE_SIZE && from.y < TILE_SIZE) colorAt(from.x, from.y) else 0
        }
    }

    @Test
    fun translate_movesThePixels_andTheRestIsTransparent() {
        val size = PixelSize(600, 300)
        val source = photo(size)

        val moved = source.copy(layers = listOf(source.layers[0].copy(content = RasterContent(RasterTransform.transform(source.raster(), TransformPlan.translate(size, 100, -30), composer)))))

        assertPixels(moved) { x, y -> if (x - 100 in 0 until 600 && y + 30 in 0 until 300) colorAt(x - 100, y + 30) else 0 }
    }

    @Test
    fun translate_byWholeTiles_reusesTheTiles() {
        val size = PixelSize(1024, 512)
        val source = photo(size)

        val raster = RasterTransform.transform(source.raster(), TransformPlan.translate(size, 256, 256), composer)

        // 옮긴 자리마다 원본 타일 객체를 그대로 쓰고, 밖으로 나간 타일은 버리며, 빈 자리에는 타일이 없다
        assertEquals(0, composer.composedCount)
        assertEquals(setOf(TileKey(1, 1), TileKey(2, 1), TileKey(3, 1)), raster.tiles.keys)
        for (column in 1..3) assertSame(source.raster().tiles[TileKey(column - 1, 0)], raster.tiles[TileKey(column, 1)])
    }

    @Test
    fun translate_ofASolidCanvas_doesNotReuseAPartlyCoveredTile() {
        val white = Tile.solid(0xFFFFFFFF.toInt())
        val size = PixelSize(600, 300)
        val raster = RasterTransform.transform(Raster.filled(TileGrid(size.width, size.height), white), TransformPlan.translate(size, 10, 0), composer)

        // 왼쪽 끝 줄 타일은 일부만 덮여 새로 만들고(빈 10px은 투명), 다 덮인 타일은 한 가지 색 타일 그대로다
        val left = raster.tiles.getValue(TileKey(0, 0))
        assertNotSame(white, left)
        assertEquals(0, left.pixelAt(9, 0))
        assertEquals(0xFFFFFFFF.toInt(), left.pixelAt(10, 0))
        assertSame(white, raster.tiles[TileKey(1, 0)])
    }

    @Test
    fun translateGainmap_movesItByTheSameProportion() {
        val size = PixelSize(1000, 600)
        val gainmap = HdrGainmap(250, 150, photo(PixelSize(250, 150)).raster())

        // 문서에서 (100, -40)이면 1/4 크기 게인맵에서는 (25, -10)이다
        val moved = RasterTransform.translateGainmap(gainmap, size, 100, -40, composer)

        assertEquals(250, moved.width)
        assertEquals(150, moved.height)
        assertEquals(gainmap.pixelAt(0, 10), moved.pixelAt(25, 0))
        assertEquals(gainmap.pixelAt(100, 100), moved.pixelAt(125, 90))
        assertEquals(0, moved.pixelAt(10, 50))
        // 비율로 줄여 0이면 그대로다
        assertSame(gainmap, RasterTransform.translateGainmap(gainmap, size, 1, 1, composer))
    }

    @Test
    fun emptyLayer_staysEmpty() {
        val source = Document.create(600, 300, "Background")

        val result = RasterTransform.apply(source, TransformPlan.orient(PixelSize(600, 300), Orientation.ROTATE_CLOCKWISE), composer)

        assertSame(Raster.EMPTY, result.raster())
    }

    @Test
    fun layerProperties_areKept() {
        val source = SetLayerBlendMode(LayerId(1), BlendMode.MULTIPLY).applyTo(
            SetLayerOpacity(LayerId(1), 0.5f).applyTo(AddLayer("Layer 1", 1).applyTo(photo(PixelSize(300, 200))))
        )

        val result = RasterTransform.apply(source, TransformPlan.orient(PixelSize(300, 200), Orientation.FLIP_HORIZONTAL), composer)

        assertEquals(source.nextLayerId, result.nextLayerId)
        assertEquals(source.layers.map { it.id }, result.layers.map { it.id })
        assertEquals(source.layers.map { it.name }, result.layers.map { it.name })
        assertEquals(source.layers.map { it.opacity }, result.layers.map { it.opacity })
        assertEquals(source.layers.map { it.blendMode }, result.layers.map { it.blendMode })
    }

    @Test
    fun cancel_stopsBetweenTiles() {
        val source = photo(PixelSize(1001, 521))
        var calls = 0

        assertThrows(CancelledForTest::class.java) {
            RasterTransform.apply(source, TransformPlan.orient(PixelSize(1001, 521), Orientation.ROTATE_CLOCKWISE), composer) {
                if (++calls > 2) throw CancelledForTest()
            }
        }
        assertEquals(2, composer.composedCount)
    }

    @Test
    fun planForAnotherSize_isRejected() {
        val source = photo(PixelSize(300, 200))

        assertThrows(IllegalArgumentException::class.java) {
            RasterTransform.apply(source, TransformPlan.orient(PixelSize(200, 300), Orientation.IDENTITY), composer)
        }
    }

    private class CancelledForTest : RuntimeException()
}
