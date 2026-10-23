package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.selection.MaskTile
import com.kiln.stratum.engine.selection.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MaskTransformTest {

    private val composer = HeapTileComposer()

    private val size = PixelSize(600, 300)

    /** 자리마다 다른 알파(1..254). 어느 알파가 어디로 갔는지 알 수 있다. */
    private fun alphaAt(x: Int, y: Int): Int = 1 + (x * 7 + y * 13) % 254

    /** 문서 [size] 전체에 [alphaAt] 무늬를 담은 마스크 */
    private fun patterned(size: PixelSize): Selection {
        val grid = TileGrid(size.width, size.height)
        return Selection.of(grid.keys().associateWith { key ->
            val bounds = grid.bounds(key)
            MaskTile.of(ByteArray(TILE_PIXEL_COUNT) { index ->
                val x = index % TILE_SIZE
                val y = index / TILE_SIZE
                if (x < bounds.width && y < bounds.height) alphaAt(bounds.left + x, bounds.top + y).toByte() else 0
            })
        })
    }

    private fun assertAlphas(mask: Selection, size: PixelSize, expected: (x: Int, y: Int) -> Int) {
        for (y in 0 until size.height) {
            for (x in 0 until size.width) {
                if (mask.alphaAt(x, y) != expected(x, y)) fail("($x, $y) is ${mask.alphaAt(x, y)}, expected ${expected(x, y)}")
            }
        }
    }

    @Test
    fun turnedMask_movesEveryAlphaLikeThePixels() {
        val plan = TransformPlan.orient(size, Orientation.ROTATE_CLOCKWISE)

        val turned = MaskTransform.transform(patterned(size), plan, composer)

        assertAlphas(turned, plan.targetSize) { x, y ->
            val from = Orientation.ROTATE_CLOCKWISE.inverse.mapPixel(x, y, plan.targetSize.width, plan.targetSize.height)
            alphaAt(from.x, from.y)
        }
    }

    @Test
    fun croppedThenFlipped_mask_keepsTheSameAlphas() {
        val rect = PixelRect(100, 40, 520, 290)
        val plan = TransformPlan.cropThenOrient(size, rect, Orientation.FLIP_HORIZONTAL)

        val result = MaskTransform.transform(patterned(size), plan, composer)

        assertAlphas(result, plan.targetSize) { x, y ->
            val from = Orientation.FLIP_HORIZONTAL.inverse.mapPixel(x, y, rect.width, rect.height)
            alphaAt(from.x + rect.left, from.y + rect.top)
        }
    }

    @Test
    fun movedMask_hidesTheEmptiedStrip() {
        val plan = TransformPlan.translate(size, 37, -20)

        val moved = MaskTransform.transform(patterned(size), plan, composer)

        // 옮겨서 빈 자리(왼쪽 37줄, 아래 20줄)는 가린다(0)
        assertAlphas(moved, size) { x, y -> if (x < 37 || y >= size.height - 20) 0 else alphaAt(x - 37, y + 20) }
    }

    @Test
    fun revealAllMask_turnsWithoutNewTiles_andEmptyStaysEmpty() {
        val all = Selection.all(size)

        val turned = MaskTransform.transform(all, TransformPlan.orient(size, Orientation.ROTATE_COUNTERCLOCKWISE), composer)

        assertEquals(0, composer.composedMaskCount)
        assertTrue(turned.tiles.values.all { it === MaskTile.FULL })
        assertEquals(TileGrid(300, 600).keys().toSet(), turned.tiles.keys)
        assertSame(Selection.EMPTY, MaskTransform.transform(Selection.EMPTY, TransformPlan.orient(size, Orientation.FLIP_VERTICAL), composer))
    }

    @Test
    fun movingByWholeTiles_keepsTheSameTileObjects() {
        val mask = patterned(PixelSize(1024, 512))

        val moved = MaskTransform.transform(mask, TransformPlan.translate(PixelSize(1024, 512), TILE_SIZE, 0), composer)

        assertEquals(0, composer.composedMaskCount)
        assertSame(mask.tiles[TileKey(0, 1)], moved.tiles[TileKey(1, 1)])
        assertEquals(null, moved.tiles[TileKey(0, 0)])
    }
}
