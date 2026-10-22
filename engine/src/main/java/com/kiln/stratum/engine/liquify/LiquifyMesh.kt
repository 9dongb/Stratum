package com.kiln.stratum.engine.liquify

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 픽셀 유동화 도구 */
enum class LiquifyTool {
    /** 뒤틀기: 손가락이 움직인 쪽으로 픽셀을 민다 */
    PUSH,

    /** 오목: 브러시 가운데로 픽셀을 모은다(작아 보임) */
    PUCKER,

    /** 볼록: 브러시 가운데에서 픽셀을 밀어낸다(커 보임) */
    BLOAT,

    /** 재구성: 문지른 곳을 원래대로 조금씩 되돌린다 */
    RECONSTRUCT
}

/**
 * 픽셀 유동화의 변위 격자. 꼭짓점마다 '이 자리의 결과 픽셀을 원래 그림의 어디에서 가져올지'를 문서 픽셀 차이(dx, dy)로 둔다.
 * 꼭짓점 사이는 이어서 본다(쌍선형). 꼭짓점 (i, j)는 문서 (i × [cellSize], j × [cellSize]) 자리다.
 *
 * 작업 모드 동안만 바꿔 쓰는 값이다(실행 취소 기록에는 적용할 때 만든 복사본[copy]이 명령에 담긴다).
 * 브러시 계산은 브러시가 닿는 꼭짓점만 돈다. 격자는 문서보다 훨씬 작다(꼭짓점 약 40만 개 이하).
 *
 * @property size 문서 크기
 * @property cellSize 꼭짓점 사이 거리(문서 픽셀)
 */
