package com.kiln.stratum.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class HingePlacementTest {

    @Test
    fun band_widensAcrossTheHinge() {
        // 폭이 0인 세로 선(완전히 펼친 폴더블)
        val vertical = Hinge(left = 1080, top = 0, right = 1080, bottom = 1840, isVertical = true)
        val horizontal = Hinge(left = 0, top = 1104, right = 1840, bottom = 1104, isVertical = false)

        assertEquals(Span(1038, 1122), vertical.band(marginPx = 42))
        assertEquals(Span(1062, 1146), horizontal.band(marginPx = 42))
    }

    @Test
    fun stackAvoiding_stacksInOrderWithoutBands() {
        assertEquals(listOf(0, 58, 116), stackAvoiding(listOf(48, 48, 48), gap = 10, bands = emptyList()))
    }

    @Test
    fun stackAvoiding_movesItemPastBandAndKeepsStackingAfterIt() {
        // 두 번째 것(58~106)이 띠(80~120)에 걸려 띠 뒤(120)로 가고, 세 번째는 그 뒤에 쌓인다
        assertEquals(
            listOf(0, 120, 178),
            stackAvoiding(listOf(48, 48, 48), gap = 10, bands = listOf(Span(80, 120)))
        )
    }

    @Test
    fun stackAvoiding_movesFirstItemWhenBandIsAtStart() {
        assertEquals(listOf(30, 88), stackAvoiding(listOf(48, 48), gap = 10, bands = listOf(Span(-10, 30))))
    }

    @Test
    fun stackAvoiding_ignoresBandThatOnlyTouches() {
        // 띠가 첫 번째 것의 끝(48)에서 시작하면 겹치지 않는다
        assertEquals(listOf(0, 58), stackAvoiding(listOf(48, 48), gap = 10, bands = listOf(Span(48, 58))))
    }

    @Test
    fun stackAvoiding_checksAgainAfterMovingIntoAnotherBand() {
        // 첫 띠 뒤(50)로 옮기면 둘째 띠(60~90)에 걸려 다시 그 뒤(90)로 간다
        assertEquals(
            listOf(90),
            stackAvoiding(listOf(48), gap = 10, bands = listOf(Span(60, 90), Span(0, 50)))
        )
    }

    // 창 고르기: 2000×1600 화면, 카드 최대 폭 600
    private val screen = Region(0, 0, 2000, 1600)

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
    fun placeInside_pushesIntoThePiece() {
        val piece = Region(60, 60, 500, 380)
        assertEquals(100 to 100, placeInside(100, 100, 56, 200, piece))
        // 오른쪽·아래로 넘치면 안으로, 왼쪽·위로 넘치면 끝으로
        assertEquals(444 to 180, placeInside(470, 300, 56, 200, piece))
        assertEquals(60 to 60, placeInside(0, 0, 56, 200, piece))
        // 조각보다 크면 위 끝(안에서 밀어 봄)
        assertEquals(100 to 60, placeInside(100, 100, 56, 500, piece))
    }

    @Test
    fun stripTop_centersOnTheTool() {
        // 위아래 자리가 넉넉하면 도구 버튼 가운데(300)에 띠 가운데를 맞춘다
        assertEquals(200, stripTop(Span(276, 324), height = 200, piece = Span(56, 700)))
    }

    @Test
    fun stripTop_startsAtTheTool_whenTheTopIsShort() {
        // 가운데에 맞추면 위로 넘치므로 버튼 위 끝(80)에서 시작한다(영역 위 끝 56이 아니라)
        assertEquals(80, stripTop(Span(80, 128), height = 300, piece = Span(56, 700)))
        // 버튼에서 시작하면 아래로 넘칠 때는 넘치지 않게 올린다(80 + 630 > 700)
        assertEquals(70, stripTop(Span(80, 128), height = 630, piece = Span(56, 700)))
    }

    @Test
    fun stripTop_endsAtTheTool_whenTheBottomIsShort() {
        // 가운데에 맞추면 아래로 넘치므로 버튼 아래 끝(660)에서 끝난다
        assertEquals(360, stripTop(Span(612, 660), height = 300, piece = Span(56, 700)))
    }

    @Test
    fun stripTop_staysInsideThePiece_whenTaller() {
        // 띠가 조각보다 크면(안에서 밀어 봄) 조각 위 끝이다
        assertEquals(56, stripTop(Span(300, 348), height = 800, piece = Span(56, 700)))
        // 가로 힌지 아래 조각(440~800)에 있는 도구: 조각 위로 나가지 않는다
        assertEquals(452, stripTop(Span(452, 500), height = 300, piece = Span(440, 800)))
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
}
