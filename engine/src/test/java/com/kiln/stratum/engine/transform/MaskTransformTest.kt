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
    fun revealAllMask_turnsToFullTilesOnTheCanvas_andEmptyStaysEmpty() {
        val all = Selection.all(size)

        val turned = MaskTransform.transform(all, TransformPlan.orient(size, Orientation.ROTATE_COUNTERCLOCKWISE), composer)

        // 캔버스 자리는 모두 다 보이는 타일이다. 끝 자리의 다 보이는 타일이 캔버스 밖까지 담은 몫은 캔버스 밖 자리로 같이 돈다.
        val grid = TileGrid(300, 600)
        assertTrue(grid.keys().all { turned.tiles[it] === MaskTile.FULL })
        assertAlphas(turned, PixelSize(300, 600)) { _, _ -> 255 }
        assertSame(Selection.EMPTY, MaskTransform.transform(Selection.EMPTY, TransformPlan.orient(size, Orientation.FLIP_VERTICAL), composer))
    }

    /** 1000×800(4×4 타일) 다 보이는 마스크에서 안쪽 (1, 1) 타일만 가린 것: 가장자리는 모두 보인다 */
    private val holeSize = PixelSize(1000, 800)
    private val withHole: Selection by lazy { Selection.of(Selection.all(holeSize).tiles - TileKey(1, 1)) }

    private fun inHole(x: Int, y: Int): Boolean = x in TILE_SIZE until 2 * TILE_SIZE && y in TILE_SIZE until 2 * TILE_SIZE

    @Test
    fun revealsOutside_whenEveryBorderTileIsFull() {
        assertTrue(MaskTransform.revealsOutside(Selection.all(size), size))
        assertTrue(MaskTransform.revealsOutside(withHole, holeSize))
        assertEquals(false, MaskTransform.revealsOutside(patterned(size), size))
        assertEquals(false, MaskTransform.revealsOutside(Selection.of(Selection.all(holeSize).tiles - TileKey(0, 2)), holeSize))
    }

    @Test
    fun movedRevealAllMask_revealsTheEmptiedStrip_andKeepsWhatLeavesTheCanvas() {
        // 다 보이는 마스크는 옮겨서 빈 자리도 보인다(포토샵 마스크의 기본색처럼, 결정 기록 '피부 보정'의 1i)
        val moved = MaskTransform.transform(Selection.all(size), TransformPlan.translate(size, 37, -20), composer)

        val grid = TileGrid(size.width, size.height)
        assertTrue(grid.keys().all { moved.tiles[it] === MaskTile.FULL })
        // 캔버스 밖으로 나간 몫(다 보이는 타일들이 담은 0..768 × 0..512를 옮긴 37..805 × -20..492)도 남고, 그 밖은 가린다(꽉 찬 타일이라 부풀지 않음)
        assertEquals(255, moved.alphaAt(100, -1))
        assertEquals(0, moved.alphaAt(100, -21))
        assertEquals(255, moved.alphaAt(804, 100))
        assertEquals(0, moved.alphaAt(805, 100))
    }

    @Test
    fun adjustmentMask_revealsEverythingOutside_withoutTilesPastTheCanvas() {
        // 조정 레이어의 바깥이 보이는 마스크는 끝이 없다: 캔버스 밖 빈 자리도 보이고, 다 보이는 캔버스 밖 타일은 두지 않는다
        val moved = MaskTransform.transform(withHole, TransformPlan.translate(holeSize, 300, 0), composer, infinite = true)

        assertTrue(moved.tiles.keys.all { TileGrid(holeSize.width, holeSize.height).contains(it) })
        assertAlphas(moved, holeSize) { x, y -> if (inHole(x - 300, y)) 0 else 255 }
        // 구멍을 캔버스 밖으로 밀면 그 자리 타일은 남는다(가린 곳이 있다)
        val pushed = MaskTransform.transform(withHole, TransformPlan.translate(holeSize, -400, 0), composer, infinite = true)
        assertEquals(0, pushed.alphaAt(-100, 300))
        assertEquals(255, pushed.alphaAt(-200, 300))
    }

    @Test
    fun movedMaskWithAHole_movesTheHole_andRevealsTheStrip() {
        val moved = MaskTransform.transform(withHole, TransformPlan.translate(holeSize, 37, 20), composer)

        assertAlphas(moved, holeSize) { x, y -> if (x >= 37 && y >= 20 && inHole(x - 37, y - 20)) 0 else 255 }
    }

    @Test
    fun turnedLayerMask_turnsTheWholeMask_andRevealsTheEmptiedPlaces() {
        // 레이어 돌리기: 내용 사각형을 돌려 놓는 옮김을 마스크 전체에 건다(사각형 밖 마스크도 같이 돈다, 결정 기록 '캔버스 밖 픽셀').
        // 원본 값이 오지 않는 캔버스 안 빈 자리는 바깥이 보이는 마스크라 보인다. 그래서 가린 곳은 돌아간 구멍뿐이다.
        val rect = PixelRect(200, 200, 700, 450)
        val plan = TransformPlan.place(holeSize, rect, Orientation.ROTATE_CLOCKWISE, holeSize, 300, 150)

        val turned = MaskTransform.transform(withHole, plan, composer)

        val placed = PixelRect(300, 150, 300 + rect.height, 150 + rect.width)
        assertAlphas(turned, holeSize) { x, y ->
            val from = Orientation.ROTATE_CLOCKWISE.inverse.mapPixel(x - placed.left, y - placed.top, placed.width, placed.height)
            if (inHole(from.x + rect.left, from.y + rect.top)) 0 else 255
        }
    }

    @Test
    fun movingByWholeTiles_keepsTheSameTileObjects() {
        val mask = patterned(PixelSize(1024, 512))

        val moved = MaskTransform.transform(mask, TransformPlan.translate(PixelSize(1024, 512), TILE_SIZE, 0), composer)

        assertEquals(0, composer.composedMaskCount)
        assertSame(mask.tiles[TileKey(0, 1)], moved.tiles[TileKey(1, 1)])
        assertEquals(null, moved.tiles[TileKey(0, 0)])
    }

    @Test
    fun revealAllMask_staysRevealing_andAHiddenSpotComesBack_afterMovingOutAndBack() {
        // 다 보이는 마스크의 안쪽 타일 (2, 1)에 가린 점(문서 562..572 × 356..366)이 있다. 캔버스 밖으로 밀었다가 되들인다(이동 도구, 결정 기록 '캔버스 밖 픽셀').
        val spot = MaskTile.of(ByteArray(TILE_PIXEL_COUNT) { i -> if (i % TILE_SIZE in 50..60 && i / TILE_SIZE in 100..110) 0 else -1 })
        var mask = Selection.of(Selection.all(holeSize).tiles + (TileKey(2, 1) to spot))

        for ((dx, dy) in listOf(600 to 0, 0 to 300, -600 to -300)) {
            mask = MaskTransform.transform(mask, TransformPlan.translate(holeSize, dx, dy), composer)
            // 옮긴 뒤에도 가장자리가 다 보여, 다음에 옮겨 생기는 빈 자리도 보인다
            assertTrue("($dx, $dy)", MaskTransform.revealsOutside(mask, holeSize))
        }

        assertAlphas(mask, holeSize) { x, y -> if (x in 562..572 && y in 356..366) 0 else 255 }
    }
}
