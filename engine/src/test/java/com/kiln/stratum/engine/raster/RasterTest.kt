package com.kiln.stratum.engine.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
    fun tileKey_rejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) { TileKey(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TileKey(0, -1) }
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
    fun filled_putsSameTileAtEveryGridKey() {
        val tile = solidTile(1)
        // 600×300은 3×2 타일이고, 오른쪽·아래 끝은 캔버스 밖으로 조금 넘친다
        val grid = TileGrid(600, 300)

        val raster = Raster.filled(grid, tile)

        assertEquals(grid.keys().toSet(), raster.tiles.keys)
        // 타일을 복사하지 않고 모든 자리가 한 객체를 함께 쓴다
        for (key in grid.keys()) assertSame(tile, raster.tiles[key])
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
