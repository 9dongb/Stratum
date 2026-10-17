package com.kiln.stratum.engine.selection

import kotlin.math.abs

/**
 * 자동 선택(마술봉)의 계산. 줄인 그림 한 장(폭 [width], 높이 [height], 픽셀 배열)에서 시작점의 색과 비슷한 픽셀을 고른다.
 *
 * 픽셀은 32비트 하나에 네 채널(각 8비트)이 든 값이다. 네 채널을 똑같이 보므로 채널 순서는 상관없다.
 * 알파를 미리 곱한 값을 넘기면 완전 투명한 픽셀끼리는 색과 상관없이 같다.
 * 비슷함: 네 채널의 차이가 모두 [tolerance] 이하다.
 *
 * 줄인 그림(긴 변 2048 이하)에서만 돈다. 원본 크기에서 픽셀을 하나씩 돌지 않는다(결정 기록 '선택 도구').
 */
object ColorRegion {

    /** [tolerance]의 범위. 포토샵과 같은 0~255이고 기본은 32다. */
    const val MAX_TOLERANCE = 255
    const val DEFAULT_TOLERANCE = 32

    /**
     * 고른 픽셀은 255(-1), 아니면 0인 배열(크기 [width]×[height]).
     * [contiguous]면 시작점과 위·아래·왼쪽·오른쪽으로 이어진 비슷한 픽셀만, 아니면 그림 전체의 비슷한 픽셀을 고른다.
     */
    fun select(pixels: IntArray, width: Int, height: Int, seedX: Int, seedY: Int, tolerance: Int, contiguous: Boolean): ByteArray {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Bad image: ${width}x$height, ${pixels.size}" }
        require(seedX in 0 until width && seedY in 0 until height) { "Seed outside image: ($seedX, $seedY)" }
        require(tolerance in 0..MAX_TOLERANCE) { "Tolerance out of range: $tolerance" }
        val seed = pixels[seedY * width + seedX]
        val result = ByteArray(width * height)
        if (!contiguous) {
            for (i in 0 until width * height) if (similar(pixels[i], seed, tolerance)) result[i] = SELECTED
            return result
        }
        // 줄 단위로 채운다: 한 줄에서 좌우로 넓힌 뒤, 위·아래 줄에서 새로 이어지는 곳을 쌓아 둔다
        var stack = IntArray(1024)
        var size = 0
        fun push(index: Int) {
            if (size == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[size++] = index
        }
        push(seedY * width + seedX)
        while (size > 0) {
            val index = stack[--size]
            if (result[index] != 0.toByte()) continue
            val y = index / width
            val row = y * width
            var left = index - row
            var right = left
            while (left > 0 && result[row + left - 1] == 0.toByte() && similar(pixels[row + left - 1], seed, tolerance)) left--
            while (right < width - 1 && result[row + right + 1] == 0.toByte() && similar(pixels[row + right + 1], seed, tolerance)) right++
            for (x in left..right) result[row + x] = SELECTED
            for (next in intArrayOf(y - 1, y + 1)) {
                if (next < 0 || next >= height) continue
                val nextRow = next * width
                var x = left
                while (x <= right) {
                    if (result[nextRow + x] == 0.toByte() && similar(pixels[nextRow + x], seed, tolerance)) {
                        push(nextRow + x)
                        // 이어진 구간은 한 번만 쌓는다
                        while (x <= right && similar(pixels[nextRow + x], seed, tolerance)) x++
                    } else {
                        x++
                    }
                }
            }
        }
        return result
    }

    private const val SELECTED: Byte = -1

    private fun similar(a: Int, b: Int, tolerance: Int): Boolean =
        abs((a and 0xFF) - (b and 0xFF)) <= tolerance &&
            abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)) <= tolerance &&
            abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)) <= tolerance &&
            abs((a ushr 24) - (b ushr 24)) <= tolerance
}
