package com.kiln.stratum.ui.layout

/** 한 축 위의 구간(픽셀). [start] 이상 [end] 미만이다. */
data class Span(val start: Int, val end: Int)

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
)

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

/** 크기 [width]×[height]인 것의 왼쪽 위를 ([x], [y])에 두되 [area] 안으로 민 자리. [area]보다 크면 왼쪽·위 끝이다. */
fun placeInside(x: Int, y: Int, width: Int, height: Int, area: Region): Pair<Int, Int> =
    x.coerceAtMost(area.right - width).coerceAtLeast(area.left) to y.coerceAtMost(area.bottom - height).coerceAtLeast(area.top)

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
 * 띠의 칸([anchor]의 위아래) 옆에 띄우는 높이 [height]인 것(세로 슬라이더 창)의 위 끝(창 좌표). 칸의 가운데에 가운데를 맞춘다.
 * 위쪽 자리가 모자라면 칸 위 끝에서 시작하고, 아래쪽 자리가 모자라면 칸 아래 끝에서 끝낸다(9차까지 도구 옵션 띠를 고른 도구 버튼 옆에 둘 때의
 * 규칙, 사용자 요청). 그래도 [area](띄울 수 있는 위아래 구간) 밖으로 나가면 안으로 민다. [area]보다 크면 그 위 끝이다.
 */
fun stripTop(anchor: Span, height: Int, area: Span): Int {
    val centered = (anchor.start + anchor.end) / 2 - height / 2
    val top = when {
        centered < area.start -> anchor.start
        centered + height > area.end -> anchor.end - height
        else -> centered
    }
    return top.coerceAtMost(area.end - height).coerceAtLeast(area.start)
}

/**
 * 도구 수치 바의 크기(픽셀, 사용자 피드백 12차: "펼친 화면 기준 도구 수치 조절을 오른쪽에 바에서 일괄 조절합니다"). [thickness]는 슬라이더 자리의
 * 굵기(누르는 영역), [minLength]~[maxLength]는 슬라이더 길이, [padding]은 카드 안쪽 여백, [gap]은 슬라이더 사이, [margin]은 카드와 캔버스 끝 사이다.
 */
data class SliderBarMetrics(val thickness: Int, val minLength: Int, val maxLength: Int, val padding: Int, val gap: Int, val margin: Int)

/** 도구 수치 바 카드([box])와 그 안 슬라이더 자리([slots], 위·왼쪽부터)(창 좌표) */
data class SliderBarLayout(val box: Region, val slots: List<Region>)

/**
 * 캔버스 오른쪽 가장자리의 세로 수치 바: [area](캔버스 영역) 오른쪽 끝에서 [SliderBarMetrics.margin] 안, 세로 가운데. 슬라이더 [count]개를 위부터
 * 쌓고, 길이는 영역 높이에 맞춰 [SliderBarMetrics.minLength]~[SliderBarMetrics.maxLength]다(캔버스가 낮으면 줄인다). 그래도 넘치면 위 끝부터 놓는다.
 */
fun sideSliderBar(count: Int, area: Region, m: SliderBarMetrics): SliderBarLayout {
    val room = area.height - 2 * m.margin - 2 * m.padding - (count - 1) * m.gap
    val length = (room / count.coerceAtLeast(1)).coerceAtMost(m.maxLength).coerceAtLeast(m.minLength)
    val width = m.thickness + 2 * m.padding
    val height = count * length + (count - 1).coerceAtLeast(0) * m.gap + 2 * m.padding
    val right = area.right - m.margin
    val top = (area.top + (area.height - height) / 2).coerceAtLeast(area.top + m.margin)
    val box = Region(right - width, top, right, top + height)
    val slots = List(count) { index ->
        val start = top + m.padding + index * (length + m.gap)
        Region(box.left + m.padding, start, box.right - m.padding, start + length)
    }
    return SliderBarLayout(box, slots)
}

