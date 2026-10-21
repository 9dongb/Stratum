package com.kiln.stratum.ui.layout

/** 한 축 위의 구간(픽셀). [start] 이상 [end] 미만이다. */
data class Span(val start: Int, val end: Int) {

    /** [offset]만큼 옮긴 구간. 창 좌표를 한 영역 안의 좌표로 바꿀 때 쓴다. */
    fun shiftedBy(offset: Int): Span = Span(start + offset, end + offset)
}

/**
 * 화면이 접히는 부분(힌지) 하나. 위치는 창 안의 픽셀 좌표다.
 *
 * 안드로이드 Rect 대신 정수로 둬서 JVM 테스트에서도 쓴다. 완전히 펼친 폴더블은 폭이 0인 선으로 알려 준다.
 *
 * @property isVertical 세로로 지나가 화면을 왼쪽·오른쪽으로 나누는지. false면 가로로 지나가 위·아래로 나눈다.
 */
data class Hinge(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isVertical: Boolean
) {

    /**
     * 버튼을 두지 않을 띠(창 좌표). 힌지를 가로지르는 축에서 양쪽으로 [marginPx]만큼 넓힌 구간이다.
     * 세로 힌지면 x 구간, 가로 힌지면 y 구간이다.
     */
    fun band(marginPx: Int): Span =
        if (isVertical) Span(left - marginPx, right + marginPx) else Span(top - marginPx, bottom + marginPx)
}

/**
 * 크기가 [sizes]인 것들을 [gap] 간격으로 0부터 차례로 쌓고, 각 시작 위치를 돌려준다.
 * [bands](같은 좌표)에 걸리는 것은 그 띠 바로 뒤로 옮기고, 그 뒤의 것들도 이어서 쌓는다.
 * 쌓는 쪽의 크기는 띠와 상관없이 정해지므로, 끝의 것이 영역 밖으로 나갈 수 있다.
 */
fun stackAvoiding(sizes: List<Int>, gap: Int, bands: List<Span>): List<Int> {
    var next = 0
    return sizes.map { size ->
        var start = next
        // 옮긴 자리가 다른 띠에 다시 걸릴 수 있어, 더 옮길 것이 없을 때까지 본다
        var moved = true
        while (moved) {
            moved = false
            for (band in bands) {
                if (start < band.end && start + size > band.start) {
                    start = band.end
                    moved = true
                }
            }
        }
        next = start + size + gap
        start
    }
}

/** 화면 안의 사각형 영역(픽셀). 왼쪽·위는 들어가고 오른쪽·아래는 들어가지 않는다. */
data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}

/**
 * 크기가 [width]×[height]인 옵션 창을 칸 [anchor] 오른쪽([gap]만큼 띄움)에, 위 끝을 칸 위 끝에 맞춰 놓을 왼쪽 위 자리.
 * [region] 밖으로 나가면 안으로 민다. 옵션 창이 [region]보다 크면 왼쪽·위 끝에 맞춘다.
 */
fun flyoutOffset(anchor: Region, width: Int, height: Int, region: Region, gap: Int): Pair<Int, Int> {
    val x = (anchor.right + gap).coerceAtMost(region.right - width).coerceAtLeast(region.left)
    val y = anchor.top.coerceAtMost(region.bottom - height).coerceAtLeast(region.top)
    return x to y
}

/**
 * 띠 칸 [anchor] 옆에 띄우는 옵션 창이 들어갈 수 있는 오른쪽·왼쪽 영역(창 좌표). 오른쪽은 칸 오른쪽에서 [gap]만큼 띈 자리부터 [area] 끝까지,
 * 왼쪽은 [area] 처음부터 칸 왼쪽에서 [gap]만큼 띈 자리까지이고, 세로는 둘 다 [area] 전체다. 자리가 없으면 폭이 0 이하다.
 * 띠를 옮길 수 있어(사용자 피드백 9차) 오른쪽 끝에 둔 띠는 왼쪽에 띄운다. 접히는 선은 보지 않는다(사용자 피드백 11차 1).
 */
