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
