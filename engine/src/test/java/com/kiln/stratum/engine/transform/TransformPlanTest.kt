package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import com.kiln.stratum.engine.raster.TILE_SIZE
import com.kiln.stratum.engine.raster.TileGrid
import com.kiln.stratum.engine.raster.TileKey
import com.kiln.stratum.engine.raster.tileSquare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** [size] 캔버스를 캔버스 안쪽 크기 타일로 다 덮은 원본(사진 레이어처럼) */
    private fun canvasTiles(size: PixelSize): Map<TileKey, PixelSize> {
        val grid = TileGrid(size.width, size.height)
        return grid.keys().associateWith { key -> grid.bounds(key).let { PixelSize(it.width, it.height) } }
    }

    /** 캔버스 원본을 옮긴 대상 타일들. 대상 캔버스의 모든 자리를 넣는다(돌리기·자르기는 캔버스 안쪽을 빈틈없이 덮는다). */
    private fun canvasTargets(plan: TransformPlan): List<TargetTile> = plan.targets(canvasTiles(plan.sourceSize), canvasTiles(plan.targetSize))

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
                assertFalse(plan.discardsOutside)
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
            assertTrue(plan.discardsOutside)
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
    fun crop_dropsEverythingOutsideTheRect_evenPastTheCanvas() {
        // 캔버스 밖 픽셀(결정 기록 '캔버스 밖 픽셀')은 자르면 버린다
        val source = PixelSize(1001, 521)
        val sources = canvasTiles(source) + mapOf(TileKey(-1, 0) to PixelSize(256, 256), TileKey(4, 2) to PixelSize(100, 50), TileKey(3, 0) to PixelSize(256, 256))
        val plan = TransformPlan.crop(source, PixelRect(100, 50, 700, 400))

        val targets = plan.targets(sources)

        assertTrue(targets.all { tile -> tile.parts.none { it.source == TileKey(-1, 0) || it.source == TileKey(4, 2) } })
        assertMapsEverySourcePixelOnce(plan, sources, targets)
    }

    @Test
    fun crop_atTopLeft_takesWholeTilesFromTheSameKeys() {
        val plan = TransformPlan.crop(PixelSize(1001, 521), PixelRect(0, 0, 600, 300))

        for (tile in canvasTargets(plan)) {
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
        val plans = Orientation.entries.map { TransformPlan.orient(size, it) } +
            TransformPlan.crop(size, PixelRect(100, 50, 400, 250)) +
            TransformPlan.translate(size, 130, -77) +
            TransformPlan.place(size, PixelRect(-40, 10, 500, 300), Orientation.TRANSVERSE, size, 70, -20)
        for (plan in plans) {
            for (tile in plan.targets(canvasTiles(size) + (TileKey(-1, 1) to PixelSize(200, 256)))) {
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
    fun translate_movesEveryPixel_andKeepsWhatLeavesTheCanvas() {
        // 이동 도구(사용자 피드백 13차 1 "포토샵처럼"): 캔버스 밖으로 나간 픽셀도 버리지 않는다
        val shifts = listOf(0 to 0, 100 to 50, -300 to 7, 256 to -512, 1001 to 0, -2000 to -2000, 3 to 520)
        for (size in listOf(PixelSize(1001, 521), PixelSize(256, 256), PixelSize(3, 700))) {
            for ((dx, dy) in shifts) {
                val plan = TransformPlan.translate(size, dx, dy)
                assertEquals(size, plan.targetSize)
                assertEquals(Orientation.IDENTITY, plan.orientation)
                assertFalse(plan.discardsOutside)
                // 캔버스 밖에 있던 원본 타일도 같이 옮겨 간다
                val sources = canvasTiles(size) + (TileKey(-1, -1) to PixelSize(256, 130))
                assertMapsEverySourcePixelOnce(plan, sources, plan.targets(sources))
            }
        }
    }

    @Test
    fun translate_sizesEachTargetTile_toTheCanvasAndWhatReachesIt() {
        // 600×300 캔버스(끝 열의 캔버스 안쪽 88px)를 오른쪽으로 100 옮기면 끝 열 타일은 캔버스 밖 100px까지 188px을 담는다
        val size = PixelSize(600, 300)
        val targets = TransformPlan.translate(size, 100, 0).targets(canvasTiles(size)).associateBy { it.key }

        assertEquals(setOf(TileKey(0, 0), TileKey(1, 0), TileKey(2, 0), TileKey(0, 1), TileKey(1, 1), TileKey(2, 1)), targets.keys)
        assertEquals(188, targets.getValue(TileKey(2, 0)).width)
        assertEquals(256, targets.getValue(TileKey(2, 0)).height)
        assertEquals(44, targets.getValue(TileKey(2, 1)).height)
        // 왼쪽 끝 열은 캔버스 안쪽(256) 그대로이고 앞 100px은 비었다
        assertEquals(256, targets.getValue(TileKey(0, 0)).width)
        assertFalse(targets.getValue(TileKey(0, 0)).isCovered)
        assertTrue(targets.getValue(TileKey(2, 0)).isCovered)

        // 오른쪽으로 300 옮기면 끝 열 픽셀(512~600)은 812~900으로, 캔버스 밖 자리 (3, 0)에 132px이 간다
        val farther = TransformPlan.translate(size, 300, 0).targets(canvasTiles(size)).associateBy { it.key }
        assertEquals(132, farther.getValue(TileKey(3, 0)).width)
        assertEquals(256, farther.getValue(TileKey(2, 0)).width)
        // 왼쪽 위로 옮기면 캔버스 밖 자리(음수)에 간다
        val back = TransformPlan.translate(size, -10, -20).targets(canvasTiles(size)).associateBy { it.key }
        assertTrue(TileKey(-1, -1) in back)
        assertEquals(PixelSize(256, 256), back.getValue(TileKey(-1, -1)).let { PixelSize(it.width, it.height) })
        assertEquals(PixelRect(246, 236, 256, 256), back.getValue(TileKey(-1, -1)).parts.single().targetRect)
    }

    @Test
    fun translate_byWholeTiles_takesWholeTiles_andTellsWhatIsCovered() {
        val size = PixelSize(1024, 512)
        val targets = TransformPlan.translate(size, 256, 0).targets(canvasTiles(size))

        // 옮겨서 빈 왼쪽 끝 열은 대상 타일이 없고, 오른쪽 끝 열은 캔버스 밖 자리(4)로 간다
        assertEquals(canvasTiles(size).keys.map { it.copy(column = it.column + 1) }.toSet(), targets.map { it.key }.toSet())
        for (tile in targets) {
            val part = tile.parts.single()
            assertEquals(tile.key.copy(column = tile.key.column - 1), part.source)
            assertEquals(PixelRect(0, 0, TILE_SIZE, TILE_SIZE), part.sourceRect)
            assertEquals(part.sourceRect, part.targetRect)
            assertTrue(tile.isCovered)
        }
        // 조금 옮기면 왼쪽 끝 타일은 일부만 덮인다
        assertTrue(!TransformPlan.translate(size, 10, 0).targets(canvasTiles(size)).first().isCovered)
    }

    @Test
    fun place_turnsTheRect_putsItThere_andMovesTheWholePlaneTheSameWay() {
        // 레이어 돌리기(사용자 피드백 12차 2): 내용 사각형을 돌려 그 가운데에 놓고, 사각형 밖·캔버스 밖도 같은 옮김으로 간다
        val size = PixelSize(601, 301)
        val rects = listOf(PixelRect.of(size), PixelRect(100, 50, 400, 250), PixelRect(255, 255, 258, 263), PixelRect(600, 300, 601, 301), PixelRect(-300, -20, 50, 40))
        val origins = listOf(0 to 0, 37 to -20, -300 to 260, 500 to 280, -2000 to 0)
        for (rect in rects) {
            for (orientation in Orientation.entries) {
                val turned = orientation.targetSize(PixelSize(rect.width, rect.height))
                for ((left, top) in origins + listOf(orientation.turnedOrigin(rect).let { it.x to it.y })) {
                    val plan = TransformPlan.place(size, rect, orientation, size, left, top)
                    assertEquals(size, plan.targetSize)
                    assertEquals(orientation, plan.orientation)
                    // 사각형 안 원본 픽셀은 놓은 자리에서 방향을 되돌린 자리다
                    val inside = orientation.inverse.mapPixel(1, 0, turned.width, turned.height)
                    if (rect.width > 1 || rect.height > 1) {
                        assertEquals(PixelPoint(left + 1, top), plan.mapPixel(inside.x + rect.left, inside.y + rect.top))
                    }
                    val sources = canvasTiles(size) + (TileKey(-2, 0) to PixelSize(256, 20))
                    assertMapsEverySourcePixelOnce(plan, sources, plan.targets(sources))
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
        val targets = plan.targets(canvasTiles(image))
        var count = 0
        for (tile in targets) {
            for (part in tile.parts) {
                for (y in part.targetRect.top until part.targetRect.bottom) {
                    for (x in part.targetRect.left until part.targetRect.right) {
                        val local = part.sourcePixel(x, y, plan.orientation)
                        val source = tileSquare(part.source).let { PixelPoint(it.left + local.x, it.top + local.y) }
                        assertEquals(PixelPoint(tile.key.column * TILE_SIZE + x - 350, tile.key.row * TILE_SIZE + y - 200), source)
                        count++
                    }
                }
            }
        }
        assertEquals(300 * 200, count)
        // 캔버스 안 자리 타일은 캔버스 안쪽 크기다
        val grid = TileGrid(canvas.width, canvas.height)
        for (tile in targets) assertEquals(grid.bounds(tile.key).width, tile.width)
    }

    @Test
    fun place_rejectsAnEmptyRect_butTakesOneOutsideTheCanvas() {
        val source = PixelSize(100, 50)
        assertThrows(IllegalArgumentException::class.java) {
            TransformPlan.place(source, PixelRect(10, 10, 10, 20), Orientation.IDENTITY, source, 0, 0)
        }
        // 레이어 내용이 캔버스 밖까지 있으면 내용 사각형도 캔버스 밖이다
        TransformPlan.place(source, PixelRect(90, 40, 101, 50), Orientation.ROTATE_CLOCKWISE, source, 0, 0)
    }

    @Test
    fun crop_rejectsEmptyOrOutsideRect() {
        val source = PixelSize(100, 50)

        assertThrows(IllegalArgumentException::class.java) { TransformPlan.crop(source, PixelRect(10, 10, 10, 20)) }
        assertThrows(IllegalArgumentException::class.java) { TransformPlan.crop(source, PixelRect(-1, 0, 10, 10)) }
        assertThrows(IllegalArgumentException::class.java) { TransformPlan.crop(source, PixelRect(90, 40, 101, 50)) }
    }

    /**
     * 캔버스 원본을 계획대로 옮겼을 때 대상 픽셀마다 오는 원본 픽셀이 [expectedSource]와 같고, 대상 캔버스 픽셀이 한 번씩만 덮이는지 확인한다.
     * 돌리기·자르기라 대상 타일은 대상 캔버스의 자리 그대로(캔버스 안쪽 크기)여야 한다.
     */
    private fun assertMovesEveryPixel(plan: TransformPlan, expectedSource: (x: Int, y: Int) -> PixelPoint) {
        val target = plan.targetSize
        val targetGrid = TileGrid(target.width, target.height)
        val targets = canvasTargets(plan)
        assertEquals(targetGrid.keys(), targets.map { it.key })
        val covered = IntArray(target.width * target.height)
        for (tile in targets) {
            val targetBounds = targetGrid.bounds(tile.key)
            assertEquals(targetBounds.width, tile.width)
            assertEquals(targetBounds.height, tile.height)
            for (part in tile.parts) {
                val sourceSquare = tileSquare(part.source)
                assertTrue("$part", part.targetRect in PixelRect(0, 0, tile.width, tile.height))
                for (y in part.targetRect.top until part.targetRect.bottom) {
                    for (x in part.targetRect.left until part.targetRect.right) {
                        val local = part.sourcePixel(x, y, plan.orientation)
                        val source = PixelPoint(sourceSquare.left + local.x, sourceSquare.top + local.y)
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

    /**
     * [targets]가 원본 타일([sources]: 자리 → 담은 크기)의 픽셀을 모두(자르기면 남길 사각형 안만) 꼭 한 번씩 [TransformPlan.mapPixel]의 자리로
     * 옮기는지 본다. 조각은 원본 타일이 담은 크기·대상 타일이 담을 크기 안에 있고, 대상 캔버스 안 자리는 캔버스 안쪽보다 작지 않아야 한다.
     */
    private fun assertMapsEverySourcePixelOnce(plan: TransformPlan, sources: Map<TileKey, PixelSize>, targets: List<TargetTile>) {
        val stored = sources.map { (key, size) -> tileSquare(key).let { PixelRect(it.left, it.top, it.left + size.width, it.top + size.height) } }
        val extent = stored.reduce(PixelRect::union)
        val seen = IntArray(extent.width * extent.height)
        val targetGrid = TileGrid(plan.targetSize.width, plan.targetSize.height)
        val landed = HashSet<Long>()
        for (tile in targets) {
            targetGrid.canvasRect(tile.key)?.let { inside ->
                assertTrue("${tile.key}", tile.width >= inside.width && tile.height >= inside.height)
            }
            for (part in tile.parts) {
                val size = sources.getValue(part.source)
                assertTrue("$part", part.sourceRect in PixelRect(0, 0, size.width, size.height))
                assertTrue("$part in ${tile.width}x${tile.height}", part.targetRect in PixelRect(0, 0, tile.width, tile.height))
                val square = tileSquare(part.source)
                for (y in part.targetRect.top until part.targetRect.bottom) {
                    for (x in part.targetRect.left until part.targetRect.right) {
                        val local = part.sourcePixel(x, y, plan.orientation)
                        val sourceX = square.left + local.x
                        val sourceY = square.top + local.y
                        val targetX = tile.key.column * TILE_SIZE + x
                        val targetY = tile.key.row * TILE_SIZE + y
                        if (plan.mapPixel(sourceX, sourceY) != PixelPoint(targetX, targetY)) {
                            fail("${plan.orientation}: ($sourceX, $sourceY) landed on ($targetX, $targetY), not ${plan.mapPixel(sourceX, sourceY)}")
                        }
                        if (!landed.add(targetX.toLong() shl 32 or (targetY.toLong() and 0xFFFFFFFFL))) fail("($targetX, $targetY) covered twice")
                        seen[(sourceY - extent.top) * extent.width + sourceX - extent.left]++
                    }
                }
            }
        }
        for (rect in stored) {
            val kept = if (plan.discardsOutside) rect.intersect(plan.crop) else rect
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    val expected = if (kept != null && x in kept.left until kept.right && y in kept.top until kept.bottom) 1 else 0
                    val count = seen[(y - extent.top) * extent.width + x - extent.left]
                    if (count != expected) fail("${plan.orientation}: source ($x, $y) moved $count times")
                }
            }
        }
    }
}
