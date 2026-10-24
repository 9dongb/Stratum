package com.kiln.stratum.engine.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        assertThrows(IllegalArgumentException::class.java) { grid.bounds(TileKey(-1, 0)) }
        assertThrows(IllegalArgumentException::class.java) { grid.bounds(TileKey(0, -1)) }
    }

    @Test
    fun canvasRect_andSolidShape_followTheCanvas() {
        val grid = TileGrid(600, 300)

        assertTrue(grid.contains(TileKey(2, 1)))
        assertFalse(grid.contains(TileKey(3, 0)))
        assertFalse(grid.contains(TileKey(-1, 0)))
        assertEquals(PixelRect(512, 256, 600, 300), grid.canvasRect(TileKey(2, 1)))
        assertNull(grid.canvasRect(TileKey(0, 2)))
        // 한 가지 색 타일: 끝 자리는 캔버스 안쪽 크기, 안쪽·캔버스 밖 자리는 꽉 찬 타일
        assertEquals(PixelSize(88, 44), grid.solidShape(TileKey(2, 1)))
        assertEquals(PixelSize(256, 44), grid.solidShape(TileKey(0, 1)))
        assertEquals(PixelSize(256, 256), grid.solidShape(TileKey(0, 0)))
        assertEquals(PixelSize(256, 256), grid.solidShape(TileKey(-3, 7)))
    }

    @Test
    fun keysAndTilesOfAnyRect_useFloorDivision() {
        assertEquals(TileKey(-1, -1), tileAt(-1, -256))
        assertEquals(TileKey(-2, 0), tileAt(-257, 255))
        assertEquals(TileKey(1, 0), tileAt(256, 0))
        assertEquals(PixelRect(-256, 512, 0, 768), tileSquare(TileKey(-1, 2)))
        assertEquals(listOf(TileKey(-1, -1), TileKey(0, -1), TileKey(-1, 0), TileKey(0, 0)), tileKeysIn(PixelRect(-10, -10, 10, 10)))
        assertEquals(listOf(TileKey(0, 0)), tileKeysIn(PixelRect(0, 0, 256, 256)))
        assertTrue(tileKeysIn(PixelRect(5, 5, 5, 9)).isEmpty())
    }

    @Test
    fun init_rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) { TileGrid(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { TileGrid(1, -1) }
    }
}
