package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.Raster
import com.kiln.stratum.engine.raster.TILE_PIXEL_COUNT
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.Tile
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

    /** 캔버스 [size]를 꼭 덮는 불투명한 사진 레이어(한 가지 색 타일, 끝 타일은 캔버스 안쪽 크기) */
    private fun photo(size: PixelSize): Raster {
        val grid = TileGrid(size.width, size.height)
        return Raster.EMPTY.withTiles(grid.keys().associateWith { key -> grid.bounds(key).let { Tile.solid(0xFF808080.toInt(), it.width, it.height) } })
    }

    /** 픽셀 타일이 담은 크기(마스크를 쓰는 픽셀 레이어, [MaskTransform.MaskUsers]) */
    private fun extents(raster: Raster): Map<TileKey, PixelSize> = raster.tiles.mapValues { (_, tile) -> PixelSize(tile.width, tile.height) }

    /** 픽셀 레이어의 마스크 [mask]와 픽셀 [raster]를 [plan]대로 함께 옮긴다(이동 도구·이미지 회전과 같다) */
    private fun movedWith(mask: Selection, raster: Raster, plan: TransformPlan): Pair<Selection, Raster> =
        MaskTransform.transform(mask, plan, composer, users = MaskTransform.MaskUsers(extents(raster), adjustment = false)) to RasterTransform.transform(raster, plan, composer)

    @Test
    fun movedRevealAllMask_revealsTheEmptiedStrip_andCoversThePixelsThatLeaveTheCanvas() {
        // 다 보이는 마스크는 옮겨서 빈 자리도 보인다(포토샵 마스크의 기본색처럼, 결정 기록 '피부 보정'의 1i)
        val (moved, pixels) = movedWith(Selection.all(size), photo(size), TransformPlan.translate(size, 37, -20))

        val grid = TileGrid(size.width, size.height)
        assertTrue(grid.keys().all { moved.tiles[it] === MaskTile.FULL })
        // 캔버스 밖으로 나간 픽셀(37..637 × -20..280)은 마스크도 따라가 보인다
        assertEquals(255, moved.alphaAt(100, -1))
        assertEquals(255, moved.alphaAt(100, -20))
        assertEquals(255, moved.alphaAt(636, 100))
        assertEquals(255, moved.alphaAt(636, -20))
        // 캔버스 밖 마스크 타일은 픽셀이 있는 자리에만 둔다(픽셀이 없는 곳의 마스크는 보이는 것을 바꾸지 않는다)
        assertTrue(moved.tiles.keys.filterNot { grid.contains(it) }.all { it in pixels.tiles })
        assertEquals(0, moved.alphaAt(800, 100))
    }

    @Test
    fun hiddenTile_staysHidden_afterMovingOffTheCanvasAndBack() {
        // 다 보이는 마스크의 안쪽 타일 (1, 1)을 다 가렸다(타일 없음). 캔버스 밖으로 밀었다가 되들여도 가린 채다(검토에서 고침:
        // 캔버스 밖에서 그 자리를 모르는 곳으로 보아, 되들일 때 빈 자리처럼 보이게 채웠다).
        var mask = withHole
        var pixels = photo(holeSize)
        for (dx in listOf(-512, 512)) {
            val (m, p) = movedWith(mask, pixels, TransformPlan.translate(holeSize, dx, 0))
            mask = m
            pixels = p
            if (dx < 0) assertEquals(0, mask.alphaAt(-100, 300))
        }

        assertAlphas(mask, holeSize) { x, y -> if (inHole(x, y)) 0 else 255 }
        assertTrue(MaskTransform.revealsOutside(mask, holeSize))
    }

    @Test
    fun adjustmentMask_keepsAHiddenTileHidden_offTheCanvasAndBack() {
        // 조정 레이어의 바깥이 보이는 마스크는 캔버스 밖에서 타일이 없으면 보임이라, 캔버스 밖으로 나간 가린 타일은 0인 타일로 적어 둔다
        val pushed = MaskTransform.transform(withHole, TransformPlan.translate(holeSize, -512, 0), composer, infinite = true)

        assertSame(composer.emptyMask(), pushed.tiles[TileKey(-1, 1)])
        assertEquals(0, pushed.alphaAt(-100, 300))
        // 다 보이는 캔버스 밖 타일은 두지 않는다(바깥과 같다)
        assertEquals(null, pushed.tiles[TileKey(-2, 1)])
        val back = MaskTransform.transform(pushed, TransformPlan.translate(holeSize, 512, 0), composer, infinite = true)
        assertAlphas(back, holeSize) { x, y -> if (inHole(x, y)) 0 else 255 }
        assertTrue(MaskTransform.revealsOutside(back, holeSize))
    }

    @Test
    fun flippedRevealAllMask_leavesNoJunkPastTheCanvas_soALaterMoveRevealsTheEmptiedStrip() {
        // 끝 자리 다 보이는 타일이 캔버스 밖까지 담은 몫(1000..1024)은 픽셀이 없으면 옮기지 않는다. 전에는 뒤집으면 왼쪽 캔버스 밖에 0이 섞인 타일이 생겨,
        // 그 뒤 오른쪽으로 옮기면 캔버스 왼쪽 끝 띠가 가려졌다(검토에서 고침).
        val (flipped, pixels) = movedWith(Selection.all(holeSize), photo(holeSize), TransformPlan.orient(holeSize, Orientation.FLIP_HORIZONTAL))

        assertTrue(flipped.tiles.keys.all { TileGrid(holeSize.width, holeSize.height).contains(it) })
        val (moved, _) = movedWith(flipped, pixels, TransformPlan.translate(holeSize, 100, 0))
        assertAlphas(moved, holeSize) { _, _ -> 255 }
    }

    @Test
    fun revealAllMask_movedOutThenFurther_writesNoZerosForTheUnknownPartOfAnOffCanvasTile() {
        // 왼쪽으로 100 옮기면 캔버스 밖 타일 (-1, r)은 꽉 찬 크기로 만들어진다. 원본 값이 오지 않는 앞쪽(-256..-100)은 0이 아니라 바깥(보임)으로 채워,
        // 다시 오른쪽으로 200 옮겨 캔버스에 들여도 빈 자리처럼 보인다(검토에서 고침)
        var mask = Selection.all(holeSize)
        var pixels = photo(holeSize)
        for (dx in listOf(-100, 200)) {
            val (m, p) = movedWith(mask, pixels, TransformPlan.translate(holeSize, dx, 0))
            mask = m
            pixels = p
        }

        assertAlphas(mask, holeSize) { _, _ -> 255 }
    }

    @Test
    fun maskSharedWithAnAdjustmentLayer_keepsOffCanvasTilesWithoutPixels() {
        // 선택 영역 모양 마스크(가장자리 가림, 안쪽 (1, 1)만 보임)를 왼쪽으로 512 밀면 보이는 타일은 캔버스 밖 (-1, 1)로 나간다
        val inner = Selection.of(mapOf(TileKey(1, 1) to MaskTile.FULL))
        val plan = TransformPlan.translate(holeSize, -512, 0)

        // 조정 레이어도 쓰는 마스크(피부 마스크처럼)는 픽셀이 없어도 남긴다(조정은 끝이 없어 어디서나 쓰임). 픽셀 레이어만 쓰면 픽셀이 없는 곳이라 남기지 않는다.
        val shared = MaskTransform.transform(inner, plan, composer, users = MaskTransform.MaskUsers(emptyMap(), adjustment = true))
        assertSame(MaskTile.FULL, shared.tiles[TileKey(-1, 1)])
        val pixelsOnly = MaskTransform.transform(inner, plan, composer, users = MaskTransform.MaskUsers(emptyMap(), adjustment = false))
        assertTrue(pixelsOnly.isEmpty)
    }

    @Test
    fun pixelLayerMask_keepsOffCanvasTilesOnlyWherePixelsGo() {
        // 왼쪽 두 열에만 픽셀이 있는 레이어: 오른쪽으로 600 옮기면 픽셀이 없는 오른쪽 마스크는 캔버스 밖에 남기지 않는다
        val half = Raster.EMPTY.withTiles(photo(holeSize).tiles.filterKeys { it.column < 2 })

        val (moved, pixels) = movedWith(Selection.all(holeSize), half, TransformPlan.translate(holeSize, 600, 0))

        val offCanvas = moved.tiles.keys.filterNot { TileGrid(holeSize.width, holeSize.height).contains(it) }
        assertTrue(offCanvas.isNotEmpty())
        assertTrue(offCanvas.all { it in pixels.tiles })
        // 캔버스 안은 그대로 다 보인다(옮긴 왼쪽 마스크 + 빈 자리)
        assertAlphas(moved, holeSize) { _, _ -> 255 }
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
