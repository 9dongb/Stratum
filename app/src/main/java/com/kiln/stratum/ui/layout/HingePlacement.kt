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

/**
 * 폭이 [width]인 줄에 폭이 [childWidth]인 두 개를 놓고, 두 시작 위치를 돌려준다.
 * 띠 [band](같은 좌표)가 줄을 지나면 띠 양쪽에 하나씩 붙여 놓고, 없으면 양 끝에 놓는다.
 * 줄 밖으로는 나가지 않게 맞춘다. 띠가 한쪽 끝에 너무 가까우면 띠에 걸칠 수 있다.
 */
fun splitAround(width: Int, childWidth: Int, band: Span?): Pair<Int, Int> {
    val last = (width - childWidth).coerceAtLeast(0)
    if (band == null || band.end <= 0 || band.start >= width) return 0 to last
    return (band.start - childWidth).coerceIn(0, last) to band.end.coerceIn(0, last)
}
