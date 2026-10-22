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

    @Test
    fun turnedOrigin_keepsTheCenter_andTurningBackComesHome() {
        // 레이어 돌리기(사용자 피드백 12차 2): 내용 사각형의 가운데를 축으로 돌린다
        assertEquals(PixelPoint(150, -50), Orientation.ROTATE_CLOCKWISE.turnedOrigin(PixelRect(100, 0, 500, 300)))
        // 뒤집기·180°는 자리가 그대로다
        for (orientation in listOf(Orientation.FLIP_HORIZONTAL, Orientation.FLIP_VERTICAL, Orientation.ROTATE_180)) {
            assertEquals("$orientation", PixelPoint(7, 9), orientation.turnedOrigin(PixelRect(7, 9, 20, 40)))
        }
        // 가로·세로 차이가 홀수라 가운데가 반 픽셀에 걸려도, 왼쪽으로 돌린 뒤 오른쪽으로 돌리면(그 반대도) 제자리다
        val rects = listOf(PixelRect(10, 20, 13, 22), PixelRect(0, 0, 4, 1), PixelRect(5, 5, 6, 10), PixelRect(31, 17, 132, 66))
        for (rect in rects) {
            for (orientation in Orientation.entries) {
                val first = orientation.turnedOrigin(rect)
                val turned = orientation.targetSize(PixelSize(rect.width, rect.height))
                val back = orientation.inverse.turnedOrigin(PixelRect(first.x, first.y, first.x + turned.width, first.y + turned.height))
                assertEquals("$rect $orientation", PixelPoint(rect.left, rect.top), back)
            }
            // 오른쪽으로 네 번 돌려도 제자리다
            var current = rect
            repeat(4) {
                val origin = Orientation.ROTATE_CLOCKWISE.turnedOrigin(current)
                current = PixelRect(origin.x, origin.y, origin.x + current.height, origin.y + current.width)
            }
            assertEquals("$rect", rect, current)
        }
    }

    private inline fun forEachPixel(size: PixelSize, action: (x: Int, y: Int) -> Unit) {
        for (y in 0 until size.height) {
            for (x in 0 until size.width) action(x, y)
        }
    }
}
