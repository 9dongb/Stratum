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

/**
 * 구간 [cell](예: 격자 칸의 x 구간)이 띠 [bands](같은 좌표)와 겹치면, 띠를 비켜나도록 시작·끝 쪽에서 얼마나 줄일지(픽셀).
 * 띠의 가운데가 칸의 가운데보다 앞이면 시작 쪽을, 아니면 끝 쪽을 줄인다. 겹치지 않으면 (0, 0)이다.
 * 홈의 최근 프로젝트 격자처럼 칸 자리가 정해진 것을 세로 힌지에서 비켜 놓을 때 쓴다.
 */
fun insetsAvoiding(cell: Span, bands: List<Span>): Pair<Int, Int> {
    var start = 0
    var end = 0
    for (band in bands) {
        if (band.end <= cell.start || band.start >= cell.end) continue
        // 두 배로 비교해 나눗셈을 피한다
        if (band.start + band.end < cell.start + cell.end) {
            start = maxOf(start, band.end - cell.start)
        } else {
            end = maxOf(end, cell.end - band.start)
        }
    }
    return start to end
}

/** 화면 안의 사각형 영역(픽셀). 왼쪽·위는 들어가고 오른쪽·아래는 들어가지 않는다. */
data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}

/**
 * 창(카드)을 띄울 영역을 고른다.
 *
 * [area](시스템 바·키보드를 뺀 영역)에서 세로 힌지 띠 [verticalBands](x 구간)와 가로 힌지 띠 [horizontalBands](y 구간)를
 * 빼고 남은 조각 중 하나다. 점수는 min(폭, [maxCardWidth]) × 높이라, 넓기만 하고 낮은 조각보다 카드가 잘 들어가는 곳을 고른다.
 * 점수가 거의 같으면(가장 높은 점수의 98% 이상) 오른쪽, 그다음 위쪽 조각을 고른다.
 * - 시스템 바나 반올림 몇 픽셀 차이로 창이 놓이는 쪽이 바뀌지 않게 한다.
 * - 위쪽을 먼저 고르는 것은 키보드가 아래쪽을 가려서, 아래에 두면 입력하는 동안 창이 옮겨 다니기 때문이다.
 * 띠가 영역을 다 덮어 남는 조각이 없으면 [area]를 그대로 돌려준다.
 */
fun dialogRegion(area: Region, verticalBands: List<Span>, horizontalBands: List<Span>, maxCardWidth: Int): Region {
    val columns = subtract(Span(area.left, area.right), verticalBands)
    val rows = subtract(Span(area.top, area.bottom), horizontalBands)
    val pieces = columns.flatMap { x -> rows.map { y -> Region(x.start, y.start, x.end, y.end) } }
    fun score(region: Region): Long = minOf(region.width, maxCardWidth).toLong() * region.height
    val best = pieces.maxOfOrNull(::score) ?: return area
    return pieces.filter { score(it) * 100 >= best * 98 }.maxWith(compareBy({ it.left }, { -it.top }))
}

/**
 * [range]에서 [bands]가 덮는 부분을 빼고 남은 구간 중 가장 긴 것. 길이가 같으면 앞의 것이다.
 * 띠가 다 덮어 남는 구간이 없으면 [range]를 그대로 돌려준다.
 */
fun widestFreeSpan(range: Span, bands: List<Span>): Span =
    subtract(range, bands).maxByOrNull { it.end - it.start } ?: range

/** [range]에서 [bands]가 덮는 부분을 뺀 나머지 구간들. 길이가 0인 구간은 뺀다. */
private fun subtract(range: Span, bands: List<Span>): List<Span> {
    var pieces = listOf(range).filter { it.end > it.start }
    for (band in bands) {
        pieces = pieces.flatMap { piece ->
            if (band.end <= piece.start || band.start >= piece.end) {
                listOf(piece)
            } else {
                listOf(Span(piece.start, band.start), Span(band.end, piece.end)).filter { it.end > it.start }
            }
        }
    }
    return pieces
}
