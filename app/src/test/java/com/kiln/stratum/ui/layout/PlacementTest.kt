package com.kiln.stratum.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/** 떠 있는 것(옵션 창·띠·배율 표시·작업 표시줄)의 자리 계산 */
class PlacementTest {

    @Test
    fun flyoutOffset_sitsRightOfTheCell_andIsPushedInside() {
        val region = Region(0, 0, 400, 400)
        // 칸 오른쪽에 붙고 위 끝을 맞춘다
        assertEquals(76 to 100, flyoutOffset(Region(0, 100, 72, 156), width = 200, height = 120, region = region, gap = 4))
        // 아래로 넘치면 올리고, 오른쪽으로 넘치면 왼쪽으로 민다
        assertEquals(76 to 280, flyoutOffset(Region(0, 350, 72, 400), width = 200, height = 120, region = region, gap = 4))
        assertEquals(100 to 0, flyoutOffset(Region(0, 0, 300, 56), width = 300, height = 120, region = region, gap = 4))
    }

    @Test
    fun flyoutSides_areBesideTheCell() {
        val area = Region(0, 0, 1000, 600)
        // 띠 칸 200~256: 오른쪽은 260부터 끝까지, 왼쪽은 처음부터 196까지(접히는 선은 보지 않는다)
        val (right, left) = flyoutSides(Region(200, 100, 256, 156), area, gap = 4)
        assertEquals(Region(260, 0, 1000, 600), right)
        assertEquals(Region(0, 0, 196, 600), left)
    }

    @Test
    fun flyoutOnLeft_onlyWhenTheRightSideIsShort() {
        val area = Region(0, 0, 1000, 600)
        // 왼쪽 끝 띠: 오른쪽에 들어간다
        val nearLeft = Region(4, 100, 60, 156)
        flyoutSides(nearLeft, area, gap = 4).let { (right, left) ->
            assertEquals(false, flyoutOnLeft(240, nearLeft, right, left, gap = 4))
        }
        // 오른쪽 끝 띠: 오른쪽 자리가 모자라 왼쪽에 띄운다
        val nearRight = Region(900, 100, 956, 156)
        flyoutSides(nearRight, area, gap = 4).let { (right, left) ->
            assertEquals(true, flyoutOnLeft(240, nearRight, right, left, gap = 4))
        }
    }

    @Test
    fun placeInside_pushesIntoTheArea() {
        val area = Region(60, 60, 500, 380)
        assertEquals(100 to 100, placeInside(100, 100, 56, 200, area))
        // 오른쪽·아래로 넘치면 안으로, 왼쪽·위로 넘치면 끝으로
        assertEquals(444 to 180, placeInside(470, 300, 56, 200, area))
        assertEquals(60 to 60, placeInside(0, 0, 56, 200, area))
        // 영역보다 크면 위 끝(안에서 밀어 봄)
        assertEquals(100 to 60, placeInside(100, 100, 56, 500, area))
    }

    @Test
    fun stripTop_centersOnTheCell() {
        // 위아래 자리가 넉넉하면 칸 가운데(300)에 창 가운데를 맞춘다
        assertEquals(200, stripTop(Span(276, 324), height = 200, area = Span(56, 700)))
    }

    @Test
    fun stripTop_startsAtTheCell_whenTheTopIsShort() {
        // 가운데에 맞추면 위로 넘치므로 칸 위 끝(80)에서 시작한다(영역 위 끝 56이 아니라)
        assertEquals(80, stripTop(Span(80, 128), height = 300, area = Span(56, 700)))
        // 칸에서 시작하면 아래로 넘칠 때는 넘치지 않게 올린다(80 + 630 > 700)
        assertEquals(70, stripTop(Span(80, 128), height = 630, area = Span(56, 700)))
    }

    @Test
    fun stripTop_endsAtTheCell_whenTheBottomIsShort() {
        // 가운데에 맞추면 아래로 넘치므로 칸 아래 끝(660)에서 끝난다
        assertEquals(360, stripTop(Span(612, 660), height = 300, area = Span(56, 700)))
    }

    @Test
    fun stripTop_staysInsideTheArea_whenTaller() {
        // 영역보다 크면(안에서 밀어 봄) 영역 위 끝이다
        assertEquals(56, stripTop(Span(300, 348), height = 800, area = Span(56, 700)))
        // 영역(440~800) 위 끝 가까이의 칸: 영역 위로 나가지 않는다
        assertEquals(452, stripTop(Span(452, 500), height = 300, area = Span(440, 800)))
    }

    @Test
    fun zoomChipSpot_staysAtTheCorner_whenNothingIsInTheWay() {
        assertEquals(70 to 60, zoomChipSpot(70, 60, width = 60, height = 32, avoid = null, gap = 8))
    }

    @Test
    fun zoomChipSpot_goesRightOfTheStrip() {
        // 띠(64~120, 60~400)가 구석에 있으면 그 오른쪽(120 + 8)
        val strip = Region(64, 60, 120, 400)
        assertEquals(128 to 60, zoomChipSpot(70, 60, width = 60, height = 32, avoid = strip, gap = 8))
        // 띠를 아래로 옮겨 두었으면(구석과 8 넘게 떨어짐) 구석 그대로다
        val lower = Region(64, 101, 120, 400)
        assertEquals(70 to 60, zoomChipSpot(70, 60, width = 60, height = 32, avoid = lower, gap = 8))
    }

