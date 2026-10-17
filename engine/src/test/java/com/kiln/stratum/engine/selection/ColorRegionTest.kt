package com.kiln.stratum.engine.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorRegionTest {

    private val red = 0xFFFF0000.toInt()
    private val darkRed = 0xFFE00000.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** 'R'은 빨강, 'r'은 조금 어두운 빨강, 'B'는 파랑인 그림 */
    private fun image(vararg rows: String): IntArray =
        rows.flatMap { row -> row.map { when (it) { 'R' -> red; 'r' -> darkRed; else -> blue } } }.toIntArray()

    private fun picked(result: ByteArray, width: Int): List<String> =
        result.toList().chunked(width).map { row -> row.joinToString("") { if (it == 0.toByte()) "." else "#" } }

    @Test
    fun contiguous_picksOnlyTheConnectedArea() {
        val pixels = image(
            "RRBR",
            "RBBR",
            "RRBB"
        )

        val result = ColorRegion.select(pixels, 4, 3, seedX = 0, seedY = 0, tolerance = 0, contiguous = true)

        assertEquals(listOf("##..", "#...", "##.."), picked(result, 4))
    }

    @Test
    fun notContiguous_picksEverySimilarPixel() {
        val pixels = image("RRBR", "RBBR", "RRBB")

        val result = ColorRegion.select(pixels, 4, 3, seedX = 0, seedY = 0, tolerance = 0, contiguous = false)

        assertEquals(listOf("##.#", "#..#", "##.."), picked(result, 4))
    }

    @Test
    fun tolerance_includesNearColors() {
        val pixels = image("RrB", "rrB")

        // 빨강 차이 0x1F = 31
        assertEquals(listOf("#..", "..."), picked(ColorRegion.select(pixels, 3, 2, 0, 0, tolerance = 30, contiguous = true), 3))
        assertEquals(listOf("##.", "##."), picked(ColorRegion.select(pixels, 3, 2, 0, 0, tolerance = 31, contiguous = true), 3))
    }

    @Test
    fun fillsAroundCorners_inAMaze() {
        // 오른쪽으로 돌아 들어가는 길
        val pixels = image(
            "RRRRR",
            "BBBBR",
            "RRRBR",
            "RBRRR"
        )

        val result = ColorRegion.select(pixels, 5, 4, seedX = 0, seedY = 0, tolerance = 0, contiguous = true)

        assertEquals(listOf("#####", "....#", "###.#", "#.###"), picked(result, 5))
    }

    @Test
    fun transparentPixels_matchEachOther_whenPremultiplied() {
        // 알파를 미리 곱한 투명은 모두 0이다
        val pixels = intArrayOf(0, 0, red, 0)

        val result = ColorRegion.select(pixels, 4, 1, seedX = 0, seedY = 0, tolerance = 0, contiguous = false)

        assertEquals(listOf("##.#"), picked(result, 4))
    }
}
