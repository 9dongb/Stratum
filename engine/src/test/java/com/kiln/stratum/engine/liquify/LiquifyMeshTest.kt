package com.kiln.stratum.engine.liquify

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyMeshTest {

    private val size = PixelSize(400, 300)

    private fun at(mesh: LiquifyMesh, x: Float, y: Float): Pair<Float, Float> {
        val out = FloatArray(2)
        mesh.displacementAt(x, y, out)
        return out[0] to out[1]
    }

    @Test
    fun identity_hasNoDisplacement_andSmallCellsForSmallDocuments() {
        val mesh = LiquifyMesh.identity(size)

        assertTrue(mesh.isIdentity())
        assertNull(mesh.changedBounds())
        assertEquals(LiquifyMesh.MIN_CELL, mesh.cellSize)
        assertEquals(0f to 0f, at(mesh, 123f, 45f))
    }

    @Test
    fun forward_findsWhereAnOriginalPointIsShown_afterPushing() {
        val mesh = LiquifyMesh.identity(size)
        // 오른쪽으로 크게 민다: 결과의 (200, 150) 둘레는 왼쪽에서 가져온다
        mesh.brush(LiquifyTool.PUSH, 200f, 150f, radius = 80f, strength = 1f, moveX = 30f, moveY = 10f)
        val shown = FloatArray(2)
        for ((x, y) in listOf(200f to 150f, 230f to 140f, 170f to 170f, 10f to 10f)) {
            // 결과 자리 (x, y)가 보여 주는 원래 점
            val (dx, dy) = at(mesh, x, y)

            mesh.forward(x + dx, y + dy, shown)

            assertEquals("x for ($x, $y)", x, shown[0], 0.05f)
            assertEquals("y for ($x, $y)", y, shown[1], 0.05f)
        }
    }

    @Test
    fun bigDocuments_useCoarserCells() {
        val mesh = LiquifyMesh.identity(PixelSize(8160, 6120))

        assertTrue(mesh.columns * mesh.rows <= LiquifyMesh.MAX_VERTICES + mesh.columns + mesh.rows + 2)
        assertTrue(mesh.cellSize in 11..13)
    }

    @Test
    fun push_readsFromBehindTheFinger_strongestInTheMiddle() {
        val mesh = LiquifyMesh.identity(size)

        // 오른쪽으로 10픽셀 민다: 결과 자리는 왼쪽(-10) 픽셀을 보인다
        mesh.brush(LiquifyTool.PUSH, 200f, 150f, radius = 40f, strength = 1f, moveX = 10f, moveY = 0f)

        val (centerX, centerY) = at(mesh, 200f, 150f)
        assertEquals(-10f, centerX, 0.5f)
        assertEquals(0f, centerY, 0.01f)
        val (sideX, _) = at(mesh, 228f, 150f)
        assertTrue("side $sideX", sideX > -10f && sideX < 0f)
        // 브러시 밖은 그대로다
        assertEquals(0f to 0f, at(mesh, 300f, 150f))
        val changed = checkNotNull(mesh.changedBounds())
        assertTrue(changed.left >= 150 && changed.right <= 250)
    }

    @Test
    fun pushTwice_accumulates() {
        val mesh = LiquifyMesh.identity(size)

        mesh.brush(LiquifyTool.PUSH, 200f, 150f, 60f, 1f, moveX = 5f)
        mesh.brush(LiquifyTool.PUSH, 205f, 150f, 60f, 1f, moveX = 5f)

        assertTrue(at(mesh, 205f, 150f).first < -8f)
    }

    @Test
    fun pucker_readsFromOutside_bloatFromInside() {
        val pucker = LiquifyMesh.identity(size)
        pucker.brush(LiquifyTool.PUCKER, 200f, 150f, 50f, 1f)
        val bloat = LiquifyMesh.identity(size)
        bloat.brush(LiquifyTool.BLOAT, 200f, 150f, 50f, 1f)

        // 가운데 오른쪽 점: 오목은 더 오른쪽(바깥)을, 볼록은 가운데 쪽을 읽는다
        assertTrue(at(pucker, 220f, 150f).first > 0f)
        assertTrue(at(bloat, 220f, 150f).first < 0f)
        // 가운데는 움직이지 않는다
        assertEquals(0f, at(pucker, 200f, 150f).first, 0.01f)
    }

    @Test
    fun reconstruct_goesBackTowardTheOriginal_andResetClears() {
        val mesh = LiquifyMesh.identity(size)
        mesh.brush(LiquifyTool.PUSH, 200f, 150f, 40f, 1f, moveX = 10f)
        val pushed = at(mesh, 200f, 150f).first

        mesh.brush(LiquifyTool.RECONSTRUCT, 200f, 150f, 40f, 1f)

        val restored = at(mesh, 200f, 150f).first
        assertTrue("$pushed → $restored", restored > pushed && restored < 0f)
        mesh.reset()
        assertTrue(mesh.isIdentity())
    }

    @Test
    fun copy_isIndependent() {
        val mesh = LiquifyMesh.identity(size)
        val copy = mesh.copy()

        mesh.brush(LiquifyTool.PUSH, 200f, 150f, 40f, 1f, moveX = 10f)

        assertTrue(copy.isIdentity())
        assertFalse(mesh.isIdentity())
    }

    @Test
    fun maxDisplacement_coversTheTouchedArea() {
        val mesh = LiquifyMesh.identity(size)
        mesh.brush(LiquifyTool.PUSH, 200f, 150f, 40f, 1f, moveX = 0f, moveY = 12f)

        assertEquals(12f, mesh.maxDisplacementIn(PixelRect(180, 130, 220, 170)), 0.6f)
        assertEquals(0f, mesh.maxDisplacementIn(PixelRect(0, 0, 50, 50)), 0f)
    }

    @Test
    fun zeroStrengthOrRadius_changesNothing() {
        val mesh = LiquifyMesh.identity(size)

        mesh.brush(LiquifyTool.PUSH, 200f, 150f, 40f, 0f, moveX = 10f)
        mesh.brush(LiquifyTool.BLOAT, 200f, 150f, 0f, 1f)

        assertTrue(mesh.isIdentity())
    }
}
