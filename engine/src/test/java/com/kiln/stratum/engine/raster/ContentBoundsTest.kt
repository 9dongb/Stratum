package com.kiln.stratum.engine.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 레이어 내용을 담는 사각형(레이어 돌리기·뒤집기의 축, 사용자 피드백 12차 2) */
class ContentBoundsTest {

    private val black = 0xFF000000.toInt()

    /** [points](문서 좌표)만 [color]인 래스터. 점이 없는 타일은 두지 않는다. */
    private fun dots(vararg points: Pair<Int, Int>, color: Int = black): Raster {
        val tiles = HashMap<TileKey, IntArray>()
        for ((x, y) in points) {
            val pixels = tiles.getOrPut(TileKey(x / TILE_SIZE, y / TILE_SIZE)) { IntArray(TILE_PIXEL_COUNT) }
            pixels[(y % TILE_SIZE) * TILE_SIZE + x % TILE_SIZE] = color
        }
        return Raster.EMPTY.withTiles(tiles.mapValues { Tile.of(it.value) })
    }

    @Test
    fun emptyOrClearLayers_haveNoContent() {
        val size = PixelSize(1000, 600)
        assertNull(Raster.EMPTY.contentBounds(size))
        // 타일은 있어도 다 투명하면(지운 뒤) 내용이 없다
        assertNull(Raster.EMPTY.withTiles(mapOf(TileKey(1, 1) to Tile.of(IntArray(TILE_PIXEL_COUNT)))).contentBounds(size))
        assertNull(Raster.filled(TileGrid(1000, 600), Tile.solid(0)).contentBounds(size))
    }

    @Test
    fun aFewPixels_spanningTiles() {
        val size = PixelSize(1000, 600)
        assertEquals(PixelRect(300, 40, 301, 41), dots(300 to 40).contentBounds(size))
        assertEquals(PixelRect(10, 255, 701, 513), dots(10 to 300, 700 to 255, 400 to 512).contentBounds(size))
        // 반투명한 픽셀도 내용이다
        assertEquals(PixelRect(5, 6, 6, 7), dots(5 to 6, color = 0x01FFFFFF).contentBounds(size))
    }

    @Test
    fun clearTilesOnTheOutside_areSkippedInward() {
        // 바깥 줄의 타일이 다 투명하면 안쪽 줄로 들어가 찾는다
        val size = PixelSize(1000, 600)
        val clear = Tile.of(IntArray(TILE_PIXEL_COUNT))
        val raster = dots(520 to 300, 600 to 310).withTiles(
            mapOf(TileKey(0, 0) to clear, TileKey(3, 2) to clear, TileKey(0, 2) to clear)
        )
        assertEquals(PixelRect(520, 300, 601, 311), raster.contentBounds(size))
    }

    @Test
    fun solidTiles_coverTheCanvasInsideOnly() {
        // 한 가지 색 새 캔버스: 끝 타일의 캔버스 밖 부분은 내용이 아니다
        val size = PixelSize(1000, 600)
        assertEquals(PixelRect.of(size), Raster.filled(TileGrid(1000, 600), Tile.solid(black)).contentBounds(size))
        // 꽉 찬 사진도 캔버스 전체다
        val photo = Raster.EMPTY.withTiles(TileGrid(1000, 600).keys().associateWith { Tile.of(IntArray(TILE_PIXEL_COUNT) { black }) })
        assertEquals(PixelRect.of(size), photo.contentBounds(size))
    }

    @Test
    fun opaqueRect_findsTheSmallestRectangle() {
        val opaque = setOf(3 to 1, 0 to 4, 2 to 2)
        assertEquals(PixelRect(0, 1, 4, 5), opaqueRect(5, 6) { x, y -> x to y in opaque })
        assertNull(opaqueRect(5, 6) { _, _ -> false })
        assertEquals(PixelRect(0, 0, 5, 6), opaqueRect(5, 6) { _, _ -> true })
    }
}
