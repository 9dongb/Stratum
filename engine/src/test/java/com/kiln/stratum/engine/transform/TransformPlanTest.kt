package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransformPlanTest {

    /** 끝 타일이 잘리는 크기, 꼭 맞는 크기, 아주 가늘고 긴 크기를 섞었다. */
    private val sizes = listOf(
        PixelSize(1, 1),
        PixelSize(255, 255),
        PixelSize(256, 256),
        PixelSize(257, 257),
        PixelSize(1001, 521),
        PixelSize(16383, 1),
        PixelSize(3, 700)
    )

    @Test
    fun mapPoint_followsCropTurnAndMove_likeThePixels() {
        val source = PixelSize(1000, 600)
        // 왼쪽 위 (100, 50)에서 800×500을 잘라 오른쪽으로 90° 돌린다: 원본 (300, 150)은 자른 그림 (200, 100) → 돌린 그림 (500 − 100, 200)
        val turned = TransformPlan.cropThenOrient(source, PixelRect(100, 50, 900, 550), Orientation.ROTATE_CLOCKWISE)
        assertEquals(400f to 200f, turned.mapPoint(300f, 150f))
        // 좌우 뒤집기는 가로 자리만 뒤집는다
        assertEquals(700f to 150f, TransformPlan.orient(source, Orientation.FLIP_HORIZONTAL).mapPoint(300f, 150f))
        // 옮기기는 그만큼 옮긴다
        assertEquals(330f to 110f, TransformPlan.translate(source, 30, -40).mapPoint(300f, 150f))
    }

    @Test
    fun orient_movesEveryPixelOnce() {
        for (size in sizes) {
            for (orientation in Orientation.entries) {
                val plan = TransformPlan.orient(size, orientation)
                assertEquals(orientation.targetSize(size), plan.targetSize)
                val target = plan.targetSize
                // 대상 픽셀에는 방향을 되돌린 자리의 원본 픽셀이 와야 한다
                assertMovesEveryPixel(plan) { x, y -> orientation.inverse.mapPixel(x, y, target.width, target.height) }
            }
        }
    }

    @Test
    fun crop_takesTheRectangle() {
        val source = PixelSize(1001, 521)
        val rects = listOf(
            PixelRect.of(source),
            PixelRect(0, 0, 256, 256),
            PixelRect(100, 50, 700, 400),
            // 타일 네 장이 만나는 곳
            PixelRect(255, 255, 257, 257),
            // 마지막 픽셀 하나
            PixelRect(1000, 520, 1001, 521)
        )
        for (rect in rects) {
            val plan = TransformPlan.crop(source, rect)
            assertEquals(PixelSize(rect.width, rect.height), plan.targetSize)
            assertEquals(Orientation.IDENTITY, plan.orientation)
            assertMovesEveryPixel(plan) { x, y -> PixelPoint(x + rect.left, y + rect.top) }
        }
    }

    @Test
    fun cropThenOrient_takesTheRectangle_thenTurnsIt() {
        // 자르기·회전 화면의 ✓: 원래 방향에서 자른 뒤 돌린다(사용자 피드백 8차 1)
        val source = PixelSize(1001, 521)
        val rects = listOf(PixelRect.of(source), PixelRect(100, 50, 700, 400), PixelRect(255, 255, 257, 260), PixelRect(1000, 520, 1001, 521))
        for (rect in rects) {
            for (orientation in Orientation.entries) {
                val plan = TransformPlan.cropThenOrient(source, rect, orientation)
                val target = orientation.targetSize(PixelSize(rect.width, rect.height))
                assertEquals(target, plan.targetSize)
                assertEquals(rect, plan.crop)
                // 대상 픽셀에는 방향을 되돌린 자리(자른 사각형 안)의 원본 픽셀이 와야 한다
                assertMovesEveryPixel(plan) { x, y ->
                    val inCrop = orientation.inverse.mapPixel(x, y, target.width, target.height)
                    PixelPoint(inCrop.x + rect.left, inCrop.y + rect.top)
                }
            }
        }
    }

    @Test
    fun crop_atTopLeft_takesWholeTilesFromTheSameKeys() {
        val plan = TransformPlan.crop(PixelSize(1001, 521), PixelRect(0, 0, 600, 300))

        for (tile in plan.targets) {
            // 같은 자리 타일 하나에서, 대상 타일의 캔버스 안쪽만큼 그대로 가져온다
            val part = tile.parts.single()
            assertEquals(tile.key, part.source)
            assertEquals(PixelRect(0, 0, tile.width, tile.height), part.targetRect)
            assertEquals(part.targetRect, part.sourceRect)
        }
    }

    @Test
    fun affine_putsEachSourcePixelOntoItsTargetPixel() {
        val size = PixelSize(601, 301)
        val plans = Orientation.entries.map { TransformPlan.orient(size, it) } + TransformPlan.crop(size, PixelRect(100, 50, 400, 250))
        for (plan in plans) {
            for (tile in plan.targets) {
                for (part in tile.parts) {
                    val m = part.affine(plan.orientation)
                    for (y in part.targetRect.top until part.targetRect.bottom) {
                        for (x in part.targetRect.left until part.targetRect.right) {
                            // 원본 픽셀의 가운데가 대상 픽셀의 가운데로 가야 한다
                            val source = part.sourcePixel(x, y, plan.orientation)
                            val sx = source.x + 0.5
                            val sy = source.y + 0.5
                            val tx = m[0] * sx + m[1] * sy + m[2]
                            val ty = m[3] * sx + m[4] * sy + m[5]
                            if (tx != x + 0.5 || ty != y + 0.5) {
                                fail("${plan.orientation} $part: ($x, $y) <- $source lands on ($tx, $ty)")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun translate_movesEachPixel_andDropsWhatLeavesTheCanvas() {
        val shifts = listOf(0 to 0, 100 to 50, -300 to 7, 256 to -512, 1001 to 0, -2000 to -2000, 3 to 520)
        for (size in listOf(PixelSize(1001, 521), PixelSize(256, 256), PixelSize(3, 700))) {
            for ((dx, dy) in shifts) {
                val plan = TransformPlan.translate(size, dx, dy)
                assertEquals(size, plan.targetSize)
                assertEquals(Orientation.IDENTITY, plan.orientation)
                val grid = TileGrid(size.width, size.height)
                assertEquals(grid.keys(), plan.targets.map { it.key })
                // 대상 픽셀마다 (x - dx, y - dy)의 원본 픽셀이 오고, 그 자리가 이미지 밖이면 아무것도 오지 않는다(투명)
                val covered = IntArray(size.width * size.height)
                for (tile in plan.targets) {
                    for (part in tile.parts) {
                        val sourceBounds = grid.bounds(part.source)
                        assertTrue("$part", part.sourceRect in PixelRect(0, 0, sourceBounds.width, sourceBounds.height))
                        assertTrue("$part", part.targetRect in PixelRect(0, 0, tile.width, tile.height))
                        for (y in part.targetRect.top until part.targetRect.bottom) {
                            for (x in part.targetRect.left until part.targetRect.right) {
                                val local = part.sourcePixel(x, y, plan.orientation)
                                val targetX = tile.key.column * TILE_SIZE + x
                                val targetY = tile.key.row * TILE_SIZE + y
                                assertEquals(PixelPoint(targetX - dx, targetY - dy), PixelPoint(sourceBounds.left + local.x, sourceBounds.top + local.y))
                                covered[targetY * size.width + targetX]++
                            }
                        }
                    }
                }
                for (y in 0 until size.height) {
                    for (x in 0 until size.width) {
                        val inside = x - dx in 0 until size.width && y - dy in 0 until size.height
                        val count = covered[y * size.width + x]
                        if (count != if (inside) 1 else 0) fail("$size by ($dx, $dy): ($x, $y) covered $count times")
                    }
                }
            }
        }
    }

    @Test
    fun translate_byWholeTiles_takesWholeTiles_andTellsWhatIsCovered() {
        val plan = TransformPlan.translate(PixelSize(1024, 512), 256, 0)

        for (tile in plan.targets) {
            if (tile.key.column == 0) {
                // 왼쪽 끝 줄은 옮겨서 빈 자리다
                assertTrue(tile.parts.isEmpty())
                assertTrue(!tile.isCovered)
            } else {
                val part = tile.parts.single()
                assertEquals(tile.key.copy(column = tile.key.column - 1), part.source)
                assertEquals(PixelRect(0, 0, TILE_SIZE, TILE_SIZE), part.sourceRect)
                assertEquals(part.sourceRect, part.targetRect)
                assertTrue(tile.isCovered)
            }
        }
        // 조금 옮기면 왼쪽 끝 타일은 일부만 덮인다
        assertTrue(!TransformPlan.translate(PixelSize(1024, 512), 10, 0).targets.first().isCovered)
    }

    @Test
    fun place_turnsTheRect_putsItThere_andDropsWhatLeavesTheCanvas() {
        // 레이어 돌리기(사용자 피드백 12차 2): 같은 캔버스 안에서 내용 사각형을 돌려 그 가운데에 놓는다
        val size = PixelSize(601, 301)
        val rects = listOf(PixelRect.of(size), PixelRect(100, 50, 400, 250), PixelRect(255, 255, 258, 263), PixelRect(600, 300, 601, 301))
        val origins = listOf(0 to 0, 37 to -20, -300 to 260, 500 to 280, -2000 to 0)
        for (rect in rects) {
            for (orientation in Orientation.entries) {
                val turned = orientation.targetSize(PixelSize(rect.width, rect.height))
                for ((left, top) in origins + listOf(orientation.turnedOrigin(rect).let { it.x to it.y })) {
                    val plan = TransformPlan.place(size, rect, orientation, size, left, top)
                    assertEquals(size, plan.targetSize)
                    assertEquals(orientation, plan.orientation)
                    val covered = IntArray(size.width * size.height)
                    forEachTargetPixel(plan) { x, y, source ->
                        // 놓은 자리에서 방향을 되돌린 [rect] 안의 원본 픽셀이 온다
                        val inRect = orientation.inverse.mapPixel(x - left, y - top, turned.width, turned.height)
                        val expected = PixelPoint(inRect.x + rect.left, inRect.y + rect.top)
                        if (source != expected) fail("$rect $orientation at ($left, $top): ($x, $y) took $source, expected $expected")
                        covered[y * size.width + x]++
                    }
                    for (y in 0 until size.height step 7) {
                        for (x in 0 until size.width step 5) {
                            val inside = x - left in 0 until turned.width && y - top in 0 until turned.height
                            val count = covered[y * size.width + x]
                            if (count != if (inside) 1 else 0) fail("$rect $orientation at ($left, $top): ($x, $y) covered $count times")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun place_intoADifferentSizedCanvas_putsTheWholeImageAt() {
        // 사진 넣기(사용자 피드백 12차)처럼 다른 크기 이미지를 캔버스 가운데에 놓는다
        val image = PixelSize(300, 200)
        val canvas = PixelSize(1000, 600)
        val plan = TransformPlan.place(image, PixelRect.of(image), Orientation.IDENTITY, canvas, 350, 200)
        assertEquals(canvas, plan.targetSize)
        var count = 0
        forEachTargetPixel(plan) { x, y, source ->
            assertEquals(PixelPoint(x - 350, y - 200), source)
            count++
        }
        assertEquals(300 * 200, count)
    }

    @Test
    fun place_rejectsEmptyOrOutsideRect() {
        val source = PixelSize(100, 50)
        assertThrows(IllegalArgumentException::class.java) {
            TransformPlan.place(source, PixelRect(10, 10, 10, 20), Orientation.IDENTITY, source, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransformPlan.place(source, PixelRect(90, 40, 101, 50), Orientation.ROTATE_CLOCKWISE, source, 0, 0)
        }
    }

    /** 계획의 대상 픽셀(캔버스 좌표)마다 그 픽셀로 오는 원본 픽셀(이미지 좌표)을 알려 준다. 조각이 타일 안쪽에 있는지도 본다. */
    private inline fun forEachTargetPixel(plan: TransformPlan, action: (x: Int, y: Int, source: PixelPoint) -> Unit) {
        val sourceGrid = TileGrid(plan.sourceSize.width, plan.sourceSize.height)
        val targetGrid = TileGrid(plan.targetSize.width, plan.targetSize.height)
        assertEquals(targetGrid.keys(), plan.targets.map { it.key })
        for (tile in plan.targets) {
            for (part in tile.parts) {
                val sourceBounds = sourceGrid.bounds(part.source)
                assertTrue("$part", part.sourceRect in PixelRect(0, 0, sourceBounds.width, sourceBounds.height))
                assertTrue("$part", part.targetRect in PixelRect(0, 0, tile.width, tile.height))
                for (y in part.targetRect.top until part.targetRect.bottom) {
                    for (x in part.targetRect.left until part.targetRect.right) {
                        val local = part.sourcePixel(x, y, plan.orientation)
                        action(
                            tile.key.column * TILE_SIZE + x,
                            tile.key.row * TILE_SIZE + y,
                            PixelPoint(sourceBounds.left + local.x, sourceBounds.top + local.y)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun crop_rejectsEmptyOrOutsideRect() {
        val source = PixelSize(100, 50)

        assertThrows(IllegalArgumentException::class.java) { TransformPlan.crop(source, PixelRect(10, 10, 10, 20)) }
        assertThrows(IllegalArgumentException::class.java) { TransformPlan.crop(source, PixelRect(-1, 0, 10, 10)) }
        assertThrows(IllegalArgumentException::class.java) { TransformPlan.crop(source, PixelRect(90, 40, 101, 50)) }
    }

    /**
     * 계획대로 옮겼을 때 대상 픽셀마다 오는 원본 픽셀이 [expectedSource]와 같고, 대상 픽셀이 한 번씩만 덮이는지 확인한다.
     * 조각은 원본·대상 타일의 캔버스 안쪽에만 있어야 한다.
     */
    private fun assertMovesEveryPixel(plan: TransformPlan, expectedSource: (x: Int, y: Int) -> PixelPoint) {
        val target = plan.targetSize
        val sourceGrid = TileGrid(plan.sourceSize.width, plan.sourceSize.height)
        val targetGrid = TileGrid(target.width, target.height)
        assertEquals(targetGrid.keys(), plan.targets.map { it.key })
        val covered = IntArray(target.width * target.height)
        for (tile in plan.targets) {
            val targetBounds = targetGrid.bounds(tile.key)
            assertEquals(targetBounds.width, tile.width)
            assertEquals(targetBounds.height, tile.height)
            for (part in tile.parts) {
                val sourceBounds = sourceGrid.bounds(part.source)
                assertTrue("$part", part.sourceRect in PixelRect(0, 0, sourceBounds.width, sourceBounds.height))
                assertTrue("$part", part.targetRect in PixelRect(0, 0, tile.width, tile.height))
                for (y in part.targetRect.top until part.targetRect.bottom) {
                    for (x in part.targetRect.left until part.targetRect.right) {
                        val local = part.sourcePixel(x, y, plan.orientation)
                        val source = PixelPoint(sourceBounds.left + local.x, sourceBounds.top + local.y)
                        val targetX = tile.key.column * TILE_SIZE + x
                        val targetY = tile.key.row * TILE_SIZE + y
                        val expected = expectedSource(targetX, targetY)
                        if (source != expected) {
                            fail("${plan.orientation} ${plan.sourceSize}: ($targetX, $targetY) took $source, expected $expected")
                        }
                        covered[targetY * target.width + targetX]++
                    }
                }
            }
        }
        val wrong = covered.indexOfFirst { it != 1 }
        if (wrong >= 0) {
            fail("${plan.orientation} ${plan.sourceSize}: pixel $wrong covered ${covered[wrong]} times")
        }
    }
}