class LiquifyMesh private constructor(
    val size: PixelSize,
    val cellSize: Int,
    private val dx: FloatArray,
    private val dy: FloatArray
) {
    /** 가로 꼭짓점 수(문서 오른쪽 끝을 넘는 꼭짓점 하나 포함) */
    val columns: Int = size.width / cellSize + 2

    /** 세로 꼭짓점 수 */
    val rows: Int = size.height / cellSize + 2

    init {
        require(cellSize > 0) { "Cell size must be positive: $cellSize" }
        require(dx.size == columns * rows && dy.size == columns * rows) { "Mesh arrays do not match ${columns}x$rows" }
    }

    /** 꼭짓점 ([column], [row])의 변위 */
    fun vertexX(column: Int, row: Int): Float = dx[row * columns + column]

    fun vertexY(column: Int, row: Int): Float = dy[row * columns + column]

    /** 문서 좌표 ([x], [y])의 변위를 [out](0: dx, 1: dy)에 담는다. 격자 밖은 가장자리 꼭짓점을 쓴다. */
    fun displacementAt(x: Float, y: Float, out: FloatArray) {
        val gx = (x / cellSize).coerceIn(0f, (columns - 1).toFloat())
        val gy = (y / cellSize).coerceIn(0f, (rows - 1).toFloat())
        val c0 = min(floor(gx).toInt(), columns - 2)
        val r0 = min(floor(gy).toInt(), rows - 2)
        val fx = gx - c0
        val fy = gy - r0
        val i00 = r0 * columns + c0
        val i10 = i00 + 1
        val i01 = i00 + columns
        val i11 = i01 + 1
        out[0] = (dx[i00] * (1 - fx) + dx[i10] * fx) * (1 - fy) + (dx[i01] * (1 - fx) + dx[i11] * fx) * fy
        out[1] = (dy[i00] * (1 - fx) + dy[i10] * fx) * (1 - fy) + (dy[i01] * (1 - fx) + dy[i11] * fx) * fy
    }

    /**
     * 결과 자리 ([x], [y])가 읽는 원래 그림의 자리를 [out]에 담는다: 이 격자(손으로 민 것)를 따라간 뒤 [warps](얼굴 보정 등)를 차례로 따라간다.
     * [warps]는 원래 그림에 먼저 걸리는 변형이라 거꾸로 따라갈 때는 격자 다음이다([compose]와 같은 순서).
     */
    fun sourceOf(x: Float, y: Float, out: FloatArray, warps: List<MeshWarp> = emptyList()) {
        displacementAt(x, y, out)
        var sx = x + out[0]
        var sy = y + out[1]
        for (warp in warps) {
            if (!warp.covers(sx, sy)) continue
            warp.displacement(sx, sy, out)
            sx += out[0]
            sy += out[1]
        }
        out[0] = sx
        out[1] = sy
    }

    /**
     * 원래 그림의 점 ([x], [y])이 비튼 결과에서 보이는 자리를 [out]에 담는다. 격자는 결과 자리에서 원래 자리를 가리키므로(거꾸로 찾음),
     * 읽는 자리([sourceOf], [warps]까지 합침)가 (x, y)인 결과 자리 q를 고정점 반복으로 찾는다(q ← q + (x, y) − 읽는 자리(q)).
     * 유동화·얼굴 보정의 변위는 매끈해(접히지 않음) 몇 번 만에 1px 안으로 모인다. 얼굴 점처럼 몇백 개 점을 화면에 그릴 때 쓴다.
     * 한 번에 [FORWARD_TOLERANCE]보다 덜 옮기면 그만한다(움직이지 않은 곳은 한 번).
     */
    fun forward(x: Float, y: Float, out: FloatArray, warps: List<MeshWarp> = emptyList(), iterations: Int = FORWARD_ITERATIONS) {
        var qx = x
        var qy = y
        for (i in 0 until iterations) {
            sourceOf(qx, qy, out, warps)
            val stepX = x - out[0]
            val stepY = y - out[1]
            qx += stepX
            qy += stepY
            if (abs(stepX) < FORWARD_TOLERANCE && abs(stepY) < FORWARD_TOLERANCE) break
        }
        out[0] = qx
        out[1] = qy
    }

    /** 움직인 곳이 하나도 없는지 */
    fun isIdentity(): Boolean = dx.all { abs(it) < EPSILON } && dy.all { abs(it) < EPSILON }

    /**
     * 결과가 바뀌는 곳을 담는 문서 사각형(변위가 있는 꼭짓점과 그 이웃 칸). 바뀐 곳이 없으면 null.
     * 이 밖의 픽셀은 그대로라 다시 그리지 않는다.
     */
    fun changedBounds(): PixelRect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val i = row * columns + column
                if (abs(dx[i]) >= EPSILON || abs(dy[i]) >= EPSILON) {
                    left = min(left, column)
                    top = min(top, row)
                    right = max(right, column)
                    bottom = max(bottom, row)
                }
            }
        }
        if (right < left) return null
        // 꼭짓점 하나의 변위는 이웃 칸까지 이어서 퍼진다
        return PixelRect(
            ((left - 1) * cellSize).coerceAtLeast(0),
            ((top - 1) * cellSize).coerceAtLeast(0),
            ((right + 1) * cellSize).coerceAtMost(size.width),
            ((bottom + 1) * cellSize).coerceAtMost(size.height)
        ).takeIf { it.width > 0 && it.height > 0 }
    }

    /** [rect](문서 픽셀)에 닿는 꼭짓점들의 가장 큰 변위 길이(픽셀). 그 자리를 그리려면 원래 그림을 이만큼 더 넓게 읽어야 한다. */
    fun maxDisplacementIn(rect: PixelRect): Float {
        val c0 = (rect.left / cellSize).coerceIn(0, columns - 1)
        val r0 = (rect.top / cellSize).coerceIn(0, rows - 1)
        val c1 = ((rect.right + cellSize - 1) / cellSize).coerceIn(0, columns - 1)
        val r1 = ((rect.bottom + cellSize - 1) / cellSize).coerceIn(0, rows - 1)
        var most = 0f
        for (row in r0..r1) {
            for (column in c0..c1) {
                val i = row * columns + column
                most = max(most, sqrt(dx[i] * dx[i] + dy[i] * dy[i]))
            }
        }
        return most
    }

    /**
     * 브러시 한 번: 문서 좌표 ([x], [y]) 가운데, 반지름 [radius](픽셀)의 브러시로 [tool]을 [strength](0..1)만큼 건다.
     * 뒤틀기는 손가락이 움직인 거리([moveX], [moveY], 문서 픽셀)만큼 민다. 나머지는 한 번에 조금씩 걸린다(누르고 있으면 계속).
     * 가장자리로 갈수록 약해진다((1 - (r/R)²)²).
     */
    fun brush(tool: LiquifyTool, x: Float, y: Float, radius: Float, strength: Float, moveX: Float = 0f, moveY: Float = 0f) {
        if (radius <= 0f || strength <= 0f) return
        val c0 = floor((x - radius) / cellSize).toInt().coerceIn(0, columns - 1)
        val c1 = ceil((x + radius) / cellSize).toInt().coerceIn(0, columns - 1)
        val r0 = floor((y - radius) / cellSize).toInt().coerceIn(0, rows - 1)
        val r1 = ceil((y + radius) / cellSize).toInt().coerceIn(0, rows - 1)
        // 바꾸는 동안 앞 변위를 읽을 수 있게, 바꿀 꼭짓점들의 앞 값만 따로 둔다(격자 전체를 복사하지 않는다)
        val width = c1 - c0 + 1
        val oldX = FloatArray(width * (r1 - r0 + 1))
        val oldY = FloatArray(oldX.size)
        for (row in r0..r1) {
            System.arraycopy(dx, row * columns + c0, oldX, (row - r0) * width, width)
            System.arraycopy(dy, row * columns + c0, oldY, (row - r0) * width, width)
        }
        fun before(column: Int, row: Int, horizontal: Boolean): Float {
            val inside = column in c0..c1 && row in r0..r1
            return when {
                inside && horizontal -> oldX[(row - r0) * width + column - c0]
                inside -> oldY[(row - r0) * width + column - c0]
                horizontal -> dx[row * columns + column]
                else -> dy[row * columns + column]
            }
        }
        // 앞 변위를 문서 좌표 (px, py)에서 이어서 읽는다(displacementAt와 같은 계산)
        val sample = FloatArray(2)
        fun sampleBefore(px: Float, py: Float) {
            val gx = (px / cellSize).coerceIn(0f, (columns - 1).toFloat())
            val gy = (py / cellSize).coerceIn(0f, (rows - 1).toFloat())
            val cc = min(floor(gx).toInt(), columns - 2)
            val rr = min(floor(gy).toInt(), rows - 2)
            val fx = gx - cc
            val fy = gy - rr
            for (axis in 0..1) {
                val h = axis == 0
                sample[axis] = (before(cc, rr, h) * (1 - fx) + before(cc + 1, rr, h) * fx) * (1 - fy) +
                    (before(cc, rr + 1, h) * (1 - fx) + before(cc + 1, rr + 1, h) * fx) * fy
            }
        }
        val rate = strength.coerceIn(0f, 1f)
        for (row in r0..r1) {
            for (column in c0..c1) {
                val vx = (column * cellSize).toFloat()
                val vy = (row * cellSize).toFloat()
                val ox = vx - x
                val oy = vy - y
                val distance2 = ox * ox + oy * oy
                if (distance2 >= radius * radius) continue
                val t = 1f - distance2 / (radius * radius)
                val weight = t * t
                val i = row * columns + column
                when (tool) {
                    LiquifyTool.PUSH -> {
                        // 내용을 m만큼 밀면, 결과 자리 p는 앞 결과의 (p - m) 자리를 보인다
                        val mx = moveX * weight * rate
                        val my = moveY * weight * rate
                        sampleBefore(vx - mx, vy - my)
                        dx[i] = sample[0] - mx
                        dy[i] = sample[1] - my
                    }
                    LiquifyTool.PUCKER, LiquifyTool.BLOAT -> {
                        // 오목은 가운데로 모아(바깥을 읽고), 볼록은 밀어낸다(가운데 쪽을 읽는다)
                        val k = weight * rate * PINCH_STEP * (if (tool == LiquifyTool.PUCKER) 1f else -1f)
                        val sx = ox * k
                        val sy = oy * k
                        sampleBefore(vx + sx, vy + sy)
                        dx[i] = sample[0] + sx
                        dy[i] = sample[1] + sy
                    }
                    LiquifyTool.RECONSTRUCT -> {
                        val keep = 1f - weight * rate * RECONSTRUCT_STEP
                        dx[i] *= keep
                        dy[i] *= keep
                    }
                }
                // 너무 멀리 끌어오지 않게 한도로 자른다(적용할 때 원래 그림을 읽는 범위를 묶는다)
                val length = sqrt(dx[i] * dx[i] + dy[i] * dy[i])
                if (length > MAX_DISPLACEMENT) {
                    dx[i] *= MAX_DISPLACEMENT / length
                    dy[i] *= MAX_DISPLACEMENT / length
                }
            }
        }
    }

    /** 모두 되돌린다(변위 0) */
    fun reset() {
        dx.fill(0f)
        dy.fill(0f)
    }

    /** 같은 값의 새 격자 */
    fun copy(): LiquifyMesh = LiquifyMesh(size, cellSize, dx.copyOf(), dy.copyOf())

    /** [other]와 크기·칸·모든 꼭짓점 값이 같은지(다시 연 유동화에서 바꾼 것이 있는지 볼 때) */
    fun sameAs(other: LiquifyMesh): Boolean =
        other === this || (size == other.size && cellSize == other.cellSize && dx.contentEquals(other.dx) && dy.contentEquals(other.dy))

    /** 이 격자가 쓰는 메모리(바이트): 꼭짓점마다 dx·dy 두 float. 실행 취소 기록의 메모리 셈(유동화 기록의 격자)에 쓴다. */
    val byteCount: Long
        get() = columns.toLong() * rows * 2 * Float.SIZE_BYTES

    /** 꼭짓점 변위들(앱이 그대로 GPU에 올리는 데 쓴다). 한 줄씩 위에서 아래로, dx·dy 순서다. */
    fun copyInto(target: FloatArray) {
        require(target.size >= columns * rows * 2) { "Target too small" }
        for (i in 0 until columns * rows) {
            target[i * 2] = dx[i]
            target[i * 2 + 1] = dy[i]
        }
    }

    /**
     * 이 격자(손으로 민 것)에 [warps](얼굴 보정 등)를 합친 새 격자([compose]). 적용할 때 이것 하나를 원본 해상도에 그린다.
     * [warps]가 없으면 사본이다.
     */
    fun composedWith(warps: List<MeshWarp>): LiquifyMesh {
        if (warps.isEmpty()) return copy()
        val field = FloatArray(columns * rows * 2)
        copyInto(field)
        compose(field, columns, rows, cellSize, warps)
        return LiquifyMesh(size, cellSize, FloatArray(columns * rows) { field[it * 2] }, FloatArray(columns * rows) { field[it * 2 + 1] })
    }

    companion object {
        /** 이보다 작은 변위는 움직이지 않은 것으로 본다(픽셀) */
        const val EPSILON = 0.01f

        /** [forward]의 반복 수 */
        const val FORWARD_ITERATIONS = 8

        /** [forward]가 한 번에 이보다(픽셀) 덜 옮기면 모인 것으로 본다 */
        const val FORWARD_TOLERANCE = 0.01f

        /** 꼭짓점 하나가 가질 수 있는 가장 큰 변위 길이(문서 픽셀) */
        const val MAX_DISPLACEMENT = 1000f

        /** 오목·볼록 한 번에 브러시 가운데 쪽으로 옮기는 비율(세기 100%, 가운데 기준) */
        const val PINCH_STEP = 0.08f

        /** 재구성 한 번에 되돌리는 비율(세기 100%, 가운데 기준) */
        const val RECONSTRUCT_STEP = 0.25f

        /** 꼭짓점 수가 이 정도를 넘지 않게 칸 크기를 고른다 */
        const val MAX_VERTICES = 400_000

        /** 가장 촘촘한 칸(픽셀) */
        const val MIN_CELL = 4

        /**
         * 꼭짓점 변위들([field], [copyInto] 형식: 손으로 민 격자)에 [warps]를 합친다. 결과 자리 q가 읽을 곳은 q + 격자 변위에서 시작해
         * [warps]를 차례로 따라간 곳이다(원래 그림에 [warps]를 먼저 걸고, 손으로 민 것을 그 위에 건 것과 같다).
         * 부위를 더하지 않고 이어 걸어 겹치는 부위도 접히지 않는다. 합친 변위도 [MAX_DISPLACEMENT]로 묶는다.
         * 꼭짓점 하나에 몇 번의 덧셈으로 상자 밖을 건너뛰어 큰 문서(꼭짓점 40만 개)도 몇 ms다. 다른 스레드에서 불러도 된다.
         */
        fun compose(field: FloatArray, columns: Int, rows: Int, cellSize: Int, warps: List<MeshWarp>) {
            if (warps.isEmpty()) return
            require(field.size >= columns * rows * 2) { "Field too small" }
            val left = warps.minOf { it.left }
            val top = warps.minOf { it.top }
            val right = warps.maxOf { it.right }
            val bottom = warps.maxOf { it.bottom }
            val step = FloatArray(2)
            for (row in 0 until rows) {
                val qy = (row * cellSize).toFloat()
                for (column in 0 until columns) {
                    val i = (row * columns + column) * 2
                    val qx = (column * cellSize).toFloat()
                    var sx = qx + field[i]
                    var sy = qy + field[i + 1]
                    if (sx < left || sx > right || sy < top || sy > bottom) continue
                    for (warp in warps) {
                        if (!warp.covers(sx, sy)) continue
                        warp.displacement(sx, sy, step)
                        sx += step[0]
                        sy += step[1]
                    }
                    var mx = sx - qx
                    var my = sy - qy
                    val length = sqrt(mx * mx + my * my)
                    if (length > MAX_DISPLACEMENT) {
                        mx *= MAX_DISPLACEMENT / length
                        my *= MAX_DISPLACEMENT / length
                    }
                    field[i] = mx
                    field[i + 1] = my
                }
            }
        }

        /** [size] 문서의 빈(움직이지 않은) 격자 */
        fun identity(size: PixelSize): LiquifyMesh {
            val cell = cellSizeFor(size)
            val columns = size.width / cell + 2
            val rows = size.height / cell + 2
            return LiquifyMesh(size, cell, FloatArray(columns * rows), FloatArray(columns * rows))
        }

        /** [size] 문서 격자의 칸(꼭짓점이 [MAX_VERTICES]쯤을 넘지 않는 가장 촘촘한 칸, [MIN_CELL] 이상) */
        fun cellSizeFor(size: PixelSize): Int = max(MIN_CELL, ceil(sqrt(size.width.toDouble() * size.height / MAX_VERTICES)).toInt())

        /** [size] 문서 격자 하나가 쓰는 메모리(바이트, [byteCount]). 격자를 만들지 않고 어림할 때 쓴다(불러올 메모리 어림). */
        fun byteCountFor(size: PixelSize): Long {
            val cell = cellSizeFor(size)
            return (size.width / cell + 2).toLong() * (size.height / cell + 2) * 2 * Float.SIZE_BYTES
        }

        /**
         * 꼭짓점 변위 [dx]·[dy]로 만든 격자(저장한 격자를 읽을 때, [LiquifyMeshCodec]). 칸·배열 크기가 [size]와 맞지 않으면 IllegalArgumentException이다.
         * 배열은 그대로 쓴다(부르는 쪽이 넘긴 뒤 바꾸지 않는다).
         */
        internal fun of(size: PixelSize, cellSize: Int, dx: FloatArray, dy: FloatArray): LiquifyMesh = LiquifyMesh(size, cellSize, dx, dy)
    }
}
