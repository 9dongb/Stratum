package com.kiln.stratum.engine.liquify

import com.kiln.stratum.engine.raster.PixelSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyMeshCodecTest {

    private val size = PixelSize(1000, 700)

    private fun bytesOf(mesh: LiquifyMesh): ByteArray = ByteArrayOutputStream().also { LiquifyMeshCodec.write(mesh, it) }.toByteArray()

    private fun read(bytes: ByteArray, size: PixelSize = this.size): LiquifyMesh = LiquifyMeshCodec.read(ByteArrayInputStream(bytes), size)

    @Test
    fun writeThenRead_keepsEveryVertexExactly() {
        val mesh = LiquifyMesh.identity(size)
        mesh.brush(LiquifyTool.PUSH, 300f, 200f, radius = 120f, strength = 0.7f, moveX = 40f, moveY = -15f)
        mesh.brush(LiquifyTool.BLOAT, 620f, 480f, radius = 90f, strength = 1f)
        mesh.brush(LiquifyTool.RECONSTRUCT, 300f, 200f, radius = 60f, strength = 0.3f)

        val read = read(bytesOf(mesh))

        assertEquals(mesh.size, read.size)
        assertEquals(mesh.cellSize, read.cellSize)
        for (row in 0 until mesh.rows) {
            for (column in 0 until mesh.columns) {
                // 값을 그대로 적어 다시 그리면 같은 픽셀이 나온다
                assertEquals(mesh.vertexX(column, row).toRawBits(), read.vertexX(column, row).toRawBits())
                assertEquals(mesh.vertexY(column, row).toRawBits(), read.vertexY(column, row).toRawBits())
            }
        }
        assertEquals(mesh.changedBounds(), read.changedBounds())
    }

    @Test
    fun emptyMesh_ofABigPhoto_isTiny() {
        // 50MP 문서도 움직인 곳이 없으면 범위가 비어 머리말만 남는다
        val empty = LiquifyMesh.identity(PixelSize(8160, 6120))

        val bytes = bytesOf(empty)

        assertTrue("${bytes.size} bytes", bytes.size < 200)
        assertTrue(read(bytes, PixelSize(8160, 6120)).isIdentity())
    }

    @Test
    fun smallChange_ofABigPhoto_writesOnlyItsWindow() {
        val big = PixelSize(8160, 6120)
        val mesh = LiquifyMesh.identity(big)
        mesh.brush(LiquifyTool.PUSH, 4000f, 3000f, radius = 200f, strength = 1f, moveX = 30f, moveY = 0f)

        val bytes = bytesOf(mesh)

        // 격자 전체(약 3.2MB)가 아니라 움직인 범위만 적는다
        assertTrue("${bytes.size} bytes", bytes.size < 100_000)
        assertEquals(mesh.changedBounds(), read(bytes, big).changedBounds())
    }

    @Test
    fun read_rejectsAnotherDocumentSize() {
        val bytes = bytesOf(LiquifyMesh.identity(size))

        assertThrows(IOException::class.java) { read(bytes, PixelSize(1000, 701)) }
    }

    @Test
    fun read_rejectsBrokenBytes() {
        val mesh = LiquifyMesh.identity(size)
        mesh.brush(LiquifyTool.BLOAT, 500f, 350f, radius = 100f, strength = 1f)
        val bytes = bytesOf(mesh)

        // 잘린 파일, 압축이 아닌 바이트
        assertThrows(IOException::class.java) { read(bytes.copyOf(bytes.size / 2)) }
        assertThrows(IOException::class.java) { read(ByteArray(64) { it.toByte() }) }
    }

    /** 손으로 만든 격자 파일: [cellSize] 칸, ([left], [top])에서 [width]×[height] 범위의 값들([values]: dx·dy 차례) */
    private fun craft(cellSize: Int, left: Int, top: Int, width: Int, height: Int, values: FloatArray, version: Int = 1): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(DeflaterOutputStream(buffer)).use { out ->
                out.writeInt(0x4C514D53)
                out.writeInt(version)
                out.writeInt(size.width)
                out.writeInt(size.height)
                out.writeInt(cellSize)
                out.writeInt(left)
                out.writeInt(top)
                out.writeInt(width)
                out.writeInt(height)
                for (value in values) out.writeFloat(value)
            }
        }.toByteArray()

    @Test
    fun read_rejectsNonFiniteValues_windowsOutside_andNewerVersions() {
        val cell = LiquifyMesh.identity(size).cellSize
        val columns = size.width / cell + 2

        assertThrows(IOException::class.java) { read(craft(cell, 3, 4, 1, 1, floatArrayOf(Float.NaN, 0f))) }
        assertThrows(IOException::class.java) { read(craft(cell, 3, 4, 1, 1, floatArrayOf(0f, Float.POSITIVE_INFINITY))) }
        assertThrows(IOException::class.java) { read(craft(cell, columns - 1, 0, 2, 1, FloatArray(4))) }
        assertThrows(IOException::class.java) { read(craft(cell, 0, 0, 0, 0, FloatArray(0), version = 2)) }
        assertThrows(IOException::class.java) { read(craft(0, 0, 0, 0, 0, FloatArray(0))) }
        // 더하면 Int를 넘치는 범위도 망가진 파일이다(넘쳐 음수가 되어 검사를 지나지 않게)
        assertThrows(IOException::class.java) { read(craft(cell, 1, 0, Int.MAX_VALUE, 1, FloatArray(0))) }
        assertThrows(IOException::class.java) { read(craft(cell, 0, 1, 1, Int.MAX_VALUE, FloatArray(0))) }
    }

    @Test
    fun read_keepsDisplacementsWithinTheLimit() {
        val cell = LiquifyMesh.identity(size).cellSize

        val read = read(craft(cell, 3, 4, 1, 1, floatArrayOf(3000f, -4000f)))

        val length = hypot(read.vertexX(3, 4), read.vertexY(3, 4))
        assertEquals(LiquifyMesh.MAX_DISPLACEMENT, length, 0.01f)
        // 방향은 그대로다
        assertEquals(0.6f, read.vertexX(3, 4) / length, 1e-4f)
    }

    @Test
    fun byteCount_matchesTheFormulaWithoutMakingAMesh() {
        for (size in listOf(PixelSize(1000, 700), PixelSize(8160, 6120), PixelSize(1, 1))) {
            assertEquals(LiquifyMesh.identity(size).byteCount, LiquifyMesh.byteCountFor(size))
        }
    }
}
