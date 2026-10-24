package com.kiln.stratum.engine.selection

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TileKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {

    /** 모든 픽셀이 [alpha]인 힙 타일 */
    private fun tile(alpha: Int): MaskTile = MaskTile.of(ByteArray(TILE_PIXEL_COUNT) { alpha.toByte() })

    /** 픽셀마다 계산하는 테스트용 계산(앱에서는 비트맵을 그린다) */
    private val ops = object : MaskTileOps {
        private fun each(a: MaskTile, b: MaskTile, f: (Int, Int) -> Int): MaskTile =
            MaskTile.of(ByteArray(TILE_PIXEL_COUNT) { i -> f(a.alphaAt(i % 256, i / 256), b.alphaAt(i % 256, i / 256)).toByte() })

        override fun union(a: MaskTile, b: MaskTile) = each(a, b) { x, y -> x + y - x * y / 255 }
        override fun subtract(a: MaskTile, b: MaskTile) = each(a, b) { x, y -> x * (255 - y) / 255 }
        override fun intersect(a: MaskTile, b: MaskTile) = each(a, b) { x, y -> x * y / 255 }
        override fun invert(a: MaskTile) = each(a, a) { x, _ -> 255 - x }
    }

    private val left = TileKey(0, 0)
    private val right = TileKey(1, 0)

    @Test
    fun all_coversEveryTileWithTheSharedFullTile() {
        val all = Selection.all(PixelSize(600, 300))

        assertEquals(6, all.tiles.size)
        assertTrue(all.tiles.values.all { it === MaskTile.FULL })
        assertEquals(0L, all.byteCount)
        assertEquals(255, all.alphaAt(599, 299))
        assertEquals(PixelRect(0, 0, 600, 300), all.tileBounds(PixelSize(600, 300)))
    }

    @Test
    fun emptySelection_readsZero_andHasNoBounds() {
        assertEquals(0, Selection.EMPTY.alphaAt(10, 10))
        assertNull(Selection.EMPTY.tileBounds(PixelSize(100, 100)))
    }

    @Test
    fun replace_keepsOnlyTheShape() {
        val old = Selection.of(mapOf(left to MaskTile.FULL))
        val shape = mapOf(right to tile(100))

        val result = combineSelection(old, shape, SelectionMode.REPLACE, ops)

        assertEquals(setOf(right), result.tiles.keys)
    }

    @Test
    fun add_fullWins_andPartialTilesAreCombined() {
        val half = tile(128)
        val old = Selection.of(mapOf(left to half, right to half))
        val shape = mapOf(left to MaskTile.FULL, right to tile(128))

        val result = combineSelection(old, shape, SelectionMode.ADD, ops)

        assertSame(MaskTile.FULL, result.tiles[left])
        // 128 + 128 - 128 × 128 / 255 = 192
        assertEquals(192, result.tiles.getValue(right).alphaAt(5, 5))
    }

    @Test
    fun subtract_removesFullShape_andCutsHoles() {
        val old = Selection.of(mapOf(left to MaskTile.FULL, right to MaskTile.FULL))
        val shape = mapOf(left to MaskTile.FULL, right to tile(55))

        val result = combineSelection(old, shape, SelectionMode.SUBTRACT, ops)

        assertNull(result.tiles[left])
        assertEquals(200, result.tiles.getValue(right).alphaAt(0, 0))
    }

    @Test
    fun subtract_outsideTheOldSelection_changesNothing() {
        val old = Selection.of(mapOf(left to MaskTile.FULL))

        val result = combineSelection(old, mapOf(right to MaskTile.FULL), SelectionMode.SUBTRACT, ops)

        assertSame(old, result)
    }

    @Test
    fun intersect_keepsOnlyTheOverlap() {
        val old = Selection.of(mapOf(left to MaskTile.FULL, right to tile(255)))
        val shape = mapOf(right to tile(51))

        val result = combineSelection(old, shape, SelectionMode.INTERSECT, ops)

        assertEquals(setOf(right), result.tiles.keys)
        assertEquals(51, result.tiles.getValue(right).alphaAt(9, 9))
    }

    @Test
    fun withTiles_sharesUnchangedTiles_andReturnsItselfWhenNothingChanges() {
        val full = Selection.all(PixelSize(512, 256))

        assertSame(full, full.withTiles(mapOf(left to MaskTile.FULL)))
        val changed = full.withTiles(mapOf(left to null))
        assertEquals(setOf(right), changed.tiles.keys)
    }

    @Test
    fun alphaAt_readsTilesOutsideTheCanvas() {
        // 레이어 마스크는 캔버스 밖으로 밀려난 픽셀 자리에도 타일이 있을 수 있다(결정 기록 '캔버스 밖 픽셀')
        val selection = Selection.of(mapOf(TileKey(-1, 0) to MaskTile.FULL))

        assertEquals(255, selection.alphaAt(-1, 0))
        assertEquals(255, selection.alphaAt(-256, 255))
        assertEquals(0, selection.alphaAt(0, 0))
        assertEquals(0, selection.alphaAt(-257, 0))
        assertEquals(0, selection.alphaAt(-1, -1))
    }
}
