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

    @Test
    fun blocksOfAnArea_reachPastTheCanvas_onTheSameGrid() {
        // 캔버스 밖 픽셀이 있는 레이어(결정 기록 '캔버스 밖 픽셀'): 대상 (-300, -10)~(1100, 500)
        val plan = ResizePlan(PixelSize(2000, 1000), PixelSize(1000, 500))
        val area = PixelRect(-300, -10, 1100, 500)
        val blocks = plan.blocks(area)

        // 대상 (0, 0)에 맞춘 1024 격자를 영역으로 자른 것이고, 빈틈없이 한 번씩 덮는다
        assertEquals(
            listOf(
                PixelRect(-300, -10, 0, 0), PixelRect(0, -10, 1024, 0), PixelRect(1024, -10, 1100, 0),
                PixelRect(-300, 0, 0, 500), PixelRect(0, 0, 1024, 500), PixelRect(1024, 0, 1100, 500)
            ),
            blocks
        )
        assertEquals(area.width.toLong() * area.height, blocks.sumOf { it.width.toLong() * it.height })
        // 캔버스 안 묶음은 캔버스만 볼 때와 같은 격자에서 시작한다
        assertEquals(plan.blocks().first().left, blocks[4].left)
        assertEquals(plan.blocks().first().top, blocks[4].top)
        assertTrue(plan.blocks(PixelRect(5, 5, 5, 9)).isEmpty())
    }

    @Test
    fun sourceRectAndTargetArea_workPastTheCanvas() {
        val plan = ResizePlan(PixelSize(2000, 1000), PixelSize(1000, 500))
        val within = PixelRect(-600, -20, 2200, 1000)
        // 원본 x = -100인 픽셀은 반으로 줄이면 대상 x = -50이다(캔버스 (0, 0)이 기준)
        assertEquals(PixelRect(-50, -10, 1100, 500), plan.targetArea(PixelRect(-100, -20, 2200, 1000)))
        val source = plan.sourceRect(PixelRect(-300, -10, 0, 500), within)
        assertTrue(source.left in -606..-600)
        assertEquals(within.top, source.top)
        assertTrue(source.right > 0)
        // 캔버스만 볼 때는 전처럼 캔버스 안으로 자른다
        assertEquals(0, plan.sourceRect(PixelRect(0, 0, 100, 100)).left)
    }

    @Test
    fun targetArea_ofTheCanvas_isExactlyTheTargetCanvas() {
        // 실수 배율을 곱하면 3000 → 757처럼 캔버스 끝이 1 크게 올림됐다(캔버스 밖 픽셀이 있는 레이어에 가짜 한 줄, 검토에서 고침)
        for (source in listOf(3000, 1000, 4096, 8160, 6120, 999)) {
            for (target in listOf(757, 501, 1, 333, 1234, 2999, 8191, 12000)) {
                val plan = ResizePlan(PixelSize(source, source / 2 + 1), PixelSize(target, target / 3 + 1))
                assertEquals("$source -> $target", PixelRect(0, 0, target, target / 3 + 1), plan.targetArea(PixelRect(0, 0, source, source / 2 + 1)))
            }
        }
        // 음수 자리는 내림·올림이 바깥쪽이다
        val plan = ResizePlan(PixelSize(3000, 3000), PixelSize(757, 757))
        assertEquals(PixelRect(-26, -26, 1, 1), plan.targetArea(PixelRect(-100, -100, 1, 1)))
    }
}
