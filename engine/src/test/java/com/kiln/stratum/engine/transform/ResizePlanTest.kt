package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizePlanTest {

    @Test
    fun halvings_leaveAFactorAboveHalf() {
        // 1000 → 250은 정확히 두 번 반으로 줄이면 끝난다
        assertEquals(2, ResizePlan.halvings(1000, 250))
        // 1001 → 250은 두 번 줄인 251에서 조금 더 보간한다
        assertEquals(2, ResizePlan.halvings(1001, 250))
        assertEquals(1, ResizePlan.halvings(1000, 499))
        assertEquals(0, ResizePlan.halvings(1000, 600))
        // 키울 때는 반으로 줄이지 않는다
        assertEquals(0, ResizePlan.halvings(1000, 2000))
        assertEquals(0, ResizePlan.halvings(1, 1))
    }

    @Test
    fun axesHalveSeparately() {
        val plan = ResizePlan(PixelSize(4000, 3000), PixelSize(1000, 3000))

        assertEquals(2, plan.levelX)
        assertEquals(0, plan.levelY)
    }

    @Test
    fun blocks_coverTheTargetOnce() {
        for (target in listOf(PixelSize(1, 1), PixelSize(1025, 3000), PixelSize(16383, 1))) {
            val plan = ResizePlan(PixelSize(500, 500), target)
            val covered = Array(target.height) { IntArray(target.width) }
            for (block in plan.blocks()) {
                assertTrue(block.width <= RESIZE_BLOCK_SIZE && block.height <= RESIZE_BLOCK_SIZE)
                for (y in block.top until block.bottom) for (x in block.left until block.right) covered[y][x]++
            }
            assertTrue("$target", covered.all { row -> row.all { it == 1 } })
        }
    }

    @Test
    fun sourceRect_coversTheMappedAreaWithAMargin() {
        // 반으로 줄이면 대상 묶음 0~1024는 원본 0~2048이고, 둘레 여유는 1단계 픽셀 3개(원본 6픽셀)다
        val plan = ResizePlan(PixelSize(4000, 3000), PixelSize(2000, 1500))

        assertEquals(PixelRect(0, 0, 2054, 2054), plan.sourceRect(PixelRect(0, 0, 1024, 1024)))
        // 원본 끝을 넘지 않는다
        assertEquals(PixelRect(2042, 2042, 4000, 3000), plan.sourceRect(PixelRect(1024, 1024, 2000, 1500)))
    }
}
