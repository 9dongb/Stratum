package com.kiln.stratum.engine.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TileGridTest {

    @Test
    fun onePixelCanvas_hasOneClippedTile() {
        val grid = TileGrid(1, 1)

        assertEquals(1, grid.columns)
        assertEquals(1, grid.rows)
        assertEquals(listOf(TileKey(0, 0)), grid.keys())
        assertEquals(TileBounds(0, 0, 1, 1), grid.bounds(TileKey(0, 0)))
    }

    @Test
    fun exactTileSize_hasOneFullTile() {
        val grid = TileGrid(TILE_SIZE, TILE_SIZE)

        assertEquals(1, grid.columns)
        assertEquals(1, grid.rows)
        assertEquals(TileBounds(0, 0, TILE_SIZE, TILE_SIZE), grid.bounds(TileKey(0, 0)))
    }

    @Test
    fun onePixelOverTileSize_addsClippedTile() {
        val grid = TileGrid(TILE_SIZE + 1, TILE_SIZE + 1)

        assertEquals(2, grid.columns)
        assertEquals(2, grid.rows)
        // 오른쪽·아래 끝 타일은 캔버스 안쪽 1픽셀만 차지한다
        assertEquals(TileBounds(TILE_SIZE, 0, 1, TILE_SIZE), grid.bounds(TileKey(1, 0)))
        assertEquals(TileBounds(0, TILE_SIZE, TILE_SIZE, 1), grid.bounds(TileKey(0, 1)))
        assertEquals(TileBounds(TILE_SIZE, TILE_SIZE, 1, 1), grid.bounds(TileKey(1, 1)))
    }

    @Test
    fun fold8Photo_has32By24Tiles() {
        // 폴드8 50MP 사진(8160×6120)
        val grid = TileGrid(8160, 6120)

        assertEquals(32, grid.columns)
        assertEquals(24, grid.rows)
        assertEquals(768, grid.keys().size)
        assertEquals(TileBounds(7936, 5888, 224, 232), grid.bounds(TileKey(31, 23)))
    }

    @Test
    fun keys_goRowByRowFromTopLeft() {
        val grid = TileGrid(TILE_SIZE * 3, TILE_SIZE * 2)

        assertEquals(
            listOf(TileKey(0, 0), TileKey(1, 0), TileKey(2, 0), TileKey(0, 1), TileKey(1, 1), TileKey(2, 1)),
            grid.keys()
        )
    }

    @Test
    fun bounds_rejectsTileOutsideGrid() {
        val grid = TileGrid(TILE_SIZE, TILE_SIZE)

        assertThrows(IllegalArgumentException::class.java) { grid.bounds(TileKey(1, 0)) }
        assertThrows(IllegalArgumentException::class.java) { grid.bounds(TileKey(0, 1)) }
    }

    @Test
    fun init_rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) { TileGrid(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { TileGrid(1, -1) }
    }
}
