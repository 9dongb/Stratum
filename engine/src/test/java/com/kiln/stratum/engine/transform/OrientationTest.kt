package com.kiln.stratum.engine.transform

import com.kiln.stratum.engine.raster.PixelRect
import com.kiln.stratum.engine.raster.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationTest {

    /** 3×2 이미지의 왼쪽 위(A), 오른쪽 위(B), 왼쪽 아래(C) 픽셀이 옮겨 가는 자리. */
    @Test
    fun mapPixel_movesCornersAsExpected() {
        val expected = mapOf(
            Orientation.IDENTITY to listOf(PixelPoint(0, 0), PixelPoint(2, 0), PixelPoint(0, 1)),
            // 시계 방향: 왼쪽 위가 오른쪽 위로 간다
            Orientation.ROTATE_CLOCKWISE to listOf(PixelPoint(1, 0), PixelPoint(1, 2), PixelPoint(0, 0)),
            Orientation.ROTATE_180 to listOf(PixelPoint(2, 1), PixelPoint(0, 1), PixelPoint(2, 0)),
            // 반시계 방향: 왼쪽 위가 왼쪽 아래로 간다
            Orientation.ROTATE_COUNTERCLOCKWISE to listOf(PixelPoint(0, 2), PixelPoint(0, 0), PixelPoint(1, 2)),
            Orientation.FLIP_HORIZONTAL to listOf(PixelPoint(2, 0), PixelPoint(0, 0), PixelPoint(2, 1)),
            Orientation.FLIP_VERTICAL to listOf(PixelPoint(0, 1), PixelPoint(2, 1), PixelPoint(0, 0)),
            Orientation.TRANSPOSE to listOf(PixelPoint(0, 0), PixelPoint(0, 2), PixelPoint(1, 0)),
            Orientation.TRANSVERSE to listOf(PixelPoint(1, 2), PixelPoint(1, 0), PixelPoint(0, 2))
        )
        assertEquals(Orientation.entries.toSet(), expected.keys)
        for ((orientation, corners) in expected) {
            val actual = listOf(
                orientation.mapPixel(0, 0, 3, 2),
                orientation.mapPixel(2, 0, 3, 2),
                orientation.mapPixel(0, 1, 3, 2)
            )
            assertEquals("$orientation", corners, actual)
        }
    }

    @Test
    fun targetSize_swapsOnlyForQuarterTurns() {
        val size = PixelSize(3, 2)
        for (orientation in Orientation.entries) {
            val expected = if (orientation.quarterTurns % 2 == 1) PixelSize(2, 3) else size
            assertEquals("$orientation", expected, orientation.targetSize(size))
        }
    }

    @Test
    fun inverse_bringsEveryPixelBack() {
        val size = PixelSize(5, 3)
        for (orientation in Orientation.entries) {
            val target = orientation.targetSize(size)
            forEachPixel(size) { x, y ->
                val moved = orientation.mapPixel(x, y, size.width, size.height)
                assertEquals("$orientation", PixelPoint(x, y), orientation.inverse.mapPixel(moved.x, moved.y, target.width, target.height))
            }
        }
    }

    @Test
    fun then_isTheSameAsMovingTwice() {
        val size = PixelSize(5, 3)
        for (first in Orientation.entries) {
            for (second in Orientation.entries) {
                val middle = first.targetSize(size)
                val combined = first.then(second)
                assertEquals(second.targetSize(middle), combined.targetSize(size))
                forEachPixel(size) { x, y ->
                    val once = first.mapPixel(x, y, size.width, size.height)
                    val twice = second.mapPixel(once.x, once.y, middle.width, middle.height)
                    assertEquals("$first then $second", twice, combined.mapPixel(x, y, size.width, size.height))
                }
            }
        }
    }

    @Test
    fun fourTurnsAndTwoFlips_comeBack() {
        val clockwise = Orientation.ROTATE_CLOCKWISE
        assertEquals(Orientation.IDENTITY, clockwise.then(clockwise).then(clockwise).then(clockwise))
        assertEquals(Orientation.IDENTITY, Orientation.FLIP_HORIZONTAL.then(Orientation.FLIP_HORIZONTAL))
        assertEquals(Orientation.IDENTITY, Orientation.FLIP_VERTICAL.then(Orientation.FLIP_VERTICAL))
        assertEquals(Orientation.IDENTITY, clockwise.then(Orientation.ROTATE_COUNTERCLOCKWISE))
        // 좌우 뒤집고 상하 뒤집으면 180° 돌린 것과 같다
        assertEquals(Orientation.ROTATE_180, Orientation.FLIP_HORIZONTAL.then(Orientation.FLIP_VERTICAL))
    }

    @Test
    fun mapRect_coversTheMovedPixels() {
        val size = PixelSize(7, 4)
        val rect = PixelRect(2, 1, 6, 3)
        for (orientation in Orientation.entries) {
            val moved = (rect.top until rect.bottom).flatMap { y ->
                (rect.left until rect.right).map { x -> orientation.mapPixel(x, y, size.width, size.height) }
            }
            val expected = PixelRect(
                moved.minOf { it.x },
                moved.minOf { it.y },
                moved.maxOf { it.x } + 1,
                moved.maxOf { it.y } + 1
            )
            assertEquals("$orientation", expected, orientation.mapRect(rect, size.width, size.height))
            // 사각형은 돌리거나 뒤집어도 같은 수의 픽셀을 덮는다
            assertEquals(moved.size, expected.width * expected.height)
        }
    }

    @Test
    fun mapPoint_movesEachPixelSquareOntoTheMovedPixel() {
        // 픽셀 (x, y)를 덮는 사각형의 네 모서리를 옮기면, 옮긴 픽셀을 덮는 사각형이 된다
        val size = PixelSize(5, 3)
        for (orientation in Orientation.entries) {
            forEachPixel(size) { x, y ->
                val corners = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (dx, dy) ->
                    orientation.mapPoint((x + dx).toDouble(), (y + dy).toDouble(), size.width.toDouble(), size.height.toDouble())
                }
                val moved = orientation.mapPixel(x, y, size.width, size.height)
                assertEquals("$orientation ($x, $y)", moved.x.toDouble(), corners.minOf { it.first }, 0.0)
                assertEquals("$orientation ($x, $y)", moved.y.toDouble(), corners.minOf { it.second }, 0.0)
                assertEquals("$orientation ($x, $y)", moved.x + 1.0, corners.maxOf { it.first }, 0.0)
                assertEquals("$orientation ($x, $y)", moved.y + 1.0, corners.maxOf { it.second }, 0.0)
            }
        }
    }

    private inline fun forEachPixel(size: PixelSize, action: (x: Int, y: Int) -> Unit) {
        for (y in 0 until size.height) {
            for (x in 0 until size.width) action(x, y)
        }
    }
}
