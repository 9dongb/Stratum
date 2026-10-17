package com.kiln.stratum.engine.brush

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** 문서 좌표(픽셀, 소수)의 한 점. 손가락이 지난 점과 브러시 자국의 가운데에 쓴다. */
data class StrokePoint(val x: Float, val y: Float)

/**
 * 손가락이 지난 점들을 따라 같은 간격으로 브러시 자국(dab)을 놓는다. 첫 점에 하나를 놓고, 그 뒤로 지나온 거리가
 * 간격만큼 쌓일 때마다 하나씩 놓는다. 점 사이는 직선으로 잇는다. 끄는 동안 점이 하나씩 오므로 이어서 놓을 수 있다([add]).
 *
 * 같은 점들은 한 번에 넣든 나눠 넣든 같은 자국이 된다(끄는 동안의 미리 보기와 놓은 뒤의 기록이 같다).
 */
class StrokeDabs(private val spacing: Float) {

    init {
        require(spacing > 0f) { "Spacing must be positive: $spacing" }
    }

    private val placed = ArrayList<StrokePoint>()
    private var last: StrokePoint? = null

    /** 앞 자국 뒤로 지나온 거리 */
    private var travelled = 0f

    /** 지금까지 놓은 자국(앞에서부터) */
    val dabs: List<StrokePoint>
        get() = placed

    /** 손가락이 [point]까지 왔다. 새로 놓은 자국들을 돌려준다. */
    fun add(point: StrokePoint): List<StrokePoint> {
        val previous = last
        last = point
        if (previous == null) {
            placed += point
            return listOf(point)
        }
        val distance = hypot(point.x - previous.x, point.y - previous.y)
        if (distance == 0f) return emptyList()
        val added = ArrayList<StrokePoint>()
        // 앞 자국에서 [spacing]만큼 떨어진 곳마다 놓는다
        var along = spacing - travelled
        while (along <= distance) {
            val t = along / distance
            added += StrokePoint(previous.x + (point.x - previous.x) * t, previous.y + (point.y - previous.y) * t)
            along += spacing
        }
        travelled = distance - (along - spacing)
        placed += added
        return added
    }

    companion object {
        /** [points]를 따라 놓은 자국 전부 */
        fun place(points: List<StrokePoint>, spacing: Float): List<StrokePoint> {
            val dabs = StrokeDabs(spacing)
            for (point in points) dabs.add(point)
            return dabs.dabs
        }

        /**
         * 반지름 [radius]인 자국들이 닿는 문서 영역(픽셀 경계로 넓힘). [document] 밖은 자른다.
         * 문서에 하나도 닿지 않으면 null.
         */
        fun bounds(dabs: List<StrokePoint>, radius: Float, document: PixelSize): PixelRect? {
            if (dabs.isEmpty()) return null
            // 경계 픽셀의 부드러운 가장자리까지 넣는다
            val reach = radius + 1f
            val left = floor(dabs.minOf { it.x } - reach).toInt()
            val top = floor(dabs.minOf { it.y } - reach).toInt()
            val right = ceil(dabs.maxOf { it.x } + reach).toInt()
            val bottom = ceil(dabs.maxOf { it.y } + reach).toInt()
            return PixelRect(
                left.coerceIn(0, document.width),
                top.coerceIn(0, document.height),
                right.coerceIn(0, document.width),
                bottom.coerceIn(0, document.height)
            ).takeIf { it.width > 0 && it.height > 0 }
        }
    }
}
