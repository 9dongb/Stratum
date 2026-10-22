package com.kiln.stratum.engine.liquify

import com.kiln.stratum.engine.raster.PixelSize
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.sqrt

/**
 * 손으로 민 변위 격자([LiquifyMesh])를 바이트로 쓰고 읽는다(프로젝트 폴더의 liquify 파일, 결정 기록 '얼굴 인식 보정' 3단계). 순수 계산이다.
 *
 * 형식(압축 전): 표지(LQMS), 판, 문서 가로·세로, 칸, 움직인 꼭짓점을 모두 담는 범위(왼쪽·위 꼭짓점 번호, 가로·세로 꼭짓점 수),
 * 그 범위의 dx·dy(float, 한 줄씩 위에서 아래로, 꼭짓점마다 dx·dy). 범위 밖 꼭짓점은 0이다. 값은 그대로 적어(손실 없음)
 * 다시 읽은 격자로 다시 그리면 같은 픽셀이 나온다. 전체를 Deflate로 줄인다(움직이지 않은 꼭짓점의 0이 많다).
 */
object LiquifyMeshCodec {

    /** 'LQMS' */
    private const val MAGIC = 0x4C514D53

    /** 이 앱이 쓰는 판이자 읽을 수 있는 가장 높은 판 */
    private const val VERSION = 1

    /** 읽을 때 받아들이는 가장 많은 꼭짓점 수. 망가진 파일이 큰 메모리를 잡지 않게 한다(앱의 격자는 약 40만 개). */
    private const val MAX_READ_VERTICES = LiquifyMesh.MAX_VERTICES * 4

    /** [mesh]를 [output]에 쓴다. [output]은 닫지 않는다(압축은 끝까지 마친다). */
    fun write(mesh: LiquifyMesh, output: OutputStream) {
        val columns = mesh.columns
        val rows = mesh.rows
        // 움직인(0이 아닌) 꼭짓점을 모두 담는 범위
        var left = columns
        var top = rows
        var right = -1
        var bottom = -1
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (mesh.vertexX(column, row) != 0f || mesh.vertexY(column, row) != 0f) {
                    if (column < left) left = column
                    if (column > right) right = column
                    if (row < top) top = row
                    if (row > bottom) bottom = row
                }
            }
        }
        val width = if (right < 0) 0 else right - left + 1
        val height = if (bottom < 0) 0 else bottom - top + 1
        if (width == 0) {
            left = 0
            top = 0
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            val compressed = DeflaterOutputStream(NonClosing(output), deflater, BUFFER)
            val out = DataOutputStream(compressed)
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(mesh.size.width)
            out.writeInt(mesh.size.height)
            out.writeInt(mesh.cellSize)
            out.writeInt(left)
            out.writeInt(top)
            out.writeInt(width)
            out.writeInt(height)
            // 한 줄씩 모아 쓴다(꼭짓점마다 쓰는 것보다 훨씬 빠르다)
            val line = ByteBuffer.allocate(width * 2 * Float.SIZE_BYTES)
            for (row in top until top + height) {
                line.clear()
                for (column in left until left + width) {
                    line.putFloat(mesh.vertexX(column, row))
                    line.putFloat(mesh.vertexY(column, row))
                }
                out.write(line.array(), 0, line.position())
            }
            out.flush()
            compressed.finish()
        } finally {
            deflater.end()
        }
    }

    /**
     * [input]에서 [size] 문서의 격자를 읽는다([input]은 닫지 않는다). 표지·판이 맞지 않거나, 문서 크기가 다르거나, 망가졌으면(숫자가 아닌 값 포함) IOException이다.
     * 꼭짓점 하나의 변위는 [LiquifyMesh.MAX_DISPLACEMENT]를 넘지 않게 묶는다.
     */
    fun read(input: InputStream, size: PixelSize): LiquifyMesh {
        val inflater = Inflater()
        try {
            val data = DataInputStream(InflaterInputStream(input, inflater, BUFFER))
            if (data.readInt() != MAGIC) throw IOException("Not a liquify mesh")
            val version = data.readInt()
            if (version !in 1..VERSION) throw IOException("Unsupported liquify mesh version $version")
            val width = data.readInt()
            val height = data.readInt()
            if (width != size.width || height != size.height) throw IOException("Mesh is for ${width}x$height, not ${size.width}x${size.height}")
            val cellSize = data.readInt()
            if (cellSize <= 0) throw IOException("Broken cell size $cellSize")
            val columns = size.width / cellSize + 2
            val rows = size.height / cellSize + 2
            if (columns.toLong() * rows > MAX_READ_VERTICES) throw IOException("Too many vertices: ${columns}x$rows")
            val left = data.readInt()
            val top = data.readInt()
            val windowWidth = data.readInt()
            val windowHeight = data.readInt()
            if (left < 0 || top < 0 || windowWidth < 0 || windowHeight < 0 || left + windowWidth > columns || top + windowHeight > rows) {
                throw IOException("Broken window ($left, $top) ${windowWidth}x$windowHeight in ${columns}x$rows")
            }
            val dx = FloatArray(columns * rows)
            val dy = FloatArray(columns * rows)
            val line = ByteArray(windowWidth * 2 * Float.SIZE_BYTES)
            val buffer = ByteBuffer.wrap(line)
            for (row in top until top + windowHeight) {
                data.readFully(line)
                buffer.clear()
                for (column in left until left + windowWidth) {
                    var x = buffer.getFloat()
                    var y = buffer.getFloat()
                    if (!x.isFinite() || !y.isFinite()) throw IOException("Broken displacement at ($column, $row)")
                    val length = sqrt(x * x + y * y)
                    if (length > LiquifyMesh.MAX_DISPLACEMENT) {
                        x *= LiquifyMesh.MAX_DISPLACEMENT / length
                        y *= LiquifyMesh.MAX_DISPLACEMENT / length
                    }
                    dx[row * columns + column] = x
                    dy[row * columns + column] = y
                }
            }
            return LiquifyMesh.of(size, cellSize, dx, dy)
        } finally {
            // [input]은 부르는 쪽이 닫는다
            inflater.end()
        }
    }

    /** 압축 묶음 크기(바이트) */
    private const val BUFFER = 64 * 1024

    /** 닫아도 아래 흐름은 닫지 않는 감싸개(압축 흐름을 끝내도 부르는 쪽의 [OutputStream]은 열어 둔다) */
    private class NonClosing(private val target: OutputStream) : OutputStream() {
        override fun write(b: Int) = target.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = target.write(b, off, len)

        override fun flush() = target.flush()

        override fun close() = target.flush()
    }
}
