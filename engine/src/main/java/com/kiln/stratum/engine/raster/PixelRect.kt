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

    /** 이 사각형과 [other]를 모두 담는 가장 작은 사각형. */
    fun union(other: PixelRect): PixelRect =
        PixelRect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

    /** [other]를 모두 담는지. */
    operator fun contains(other: PixelRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    /** 가로로 [dx], 세로로 [dy]만큼 옮긴 사각형. */
    fun offset(dx: Int, dy: Int): PixelRect = PixelRect(left + dx, top + dy, right + dx, bottom + dy)

    /** 네 쪽으로 [margin]만큼 넓힌 사각형(문서 밖으로 나갈 수 있다). */
    fun expanded(margin: Int): PixelRect = PixelRect(left - margin, top - margin, right + margin, bottom + margin)

    companion object {
        /** [size] 문서 전체. */
        fun of(size: PixelSize): PixelRect = PixelRect(0, 0, size.width, size.height)
    }
}
