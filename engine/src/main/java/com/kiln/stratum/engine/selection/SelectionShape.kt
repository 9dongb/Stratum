package com.kiln.stratum.engine.selection

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 선택 영역을 그리는 점 하나(문서 좌표, 픽셀). 픽셀 (x, y)는 [x, x + 1) × [y, y + 1)을 차지한다. */
data class SelectionPoint(val x: Float, val y: Float)

/** 타일(사각형) 하나가 모양 안에 얼마나 드는지 */
enum class Coverage {
    /** 모양 밖이다. 고를 픽셀이 없다. */
    OUTSIDE,

    /** 모양 안이다. 모든 픽셀을 다 고른다. */
    INSIDE,

    /** 경계가 지나간다. 픽셀마다 그려 봐야 한다. */
    PARTIAL
}

/**
 * 선택 영역을 만드는 모양(문서 좌표). 사각형·원형 선택과 올가미가 만든다.
 *
 * 타일마다 [coverage]로 안·밖·걸침을 먼저 가려, 모두 안이거나 밖인 타일은 픽셀을 그리지 않는다.
 * 걸친 타일만 플랫폼 쪽에서 모양을 그려(안티에일리어싱) 알파를 얻는다.
 */
sealed interface SelectionShape {

    /** 모양을 담는 가장 작은 정수 사각형(문서 밖일 수 있다). 넓이가 없으면 null. */
    val bounds: PixelRect?

    /** [rect]가 모양 안에 얼마나 드는지 */
    fun coverage(rect: PixelRect): Coverage

    /** 사각형. 왼쪽·위가 오른쪽·아래보다 작다. */
    data class Rectangle(val left: Float, val top: Float, val right: Float, val bottom: Float) : SelectionShape {
        init {
            require(right >= left && bottom >= top) { "Rectangle must not be inverted: $this" }
        }

        override val bounds: PixelRect?
            get() = outerRect(left, top, right, bottom)

        override fun coverage(rect: PixelRect): Coverage = when {
            rect.right <= left || rect.left >= right || rect.bottom <= top || rect.top >= bottom -> Coverage.OUTSIDE
            rect.left >= left && rect.right <= right && rect.top >= top && rect.bottom <= bottom -> Coverage.INSIDE
            else -> Coverage.PARTIAL
        }
    }

    /** 사각형 [left]..[right] × [top]..[bottom]에 꼭 맞는 타원 */
    data class Ellipse(val left: Float, val top: Float, val right: Float, val bottom: Float) : SelectionShape {
        init {
            require(right >= left && bottom >= top) { "Ellipse must not be inverted: $this" }
        }

        override val bounds: PixelRect?
            get() = outerRect(left, top, right, bottom)

        override fun coverage(rect: PixelRect): Coverage {
            val rx = (right - left) / 2.0
            val ry = (bottom - top) / 2.0
            if (rx <= 0.0 || ry <= 0.0) return Coverage.OUTSIDE
            val cx = (left + right) / 2.0
            val cy = (top + bottom) / 2.0
            // 원 하나로 펴서 본다: 사각형에서 가운데에 가장 가까운 점이 원 밖이면 겹치지 않는다
            val nearX = (cx.coerceIn(rect.left.toDouble(), rect.right.toDouble()) - cx) / rx
            val nearY = (cy.coerceIn(rect.top.toDouble(), rect.bottom.toDouble()) - cy) / ry
            if (nearX * nearX + nearY * nearY >= 1.0) return Coverage.OUTSIDE
            // 타원은 볼록해서, 네 꼭짓점이 모두 안이면 사각형 전체가 안이다
            val corners = listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)
            val inside = corners.all { (x, y) ->
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                dx * dx + dy * dy <= 1.0
            }
            return if (inside) Coverage.INSIDE else Coverage.PARTIAL
        }
    }

    /**
     * 올가미: 점을 차례로 이은 닫힌 다각형(끝 점과 첫 점도 잇는다). 선이 스스로 겹치면 짝수 번 감싼 곳은 밖이다(even-odd).
     * 점이 3개보다 적으면 넓이가 없다.
     */
    data class Lasso(val points: List<SelectionPoint>) : SelectionShape {

        override val bounds: PixelRect?
            get() {
                if (points.size < 3) return null
                return outerRect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
            }

        override fun coverage(rect: PixelRect): Coverage {
            val outer = bounds ?: return Coverage.OUTSIDE
            if (rect.intersect(outer) == null) return Coverage.OUTSIDE
            if (edges().any { (a, b) -> segmentTouches(a, b, rect) }) return Coverage.PARTIAL
            // 선이 지나가지 않으면 사각형 전체가 안이거나 밖이다. 가운데 한 점으로 본다.
            return if (contains((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)) Coverage.INSIDE else Coverage.OUTSIDE
        }

        /** 이웃한 점을 잇는 변들(마지막 점 → 첫 점 포함) */
        fun edges(): List<Pair<SelectionPoint, SelectionPoint>> =
            if (points.size < 3) emptyList() else points.indices.map { points[it] to points[(it + 1) % points.size] }

        /** 점 ([x], [y])가 안에 있는지(even-odd) */
        fun contains(x: Float, y: Float): Boolean {
            var inside = false
            for ((a, b) in edges()) {
                if ((a.y > y) != (b.y > y)) {
                    val crossX = a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
                    if (x < crossX) inside = !inside
                }
            }
            return inside
        }
    }

    companion object {
        /** [left]..[right] × [top]..[bottom]을 담는 정수 사각형. 넓이가 없으면 null. */
        internal fun outerRect(left: Float, top: Float, right: Float, bottom: Float): PixelRect? {
            val l = floor(left).toInt()
            val t = floor(top).toInt()
            val r = ceil(right).toInt()
            val b = ceil(bottom).toInt()
            return if (r > l && b > t) PixelRect(l, t, r, b) else null
        }

        /** 선분 [a]-[b]가 사각형 [rect]에 닿는지(사각형 안을 지나거나 끝 점이 안에 있음) */
        internal fun segmentTouches(a: SelectionPoint, b: SelectionPoint, rect: PixelRect): Boolean {
            // 리앙-바스키: 선분을 사각형으로 잘라 남는 부분이 있는지 본다
            var t0 = 0.0
            var t1 = 1.0
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val checks = listOf(
                -dx to (a.x - rect.left).toDouble(),
                dx to (rect.right - a.x).toDouble(),
                -dy to (a.y - rect.top).toDouble(),
                dy to (rect.bottom - a.y).toDouble()
            )
            for ((p, q) in checks) {
                if (p == 0.0) {
                    if (q < 0.0) return false
                } else {
                    val t = q / p
                    if (p < 0.0) t0 = max(t0, t) else t1 = min(t1, t)
                    if (t0 > t1) return false
                }
            }
            return true
        }
    }
}

/** 문서 [size] 안에서 [shape]가 차지하는 사각형. 문서와 겹치지 않으면 null. */
fun SelectionShape.boundsIn(size: PixelSize): PixelRect? = bounds?.intersect(PixelRect.of(size))
