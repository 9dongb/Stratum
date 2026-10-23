package com.kiln.stratum.engine.inpaint

import com.kiln.stratum.engine.raster.PixelRect
import java.util.Random
import kotlin.math.floor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintTextureTest {

    private val size = 64
    private val grid = InpaintTexture.gridSize(size)

    /** 가운데 ([cx], [cy]) 반지름 [radius]인 원 구멍 */
    private fun disc(cx: Float = 32f, cy: Float = 32f, radius: Float = 8f): BooleanArray =
        BooleanArray(size * size) {
            val dx = it % size + 0.5f - cx
            val dy = it / size + 0.5f - cy
            dx * dx + dy * dy <= radius * radius
        }

    private fun opaque(): FloatArray = FloatArray(size * size) { 255f }

    /** 그물 칸 [cell]의 조각(과 둘레 1칸)이 모두 [ok]인지 */
    private fun wholeCell(cell: Int, ok: (Int, Int) -> Boolean): Boolean {
        val x0 = InpaintTexture.center(cell % grid) - InpaintTexture.CELL / 2 - 1
        val y0 = InpaintTexture.center(cell / grid) - InpaintTexture.CELL / 2 - 1
        for (y in y0 until y0 + InpaintTexture.CELL + 2) {
            for (x in x0 until x0 + InpaintTexture.CELL + 2) if (x !in 0 until size || y !in 0 until size || !ok(x, y)) return false
        }
        return true
    }

    @Test
    fun candidates_areWholeKnownOpaqueCells_insideTheDocument_andNearTheHole() {
        val hole = disc()
        val distance = InpaintMask.distanceToHole(hole, size, size)
        // 왼쪽 위 구석은 투명하고, 문서는 오른쪽 48칸까지다
        val alpha = FloatArray(size * size) { if (it % size < 16 && it / size < 16) 0f else 255f }
        val inside = PixelRect(0, 0, 48, size)

        val found = InpaintTexture.candidates(distance, alpha, size, inside, max = 1000)

        assertTrue(found.isNotEmpty())
        val expected = (0 until grid * grid).filter { cell ->
            val cx = InpaintTexture.center(cell % grid)
            val cy = InpaintTexture.center(cell / grid)
            cx + InpaintTexture.CELL / 2 <= 48 && cy - InpaintTexture.CELL / 2 >= 0 && cy + InpaintTexture.CELL / 2 <= size &&
                cx - InpaintTexture.CELL / 2 >= 0 && distance[cy * size + cx] <= InpaintTexture.NEAR &&
                wholeCell(cell) { x, y -> distance[y * size + x] > InpaintTexture.KNOWN_GAP && alpha[y * size + x] >= 255f }
        }
        assertArrayEquals(expected.toIntArray(), found)
        for (cell in found) {
            assertTrue("inside the document", InpaintTexture.center(cell % grid) + InpaintTexture.CELL / 2 <= 48)
            assertFalse("not the transparent corner", InpaintTexture.center(cell % grid) < 20 && InpaintTexture.center(cell / grid) < 20)
        }
    }

    @Test
    fun candidates_farFromTheHole_areLeftOut() {
        // 아주 작은 구멍: 96칸보다 먼 칸은 없지만, 거리를 줄여 보면 가까운 칸만 남는다
        val hole = disc(radius = 2f)
        val distance = InpaintMask.distanceToHole(hole, size, size)
        val far = FloatArray(size * size) { distance[it] * 10f }

        val found = InpaintTexture.candidates(far, opaque(), size, PixelRect(0, 0, size, size), max = 1000)

        assertTrue(found.isNotEmpty())
        for (cell in found) {
            val cx = InpaintTexture.center(cell % grid)
            val cy = InpaintTexture.center(cell / grid)
            assertTrue(far[cy * size + cx] <= InpaintTexture.NEAR)
        }
    }

    @Test
    fun candidates_beyondTheLimit_areSpreadEvenly() {
        val distance = InpaintMask.distanceToHole(disc(radius = 4f), size, size)
        val all = InpaintTexture.candidates(distance, opaque(), size, PixelRect(0, 0, size, size), max = 1000)

        val five = InpaintTexture.candidates(distance, opaque(), size, PixelRect(0, 0, size, size), max = 5)

        assertEquals(5, five.size)
        assertTrue(all.size > 5)
        assertArrayEquals(IntArray(5) { all[((it + 0.5) * all.size / 5).toInt()] }, five)
    }

    @Test
    fun targets_coverEveryHolePixel_withAllFourOverlappingCells() {
        val hole = disc(cx = 30f, cy = 37f, radius = 9f)

        val targets = InpaintTexture.targets(hole, size).toSet()

        for (p in hole.indices) {
            if (!hole[p]) continue
            val x = p % size
            val y = p / size
            val firstX = floor(x.toFloat() / InpaintTexture.STRIDE).toInt() * InpaintTexture.STRIDE
            val firstY = floor(y.toFloat() / InpaintTexture.STRIDE).toInt() * InpaintTexture.STRIDE
            for (cy in listOf(firstY, firstY + InpaintTexture.STRIDE)) {
                for (cx in listOf(firstX, firstX + InpaintTexture.STRIDE)) {
                    val gx = cx / InpaintTexture.STRIDE - 1
                    val gy = cy / InpaintTexture.STRIDE - 1
                    if (gx !in 0 until grid || gy !in 0 until grid) continue
                    assertTrue("cell ($cx, $cy) covers ($x, $y)", gy * grid + gx in targets)
                }
            }
        }
        // 구멍에 닿지 않는 칸은 없다
        for (cell in targets) assertTrue(touches(hole, cell))
    }

    private fun touches(hole: BooleanArray, cell: Int): Boolean {
        val x0 = InpaintTexture.center(cell % grid) - InpaintTexture.CELL / 2
        val y0 = InpaintTexture.center(cell / grid) - InpaintTexture.CELL / 2
        for (y in y0 until y0 + InpaintTexture.CELL) for (x in x0 until x0 + InpaintTexture.CELL) if (hole[y * size + x]) return true
        return false
    }

    /** 그물 칸 [cell]의 조각에 [texture](8×8×3)를 써 넣는다 */
    private fun put(picture: FloatArray, cell: Int, texture: FloatArray) {
        val x0 = InpaintTexture.center(cell % grid) - InpaintTexture.CELL / 2
        val y0 = InpaintTexture.center(cell / grid) - InpaintTexture.CELL / 2
        var i = 0
        for (y in y0 until y0 + InpaintTexture.CELL) {
            for (x in x0 until x0 + InpaintTexture.CELL) {
                for (c in 0..2) picture[(y * size + x) * 3 + c] = texture[i++]
            }
        }
    }

    @Test
    fun match_findsTheCandidateWithTheSameTexture() {
        val random = Random(4)
        val known = FloatArray(size * size * 3)
        val filled = FloatArray(size * size * 3)
        val candidates = intArrayOf(0, 2, 4, 6, 30, 32, 34, 36)
        val textures = candidates.map { FloatArray(192) { 128f + random.nextGaussian().toFloat() * 40f } }
        for (i in candidates.indices) put(known, candidates[i], textures[i])
        // 대상마다 후보 하나의 결에 작은 잡음을 얹는다
        val targets = intArrayOf(100, 102, 104, 120)
        val wanted = intArrayOf(5, 0, 7, 3)
        for (t in targets.indices) put(filled, targets[t], FloatArray(192) { textures[wanted[t]][it] + random.nextGaussian().toFloat() * 2f })

        assertArrayEquals(wanted, InpaintTexture.match(known, filled, size, candidates, targets))
    }

    @Test
    fun match_shortlistsByTheCoarseLook_butPicksByTheFineOne() {
        val known = FloatArray(size * size * 3)
        val filled = FloatArray(size * size * 3)
        // 대상: 바탕 100에 1칸 체크 무늬(±30)
        val target = FloatArray(192) { i -> val p = i / 3; if ((p % 8 + p / 8) % 2 == 0) 130f else 70f }
        // 후보 0: 평평한 101(거친 값이 가장 가깝다), 후보 1: 같은 체크 무늬에 바탕 104(거친 값은 조금 멀고 고운 값은 가장 가깝다)
        val flat = FloatArray(192) { 101f }
        val checker = FloatArray(192) { target[it] + 4f }
        // 후보 2·3: 아주 다른 색(짧은 목록에는 들지만 고운 값이 멀다)
        val far1 = FloatArray(192) { 160f }
        val far2 = FloatArray(192) { 30f }
        val candidates = intArrayOf(0, 2, 4, 6)
        listOf(flat, checker, far1, far2).forEachIndexed { i, texture -> put(known, candidates[i], texture) }
        put(filled, 100, target)

        val picked = InpaintTexture.match(known, filled, size, candidates, intArrayOf(100))

        assertArrayEquals(intArrayOf(1), picked)
    }

    @Test
    fun plan_givesEveryTargetASlot_andListsEachSourceOnce() {
        val hole = disc(radius = 7f)
        val distance = InpaintMask.distanceToHole(hole, size, size)
        val random = Random(9)
        val known = FloatArray(size * size * 3) { 100f + random.nextFloat() * 50f }
        val filled = known.copyOf()

        val match = InpaintTexture.plan(known, filled, opaque(), distance, hole, size, PixelRect(0, 0, size, size), 512)!!

        val targets = InpaintTexture.targets(hole, size).toSet()
        assertEquals(grid, match.grid)
        for (cell in 0 until grid * grid) {
            if (cell in targets) assertTrue(match.slots[cell] in match.sources.indices) else assertEquals(-1, match.slots[cell])
        }
        assertEquals(match.sources.size, match.sources.toSet().size)
        // 쓴 조각은 모두 후보다
        val candidates = InpaintTexture.candidates(distance, opaque(), size, PixelRect(0, 0, size, size), 512).toSet()
        assertTrue(match.sources.all { it in candidates })
    }

    @Test
    fun plan_withoutCandidates_isNull() {
        val hole = disc(radius = 7f)
        val distance = InpaintMask.distanceToHole(hole, size, size)
        val picture = FloatArray(size * size * 3) { 120f }

        // 모두 투명하면 가져올 조각이 없다
        assertNull(InpaintTexture.plan(picture, picture, FloatArray(size * size), distance, hole, size, PixelRect(0, 0, size, size), 512))
        // 구멍이 없으면 채울 조각이 없다
        assertNull(InpaintTexture.plan(picture, picture, opaque(), distance, BooleanArray(size * size), size, PixelRect(0, 0, size, size), 512))
    }

    @Test
    fun maxCandidates_keepsTheAtlasWithinItsBudget() {
        assertEquals(InpaintTexture.MAX_CANDIDATES, InpaintTexture.maxCandidates(1.5))
        assertEquals(InpaintTexture.MAX_CANDIDATES, InpaintTexture.maxCandidates(8.0))
        for (scale in listOf(1.5, 4.0, 8.0, 16.0, 26.0, 40.0)) {
            val block = InpaintTexture.blockSize(scale).toLong()
            assertTrue("scale $scale", InpaintTexture.maxCandidates(scale) * block * block * 4 <= InpaintTexture.ATLAS_BYTES)
        }
        assertTrue(InpaintTexture.maxCandidates(26.0) < InpaintTexture.MAX_CANDIDATES)
    }

    @Test
    fun applies_fromOneAndAHalfTimes() {
        assertFalse(InpaintTexture.applies(1.0))
        assertFalse(InpaintTexture.applies(1.49))
        assertTrue(InpaintTexture.applies(1.5))
        assertTrue(InpaintTexture.applies(9.4))
    }

    @Test
    fun pack_roundTripsWhatTheShaderReads() {
        for ((x, y, slot) in listOf(Triple(0, 0, 0), Triple(126, 126, 511), Triple(5, 90, 256), Triple(127, 3, 255), Triple(64, 64, 300))) {
            val color = InpaintTexture.pack(x, y, slot)
            assertEquals(0xFF, color ushr 24)
            assertEquals(Triple(x, y, slot), InpaintTexture.unpack(color))
        }
    }

    @Test
    fun blockStart_coversEveryPixelTheShaderReads() {
        val random = Random(2)
        for (scale in listOf(1.5, 1.953125, 7.32421875, 15.9375)) {
            val block = InpaintTexture.blockSize(scale)
            val cell = InpaintTexture.CELL * scale
            repeat(200) {
                val windowLeft = random.nextInt(4000)
                val source = random.nextInt(InpaintTexture.gridSize(512))
                val target = random.nextInt(InpaintTexture.gridSize(512))
                val sourceCenter = InpaintTexture.center(source)
                val targetCenter = InpaintTexture.center(target)
                val start = InpaintTexture.blockStart(windowLeft, source, scale)
                // 대상 조각이 덮는 문서 픽셀 가운데마다, 셰이더처럼 가져올 픽셀을 구한다
                val from = floor(windowLeft + targetCenter * scale - cell / 2).toInt()
                val to = floor(windowLeft + targetCenter * scale + cell / 2).toInt()
                for (x in from..to) {
                    val q = floor(x + 0.5 + (sourceCenter - targetCenter) * scale).toInt()
                    assertTrue("scale $scale: ${q - start} in 0 until $block", q - start in 0 until block)
                }
            }
        }
    }
}