    @Test
    fun actionBarStarts_centersTheRow_insideTheArea() {
        // 버튼 셋(60·80·60, 사이 4): 줄 폭 208, 가운데 500이면 396부터
        assertEquals(listOf(396, 460, 544), actionBarStarts(listOf(60, 80, 60), center = 500, area = Span(10, 990), gap = 4))
        // 가운데가 끝에 가까우면 영역 안으로 민다
        assertEquals(listOf(10, 74, 158), actionBarStarts(listOf(60, 80, 60), center = 50, area = Span(10, 990), gap = 4))
        // 영역보다 넓으면 왼쪽 끝부터
        assertEquals(listOf(10, 74, 138), actionBarStarts(List(3) { 60 }, center = 100, area = Span(10, 150), gap = 4))
    }

    /** 수치 바 크기: 자리 굵기 48, 길이 80~160, 안쪽 여백 4, 사이 8, 캔버스 끝과 8 */
    private val bar = SliderBarMetrics(thickness = 48, minLength = 80, maxLength = 160, padding = 4, gap = 8, margin = 8)

    @Test
    fun sideSliderBar_sitsOnTheRightEdge_centeredVertically_withSlotsFromTheTop() {
        // 사용자 피드백 12차: 캔버스 오른쪽 가장자리, 세로 가운데. 슬라이더는 위부터 크기·경도·불투명도 순서로 쌓는다
        val area = Region(100, 50, 1000, 750)
        val layout = sideSliderBar(3, area, bar)
        // 카드 폭 = 자리 48 + 안쪽 여백 4×2, 높이 = 160×3 + 8×2 + 4×2
        assertEquals(Region(1000 - 8 - 56, 50 + (700 - 504) / 2, 1000 - 8, 50 + (700 - 504) / 2 + 504), layout.box)
        assertEquals(3, layout.slots.size)
        val top = layout.box.top + 4
        assertEquals(Region(layout.box.left + 4, top, layout.box.right - 4, top + 160), layout.slots[0])
        assertEquals(Region(layout.box.left + 4, top + 168, layout.box.right - 4, top + 328), layout.slots[1])
        assertEquals(Region(layout.box.left + 4, top + 336, layout.box.right - 4, top + 496), layout.slots[2])
    }

    @Test
    fun sideSliderBar_shrinksTheSlidersOnALowCanvas_butNotBelowTheMinimum() {
        // 높이 400: 남는 자리 400 - 16 - 8 - 16 = 360 → 길이 120
        val low = sideSliderBar(3, Region(0, 0, 500, 400), bar)
        assertEquals(120, low.slots[0].height)
        assertEquals((400 - low.box.height) / 2, low.box.top)
        // 아주 낮으면 가장 짧은 길이로 두고 위 끝(여백 안)부터 놓는다
        val tiny = sideSliderBar(3, Region(0, 0, 500, 200), bar)
        assertEquals(80, tiny.slots[0].height)
        assertEquals(8, tiny.box.top)
        // 하나면 그 하나만 가운데에(가장 긴 길이)
        val one = sideSliderBar(1, Region(0, 0, 500, 800), bar)
        assertEquals(160, one.slots.single().height)
        assertEquals((800 - 168) / 2, one.box.top)
    }

    @Test
    fun bottomSliderBar_isAHorizontalRowCenteredOnTheScreen_aboveTheBottom() {
        // 사용자 피드백 12차 1-1: 패널을 열면 아래 가운데(선택 작업 표시줄 자리)로 옮겨 가로 한 줄
        val area = Region(0, 0, 1000, 700)
        val docked = bottomSliderBar(3, area, center = 520, bottomInset = 0, m = bar)
        // 카드 폭 = 160·3 + 8·2 + 4·2 = 504, 높이 = 48 + 8
        assertEquals(Region(520 - 252, 700 - 8 - 56, 520 + 252, 700 - 8), docked.box)
        // 왼쪽부터 크기·경도·불투명도, 가로로 길다
        assertEquals(listOf(272, 440, 608), docked.slots.map { it.left })
        for (slot in docked.slots) {
            assertEquals(160, slot.width)
            assertEquals(48, slot.height)
            assertEquals(docked.box.top + 4, slot.top)
        }
        // 선택 도구의 작업 표시줄이 있으면 그 위에 쌓는다
        assertEquals(700 - 60 - 8, bottomSliderBar(3, area, 520, bottomInset = 60, m = bar).box.bottom)
    }

    @Test
    fun bottomSliderBar_staysInsideAndShrinksOnANarrowCanvas() {
        // 가운데가 영역 끝에 가까우면 안으로 민다
        assertEquals(992 - 504, bottomSliderBar(3, Region(0, 0, 1000, 700), center = 990, bottomInset = 0, m = bar).box.left)
        // 폭 400: 남는 자리 400 - 16 - 8 - 16 = 360 → 길이 120
        val narrow = bottomSliderBar(3, Region(0, 0, 400, 700), center = 200, bottomInset = 0, m = bar)
        assertEquals(120, narrow.slots[0].width)
        assertEquals(8, narrow.box.left)
    }

    @Test
    fun lerp_movesEveryEdgeByTheFraction() {
        val area = Region(0, 0, 1000, 700)
        val side = sideSliderBar(3, area, bar)
        val docked = bottomSliderBar(3, area, 500, 0, bar)
        assertEquals(side, lerp(side, docked, 0f))
        assertEquals(docked, lerp(side, docked, 1f))
        val half = lerp(side, docked, 0.5f)
        assertEquals((side.box.left + docked.box.left) / 2, half.box.left)
        assertEquals((side.slots[2].bottom + docked.slots[2].bottom) / 2, half.slots[2].bottom)
    }
}
