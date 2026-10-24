package com.kiln.stratum.engine.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterTest {

    private fun solidTile(color: Int): Tile = Tile.of(IntArray(TILE_PIXEL_COUNT) { color })

    @Test
    fun tileOf_copiesInputPixels() {
        val pixels = IntArray(TILE_PIXEL_COUNT) { 0xFF112233.toInt() }
        val tile = Tile.of(pixels)

        // 만든 뒤에 원래 배열을 바꿔도 타일은 그대로여야 한다
        pixels[0] = 0
        assertEquals(0xFF112233.toInt(), tile.pixelAt(0, 0))
    }

    @Test
    fun tileOf_rejectsWrongSize() {
        assertThrows(IllegalArgumentException::class.java) { Tile.of(IntArray(10)) }
    }

    @Test
    fun tilePixelAt_readsRowByRowAndRejectsOutside() {
        val pixels = IntArray(TILE_PIXEL_COUNT)
        pixels[1 * TILE_SIZE + 2] = 7 // x=2, y=1
        val tile = Tile.of(pixels)

        assertEquals(7, tile.pixelAt(2, 1))
        assertThrows(IllegalArgumentException::class.java) { tile.pixelAt(TILE_SIZE, 0) }
        assertThrows(IllegalArgumentException::class.java) { tile.pixelAt(0, -1) }
    }

    @Test
    fun tileOfPixels_readsThroughGivenStorage() {
        // 힙 밖 비트맵 같은 다른 저장소도 같은 방식으로 읽는다
        val storage = object : TilePixels {
            override fun pixelAt(x: Int, y: Int): Int = x * 1000 + y
        }
        val tile = Tile.of(storage)

        assertEquals(3 * 1000 + 4, tile.pixelAt(3, 4))
        // 범위 검사는 저장소가 아니라 타일이 맡는다
        assertThrows(IllegalArgumentException::class.java) { tile.pixelAt(TILE_SIZE, 0) }
    }

    @Test
    fun solidTile_knowsItsColor_andOtherTilesDoNot() {
        val tile = Tile.solid(0xFF336699.toInt())

        assertEquals(0xFF336699.toInt(), tile.solidColor)
        assertEquals(0xFF336699.toInt(), tile.pixelAt(TILE_SIZE - 1, TILE_SIZE - 1))
        assertNull(Tile.of(IntArray(TILE_PIXEL_COUNT)).solidColor)
    }

    @Test
    fun byteCount_isAFullTileByDefault() {
        assertEquals(TILE_PIXEL_COUNT * 4, Tile.of(IntArray(TILE_PIXEL_COUNT)).byteCount)
    }

    @Test
    fun tileKey_canSitOutsideTheCanvas() {
        // 이동 도구가 캔버스 밖으로 밀어낸 픽셀이 남는 자리(결정 기록 '캔버스 밖 픽셀')
        val raster = Raster.EMPTY.withTiles(mapOf(TileKey(-1, 0) to solidTile(1), TileKey(5, -2) to solidTile(2)))

        assertEquals(PixelRect(-256, -512, 1536, 256), raster.extent())
        assertTrue(raster.hasTilesIn(PixelRect(-10, 10, -5, 20)))
        assertFalse(raster.hasTilesIn(PixelRect(0, 0, 256, 256)))
    }

    @Test
    fun tileSize_isStoredAndOutsideItIsClear() {
        val tile = Tile.of(IntArray(TILE_PIXEL_COUNT) { 0xFF112233.toInt() }, 100, 40)

        assertEquals(100, tile.width)
        assertEquals(40, tile.height)
        assertEquals(0xFF112233.toInt(), tile.pixelAt(99, 39))
        // 담은 크기 밖은 투명이다(꽉 찬 배열을 줘도 그 밖의 값은 버린다)
        assertEquals(0, tile.pixelAt(100, 0))
        assertEquals(0, tile.pixelAt(0, 40))
        assertThrows(IllegalArgumentException::class.java) { Tile.of(IntArray(TILE_PIXEL_COUNT), 0, 10) }
        assertThrows(IllegalArgumentException::class.java) { Tile.of(IntArray(TILE_PIXEL_COUNT), 10, TILE_SIZE + 1) }
    }

    @Test
    fun cropped_keepsTheTopLeft_andTheColor() {
        val solid = Tile.solid(0xFF00FF00.toInt())
        val cropped = solid.cropped(30, 200)

        assertEquals(30, cropped.width)
        assertEquals(200, cropped.height)
        assertEquals(0xFF00FF00.toInt(), cropped.solidColor)
        assertEquals(0xFF00FF00.toInt(), cropped.pixelAt(29, 199))
        assertEquals(0, cropped.pixelAt(30, 0))
        // 같은 크기면 자기 자신이고, 더 크게는 자를 수 없다
        assertSame(solid, solid.cropped(TILE_SIZE, TILE_SIZE))
        assertThrows(IllegalArgumentException::class.java) { cropped.cropped(31, 10) }
        val pixels = IntArray(TILE_PIXEL_COUNT) { it }
        val part = Tile.of(pixels).cropped(3, 2)
        assertEquals(TILE_SIZE + 2, part.pixelAt(2, 1))
        assertNull(part.solidColor)
    }

    @Test
    fun tileRect_andOffCanvas_followTheStoredSize() {
        val grid = TileGrid(600, 300)
        // 끝 자리 (2, 1)의 캔버스 안쪽은 88×44
        val honest = Raster.EMPTY.withTiles(mapOf(TileKey(2, 1) to Tile.of(IntArray(TILE_PIXEL_COUNT), 88, 44)))
        assertEquals(PixelRect(512, 256, 600, 300), honest.tileRect(TileKey(2, 1)))
        assertNull(honest.tileRect(TileKey(0, 0)))
        assertFalse(honest.hasOffCanvas(grid))
        // 끝 자리에서 캔버스 밖까지 담았거나, 캔버스 밖 자리에 타일이 있으면 캔버스 밖 픽셀이 있다
        assertTrue(Raster.EMPTY.withTiles(mapOf(TileKey(2, 1) to Tile.of(IntArray(TILE_PIXEL_COUNT), 90, 44))).hasOffCanvas(grid))
        assertTrue(Raster.EMPTY.withTiles(mapOf(TileKey(3, 0) to solidTile(1))).hasOffCanvas(grid))
        assertTrue(Raster.EMPTY.withTiles(mapOf(TileKey(0, -1) to solidTile(1))).hasOffCanvas(grid))
    }

    @Test
    fun withTiles_replacesOnlyChangedTilesAndSharesTheRest() {
        val a = TileKey(0, 0)
        val b = TileKey(1, 0)
        val tileA = solidTile(1)
        val tileB = solidTile(2)
        val newTileA = solidTile(3)
        val original = Raster.EMPTY.withTiles(mapOf(a to tileA, b to tileB))

        val edited = original.withTiles(mapOf(a to newTileA))

        assertNotSame(original, edited)
        assertSame(newTileA, edited.tiles[a])
        // 바뀌지 않은 타일은 복사하지 않고 같은 객체를 함께 쓴다
        assertSame(tileB, edited.tiles[b])
        // 원래 래스터는 그대로다
        assertSame(tileA, original.tiles[a])
    }

    @Test
    fun hasTilesIn_looksOnlyAtOverlappingPlaces() {
        val raster = Raster.EMPTY.withTiles(mapOf(TileKey(1, 0) to solidTile(0xFF0000FF.toInt())))

        assertTrue(raster.hasTilesIn(PixelRect(256, 0, 257, 1)))
        // 왼쪽 타일에서 시작해 오른쪽 타일로 걸친다
        assertTrue(raster.hasTilesIn(PixelRect(200, 100, 300, 200)))
        assertFalse(raster.hasTilesIn(PixelRect(0, 0, 256, 256)))
        assertFalse(raster.hasTilesIn(PixelRect(256, 256, 512, 512)))
        // 빈 사각형
        assertFalse(raster.hasTilesIn(PixelRect(300, 10, 300, 20)))
    }

    @Test
    fun withTiles_nullClearsTile() {
        val a = TileKey(0, 0)
        val b = TileKey(0, 1)
        val raster = Raster.EMPTY.withTiles(mapOf(a to solidTile(1), b to solidTile(2)))

        val cleared = raster.withTiles(mapOf(a to null))

        assertFalse(cleared.tiles.containsKey(a))
        assertSame(raster.tiles[b], cleared.tiles[b])
        assertEquals(2, raster.tiles.size)
    }

    @Test
    fun filled_sharesOneTilePerShape_andCropsTheEdges() {
        val tile = Tile.solid(1)
        // 600×300은 3×2 타일이고, 오른쪽·아래 끝 타일은 캔버스 안쪽(88px·44px)만 담는다
        val grid = TileGrid(600, 300)

        val raster = Raster.filled(grid, tile)

        assertEquals(grid.keys().toSet(), raster.tiles.keys)
        // 안쪽 자리는 받은 타일을 그대로 함께 쓴다
        assertSame(tile, raster.tiles[TileKey(0, 0)])
        assertSame(tile, raster.tiles[TileKey(1, 0)])
        // 끝 자리는 크기마다 자른 타일 한 장(같은 색)이다
        assertSame(raster.tiles[TileKey(0, 1)], raster.tiles[TileKey(1, 1)])
        assertEquals(PixelSize(256, 44), raster.tiles[TileKey(0, 1)]!!.let { PixelSize(it.width, it.height) })
        assertEquals(PixelSize(88, 256), raster.tiles[TileKey(2, 0)]!!.let { PixelSize(it.width, it.height) })
        assertEquals(PixelSize(88, 44), raster.tiles[TileKey(2, 1)]!!.let { PixelSize(it.width, it.height) })
        assertEquals(4, raster.tiles.values.toSet().size)
        for (t in raster.tiles.values) assertEquals(1, t.solidColor)
        assertFalse(raster.hasOffCanvas(grid))
    }

    @Test
    fun filled_changingOneKeyKeepsTheSharedTileElsewhere() {
        val shared = solidTile(1)
        val painted = solidTile(2)
        val raster = Raster.filled(TileGrid(512, 512), shared)

        val edited = raster.withTiles(mapOf(TileKey(1, 0) to painted))

        assertSame(painted, edited.tiles[TileKey(1, 0)])
        assertSame(shared, edited.tiles[TileKey(0, 0)])
        assertSame(shared, edited.tiles[TileKey(1, 1)])
        // 원래 래스터의 그 자리는 그대로다
        assertSame(shared, raster.tiles[TileKey(1, 0)])
    }

    @Test
    fun withTiles_returnsSameRasterWhenNothingChanges() {
        val key = TileKey(0, 0)
        val tile = solidTile(1)
        val raster = Raster.EMPTY.withTiles(mapOf(key to tile))

        assertSame(raster, raster.withTiles(emptyMap()))
        assertSame(raster, raster.withTiles(mapOf(key to tile)))
        // 원래 비어 있던 자리를 비우는 것도 변화가 아니다
        assertSame(raster, raster.withTiles(mapOf(TileKey(5, 5) to null)))
    }
}
