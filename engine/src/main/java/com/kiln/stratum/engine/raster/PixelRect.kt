package com.kiln.stratum.engine.raster

/** 문서 안의 사각형(픽셀). 왼쪽·위는 들어가고 오른쪽·아래는 들어가지 않는다. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    init {
        require(right >= left && bottom >= top) { "Rect must not be inverted: $this" }
    }

    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    /** [other]와 겹치는 부분. 겹치지 않으면 null. */
    fun intersect(other: PixelRect): PixelRect? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) PixelRect(l, t, r, b) else null
    }

    /** [other]를 모두 담는지. */
    operator fun contains(other: PixelRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    companion object {
        /** [size] 문서 전체. */
        fun of(size: PixelSize): PixelRect = PixelRect(0, 0, size.width, size.height)
    }
}
