package com.kiln.stratum.engine.selection

/**
 * 선택 영역 윤곽선(개미 행렬)을 찾는다. 줄인 마스크 한 장(폭 [width], 높이 [height], 픽셀마다 알파 0~255)에서
 * 알파가 [THRESHOLD](반)를 지나는 곳을 마칭 스퀘어로 잇는다. 포토샵도 반쯤 고른 곳에 윤곽선을 그린다.
 *
 * 줄인 마스크(긴 변 2048 이하)에서만 돈다. 원본 크기에서 픽셀을 하나씩 돌지 않는다(결정 기록 '선택 도구').
 * 마스크 밖은 0으로 보아, 가장자리까지 고른 영역도 닫힌 선이 된다.
 */
object MaskContour {

    /** 윤곽선을 그을 알파 */
    const val THRESHOLD = 128

    /**
     * 윤곽선들. 하나는 점들(x0, y0, x1, y1, …)이고, 좌표는 마스크 픽셀 가운데를 (i + 0.5)로 둔 마스크 좌표다.
     * 닫힌 선은 끝 점이 첫 점과 같다.
     */
    fun trace(alphas: ByteArray, width: Int, height: Int): List<FloatArray> {
        require(width > 0 && height > 0 && alphas.size >= width * height) { "Bad mask: ${width}x$height, ${alphas.size}" }
        // 칸은 픽셀 가운데 넷을 꼭짓점으로 하는 사각형이다. 마스크 밖 한 줄까지(0으로) 넣어 (width + 1) × (height + 1)칸이다.
        fun alpha(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= width || y >= height) 0 else alphas[y * width + x].toInt() and 0xFF
        fun inside(x: Int, y: Int) = alpha(x, y) >= THRESHOLD

        // 변 번호: 가로변(x, y)는 (x, y)-(x + 1, y) 사이, 세로변(x, y)는 (x, y)-(x, y + 1) 사이. 꼭짓점 좌표는 -1..width, -1..height
        val columns = width + 2
        fun horizontal(x: Int, y: Int) = ((y + 1) * columns + (x + 1)) * 2
        fun vertical(x: Int, y: Int) = ((y + 1) * columns + (x + 1)) * 2 + 1

        // 변 위의 점: 두 꼭짓점 알파를 이어 THRESHOLD가 되는 자리
        fun pointOn(edge: Int): Pair<Float, Float> {
            val cell = edge / 2
            val x = cell % columns - 1
            val y = cell / columns - 1
            return if (edge % 2 == 0) {
                val a = alpha(x, y)
                val b = alpha(x + 1, y)
                Pair(x + 0.5f + fraction(a, b), y + 0.5f)
            } else {
                val a = alpha(x, y)
                val b = alpha(x, y + 1)
                Pair(x + 0.5f, y + 0.5f + fraction(a, b))
            }
        }

        // 선분: 변 → 이어지는 변. 한 변에는 선분이 하나만 들어오고 하나만 나간다(안쪽을 왼쪽에 두고 돈다).
        val next = HashMap<Int, Int>()
        for (y in -1 until height) {
            for (x in -1 until width) {
                // 꼭짓점: 왼쪽 위(x, y), 오른쪽 위(x + 1, y), 오른쪽 아래(x + 1, y + 1), 왼쪽 아래(x, y + 1)
                val tl = inside(x, y)
                val tr = inside(x + 1, y)
                val br = inside(x + 1, y + 1)
                val bl = inside(x, y + 1)
                val top = horizontal(x, y)
                val right = vertical(x + 1, y)
                val bottom = horizontal(x, y + 1)
                val left = vertical(x, y)
                val case = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
                when (case) {
                    0, 15 -> Unit
                    1 -> next[left] = bottom
                    2 -> next[bottom] = right
                    3 -> next[left] = right
                    4 -> next[right] = top
                    5 -> {
                        // 안장점: 가운데 평균으로 가른다
                        if (centerInside(x, y, ::alpha)) {
                            next[left] = top
                            next[right] = bottom
                        } else {
                            next[left] = bottom
                            next[right] = top
                        }
                    }
                    6 -> next[bottom] = top
                    7 -> next[left] = top
                    8 -> next[top] = left
                    9 -> next[top] = bottom
                    10 -> {
                        if (centerInside(x, y, ::alpha)) {
                            next[top] = right
                            next[bottom] = left
                        } else {
                            next[top] = left
                            next[bottom] = right
                        }
                    }
                    11 -> next[top] = right
                    12 -> next[right] = left
                    13 -> next[right] = bottom
                    14 -> next[bottom] = left
                }
            }
        }

        // 변을 따라 이어 닫힌 선을 만든다
        val lines = ArrayList<FloatArray>()
        val visited = HashSet<Int>()
        for (start in next.keys) {
            if (start in visited) continue
            val points = ArrayList<Float>()
            var edge = start
            while (edge !in visited) {
                visited += edge
                val (px, py) = pointOn(edge)
                points += px
                points += py
                edge = next[edge] ?: break
            }
            if (edge == start) {
                val (px, py) = pointOn(start)
                points += px
                points += py
            }
            if (points.size >= 4) lines += points.toFloatArray()
        }
        return lines
    }

    /** 알파 [a]에서 [b]로 갈 때 THRESHOLD가 되는 비율(0~1) */
    private fun fraction(a: Int, b: Int): Float {
        if (a == b) return 0.5f
        return ((THRESHOLD - 0.5f - a) / (b - a)).coerceIn(0f, 1f)
    }

    private fun centerInside(x: Int, y: Int, alpha: (Int, Int) -> Int): Boolean =
        (alpha(x, y) + alpha(x + 1, y) + alpha(x + 1, y + 1) + alpha(x, y + 1)) / 4f >= THRESHOLD
}
