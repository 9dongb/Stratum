package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize

/** 이미지 안의 픽셀 위치. */
data class PixelPoint(val x: Int, val y: Int)

/**
 * 이미지를 90° 단위로 돌리거나 뒤집는 방향. 모두 8가지다.
 *
 * 먼저 [mirrored]이면 좌우로 뒤집고, 그다음 시계 방향으로 [quarterTurns]×90° 돌린다.
 * 픽셀 값은 그대로 두고 자리만 옮기므로, 몇 번을 돌려도 화질이 그대로다.
 */
enum class Orientation(val quarterTurns: Int, val mirrored: Boolean) {
    IDENTITY(0, false),
    ROTATE_CLOCKWISE(1, false),
    ROTATE_180(2, false),
    ROTATE_COUNTERCLOCKWISE(3, false),
    FLIP_HORIZONTAL(0, true),

    /** 오른쪽 위–왼쪽 아래 대각선을 축으로 뒤집기 */
    TRANSVERSE(1, true),
    FLIP_VERTICAL(2, true),

    /** 왼쪽 위–오른쪽 아래 대각선을 축으로 뒤집기 */
    TRANSPOSE(3, true);

    /** 이 방향으로 바꾸면 가로·세로가 바뀌는지(90°·270°). */
    val swapsAxes: Boolean
        get() = quarterTurns % 2 == 1

    /** 이 방향을 되돌리는 방향. 뒤집기가 들어간 방향은 자기 자신이다. */
    val inverse: Orientation
        get() = if (mirrored) this else of(-quarterTurns, mirrored = false)

    /** 이 방향으로 바꾼 뒤 [next] 방향으로 또 바꾼 것과 같은 방향. */
    fun then(next: Orientation): Orientation =
        // 뒤집은 뒤 돌리는 것은, 거꾸로 돌린 뒤 뒤집는 것과 같다
        if (next.mirrored) of(next.quarterTurns - quarterTurns, !mirrored) else of(quarterTurns + next.quarterTurns, mirrored)

    /** [size] 이미지를 이 방향으로 바꾼 크기. */
    fun targetSize(size: PixelSize): PixelSize = if (swapsAxes) PixelSize(size.height, size.width) else size

    /** [width]×[height] 이미지의 픽셀 ([x], [y])가 옮겨 가는 자리. */
    fun mapPixel(x: Int, y: Int, width: Int, height: Int): PixelPoint {
        val mx = if (mirrored) width - 1 - x else x
        return when (quarterTurns) {
            0 -> PixelPoint(mx, y)
            1 -> PixelPoint(height - 1 - y, mx)
            2 -> PixelPoint(width - 1 - mx, height - 1 - y)
            else -> PixelPoint(y, width - 1 - mx)
        }
    }

    /**
     * [width]×[height] 이미지 안의 점 ([x], [y])가 옮겨 가는 자리. 픽셀 가장자리를 기준으로 한 실수 좌표라서, 픽셀 (x, y)를 덮는
     * 사각형 (x, y)~(x + 1, y + 1)은 [mapPixel]이 옮긴 픽셀을 덮는 사각형으로 간다. 자르기·회전 화면이 테두리·보이는 영역을 돌리고,
     * 돌린 모습을 그리는 데 쓴다(사용자 피드백 8차 1).
     */
    fun mapPoint(x: Double, y: Double, width: Double, height: Double): Pair<Double, Double> {
        val mx = if (mirrored) width - x else x
        return when (quarterTurns) {
            0 -> mx to y
            1 -> (height - y) to mx
            2 -> (width - mx) to (height - y)
            else -> y to (width - mx)
        }
    }

    /** [width]×[height] 이미지 안의 [rect]가 옮겨 가는 사각형. 사각형은 돌리거나 뒤집어도 사각형이다. */
    fun mapRect(rect: PixelRect, width: Int, height: Int): PixelRect {
        require(rect.width > 0 && rect.height > 0) { "Rect must not be empty: $rect" }
        val first = mapPixel(rect.left, rect.top, width, height)
        val last = mapPixel(rect.right - 1, rect.bottom - 1, width, height)
        return PixelRect(
            left = minOf(first.x, last.x),
            top = minOf(first.y, last.y),
            right = maxOf(first.x, last.x) + 1,
            bottom = maxOf(first.y, last.y) + 1
        )
    }

    companion object {
        /** 시계 방향 [quarterTurns]×90°(음수면 반시계)와 [mirrored]로 정한 방향. */
        fun of(quarterTurns: Int, mirrored: Boolean): Orientation {
            val turns = Math.floorMod(quarterTurns, 4)
            return entries.first { it.quarterTurns == turns && it.mirrored == mirrored }
        }
    }
}