fun flyoutSides(anchor: Region, area: Region, gap: Int): Pair<Region, Region> =
    Region(anchor.right + gap, area.top, area.right, area.bottom) to Region(area.left, area.top, anchor.left - gap, area.bottom)

/**
 * 폭 [width]인 옵션 창을 칸 [anchor]의 왼쪽([left])에 띄울지. 오른쪽([right])에 칸 바로 옆으로 들어가면 오른쪽, 아니고 왼쪽에 칸 바로 옆으로
 * 들어가면 왼쪽이다. 둘 다 아니면 넓은 쪽이다. [right]·[left]는 [flyoutSides]의 것이다.
 */
fun flyoutOnLeft(width: Int, anchor: Region, right: Region, left: Region, gap: Int): Boolean {
    val rightFits = right.width >= width && right.left == anchor.right + gap
    val leftFits = left.width >= width && left.right == anchor.left - gap
    return when {
        rightFits -> false
        leftFits -> true
        else -> left.width > right.width
    }
}

/** 크기 [width]×[height]인 것의 왼쪽 위를 ([x], [y])에 두되 [piece] 안으로 민 자리. [piece]보다 크면 왼쪽·위 끝이다. */
fun placeInside(x: Int, y: Int, width: Int, height: Int, piece: Region): Pair<Int, Int> =
    x.coerceAtMost(piece.right - width).coerceAtLeast(piece.left) to y.coerceAtMost(piece.bottom - height).coerceAtLeast(piece.top)

/**
 * 캔버스 왼쪽 위 배율 표시(크기 [width]×[height])의 왼쪽 위 자리(창 좌표, 사용자 피드백 10차 8). 바라는 자리([x], [y])가
 * 피할 곳([avoid], 도구 옵션 띠)과 [gap]보다 가까우면 그 오른쪽([gap]만큼 띄움)으로 옮긴다. 접히는 선은 보지 않는다(사용자 피드백 11차 1).
 */
fun zoomChipSpot(x: Int, y: Int, width: Int, height: Int, avoid: Region?, gap: Int): Pair<Int, Int> {
    val blocked = avoid?.takeIf { it.left - gap < x + width && x < it.right + gap && it.top - gap < y + height && y < it.bottom + gap }
    return (blocked?.let { it.right + gap } ?: x) to y
}

/**
 * 작업 표시줄 버튼들(폭 [widths], 왼쪽부터, 사이 [gap])의 왼쪽 자리(사용자 피드백 10차 1). 버튼 줄의 가운데를 [center](화면 가운데)에 맞추되
 * [area] 안으로 민다(줄이 [area]보다 넓으면 왼쪽 끝부터). 접히는 선이 지나도 나누지 않는다(사용자 피드백 11차 1).
 */
fun actionBarStarts(widths: List<Int>, center: Int, area: Span, gap: Int): List<Int> {
    val total = if (widths.isEmpty()) 0 else widths.sum() + gap * (widths.size - 1)
    var x = (center - total / 2).coerceAtMost(area.end - total).coerceAtLeast(area.start)
    return widths.map { width -> x.also { x += width + gap } }
}

/**
 * 높이 [height]인 도구 옵션 띠의 위 끝(창 좌표). 고른 도구 버튼([anchor]의 위아래)의 가운데에 띠의 가운데를 맞춘다.
 * 위쪽 자리가 모자라면 버튼 위 끝에서 시작하고(사용자 요청), 아래쪽 자리가 모자라면 버튼 아래 끝에서 끝낸다.
 * 그래도 [piece](도구가 든 조각의 위아래 구간) 밖으로 나가면 안으로 민다. 띠가 조각보다 크면 조각 위 끝이다.
 */
fun stripTop(anchor: Span, height: Int, piece: Span): Int {
    val centered = (anchor.start + anchor.end) / 2 - height / 2
    val top = when {
        centered < piece.start -> anchor.start
        centered + height > piece.end -> anchor.end - height
        else -> centered
    }
    return top.coerceAtMost(piece.end - height).coerceAtLeast(piece.start)
}
