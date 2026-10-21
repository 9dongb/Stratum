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

    @Test
    fun splitAround_putsChildrenAtBothEndsWithoutBand() {
        assertEquals(0 to 306, splitAround(width = 600, childWidth = 294, band = null))
    }

    @Test
    fun splitAround_putsOneChildOnEachSideOfBand() {
        // 띠 280~320: 왼쪽 것은 띠 앞(280)에서 끝나고, 오른쪽 것은 띠 뒤(320)에서 시작한다
        assertEquals(0 to 320, splitAround(width = 600, childWidth = 280, band = Span(280, 320)))
    }

    @Test
    fun splitAround_keepsChildrenInsideRow() {
        // 띠가 오른쪽으로 치우쳐도 줄 밖으로 나가지 않는다
        assertEquals(170 to 320, splitAround(width = 600, childWidth = 280, band = Span(450, 490)))
    }

    @Test
    fun splitAround_ignoresBandOutsideRow() {
        assertEquals(0 to 320, splitAround(width = 600, childWidth = 280, band = Span(700, 740)))
    }

    // 창 고르기: 2000×1600 화면, 카드 최대 폭 600
    private val screen = Region(0, 0, 2000, 1600)

    @Test
    fun dialogRegion_usesWholeAreaWithoutHinge() {
        assertEquals(screen, dialogRegion(screen, emptyList(), emptyList(), maxCardWidth = 600))
    }

    @Test
    fun dialogRegion_prefersRightSideOfCenteredVerticalHinge() {
        // 띠 960~1040이 가운데를 지나 양쪽이 같다
        assertEquals(
            Region(1040, 0, 2000, 1600),
            dialogRegion(screen, listOf(Span(960, 1040)), emptyList(), maxCardWidth = 600)
        )
    }

    @Test
    fun dialogRegion_prefersTopSideOfCenteredHorizontalHinge() {
        assertEquals(
            Region(0, 0, 2000, 760),
            dialogRegion(screen, emptyList(), listOf(Span(760, 840)), maxCardWidth = 600)
        )
    }

    @Test
    fun dialogRegion_treatsNearlyEqualSidesAsEqual() {
        // 힌지가 가운데에서 몇 픽셀 비켜 있어 왼쪽(1066)이 오른쪽(1061)보다 조금 넓어도, 같다고 보고 오른쪽을 고른다
        val area = Region(0, 0, 2203, 1600)
        assertEquals(
            Region(1142, 0, 2203, 1600),
            dialogRegion(area, listOf(Span(1066, 1142)), emptyList(), maxCardWidth = 1440)
        )
    }

    @Test
    fun dialogRegion_picksSideWhereTheCardFits() {
        // 왼쪽이 300px뿐이면 카드가 들어가지 않으니 오른쪽, 오른쪽이 좁으면 왼쪽
        assertEquals(
            Region(380, 0, 2000, 1600),
            dialogRegion(screen, listOf(Span(300, 380)), emptyList(), maxCardWidth = 600)
        )
        assertEquals(
            Region(0, 0, 1620, 1600),
            dialogRegion(screen, listOf(Span(1620, 1700)), emptyList(), maxCardWidth = 600)
        )
    }

    @Test
    fun dialogRegion_scoresByCardWidthNotArea() {
        // 가로 띠 위쪽은 폭 2000·높이 300, 아래쪽은 높이 1220이다. 카드는 폭 600까지라 아래쪽이 낫다.
        assertEquals(
            Region(0, 380, 2000, 1600),
            dialogRegion(screen, emptyList(), listOf(Span(300, 380)), maxCardWidth = 600)
        )
    }

    @Test
    fun dialogRegion_avoidsKeyboardBelowHorizontalHinge() {
        // 키보드가 아래 700px를 가리면, 가로 힌지 아래 조각은 거의 남지 않아 위쪽을 고른다
        val aboveKeyboard = Region(0, 0, 2000, 900)
        assertEquals(
            Region(0, 0, 2000, 760),
            dialogRegion(aboveKeyboard, emptyList(), listOf(Span(760, 840)), maxCardWidth = 600)
        )
    }

    @Test
    fun dialogRegion_handlesTwoHinges() {
        // 세 번 접는 기기: 띠 둘로 세 조각. 폭이 같으면 가장 오른쪽
        assertEquals(
            Region(1360, 0, 2000, 1600),
            dialogRegion(screen, listOf(Span(600, 680), Span(1280, 1360)), emptyList(), maxCardWidth = 600)
        )
    }

    @Test
    fun dialogRegion_ignoresBandOutsideAndFallsBackWhenCovered() {
        assertEquals(screen, dialogRegion(screen, listOf(Span(2100, 2200)), emptyList(), maxCardWidth = 600))
        // 띠가 영역을 다 덮으면 영역 그대로(버튼을 둘 곳이 없다)
        assertEquals(screen, dialogRegion(screen, listOf(Span(-10, 2010)), emptyList(), maxCardWidth = 600))
    }

    @Test
    fun insetsAvoiding_shrinksTheSideNextToTheBand() {
        val band = Span(990, 1022)

        // 띠가 칸의 끝 쪽에 걸치면 끝을 줄이고, 시작 쪽에 걸치면 시작을 줄인다
        assertEquals(0 to 20, insetsAvoiding(Span(800, 1010), listOf(band)))
        assertEquals(12 to 0, insetsAvoiding(Span(1010, 1200), listOf(band)))
        // 겹치지 않으면 그대로
        assertEquals(0 to 0, insetsAvoiding(Span(1022, 1200), listOf(band)))
        assertEquals(0 to 0, insetsAvoiding(Span(800, 1010), emptyList()))
    }

    @Test
    fun pieceAt_findsThePieceHoldingThePoint() {
        val bands = listOf(Span(40, 60))
        assertEquals(Span(0, 40), pieceAt(Span(0, 100), bands, 10))
        assertEquals(Span(60, 100), pieceAt(Span(0, 100), bands, 70))
    }

    @Test
    fun pieceAt_onABand_takesThePieceAfterIt() {
        assertEquals(Span(60, 100), pieceAt(Span(0, 100), listOf(Span(40, 60)), 50))
        // 띠 뒤에 조각이 없으면 마지막 조각
        assertEquals(Span(0, 40), pieceAt(Span(0, 100), listOf(Span(40, 100)), 50))
    }

    @Test
    fun flyoutRegion_staysOnTheSideOfTheCell() {
        // 왼쪽 띠의 칸(0..72), 가운데 세로 힌지 띠(400..440)
        val region = flyoutRegion(
            anchor = Region(0, 100, 72, 156),
            area = Region(0, 0, 840, 700),
            verticalBands = listOf(Span(400, 440)),
            horizontalBands = emptyList(),
            gap = 4
        )
        assertEquals(Region(0, 0, 400, 700), region)
    }

    @Test
    fun flyoutRegion_usesTheRowOfTheCellAcrossAHorizontalHinge() {
        val area = Region(0, 0, 700, 840)
        val bands = listOf(Span(400, 440))
        assertEquals(Region(0, 0, 700, 400), flyoutRegion(Region(0, 300, 72, 356), area, emptyList(), bands, gap = 4))
        assertEquals(Region(0, 440, 700, 840), flyoutRegion(Region(0, 450, 72, 506), area, emptyList(), bands, gap = 4))
    }

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
    fun flyoutSides_areBesideTheCell_andInsideItsPiece() {
        val area = Region(0, 0, 1000, 600)
        // 띠 칸 200~256: 오른쪽은 260부터 끝까지, 왼쪽은 처음부터 196까지
        val (right, left) = flyoutSides(Region(200, 100, 256, 156), area, emptyList(), emptyList(), gap = 4)
        assertEquals(Region(260, 0, 1000, 600), right)
        assertEquals(Region(0, 0, 196, 600), left)
        // 세로 힌지(480~520)가 있으면 칸 쪽 조각까지만이다
        val (rightOfHinge, leftOfHinge) = flyoutSides(Region(600, 100, 656, 156), area, listOf(Span(480, 520)), emptyList(), gap = 4)
        assertEquals(Region(660, 0, 1000, 600), rightOfHinge)
        assertEquals(Region(520, 0, 596, 600), leftOfHinge)
    }

    @Test
    fun flyoutOnLeft_onlyWhenTheRightSideIsShort() {
        val area = Region(0, 0, 1000, 600)
        // 왼쪽 끝 띠: 오른쪽에 들어간다
        val nearLeft = Region(4, 100, 60, 156)
        flyoutSides(nearLeft, area, emptyList(), emptyList(), gap = 4).let { (right, left) ->
            assertEquals(false, flyoutOnLeft(240, nearLeft, right, left, gap = 4))
        }
        // 오른쪽 끝 띠: 오른쪽 자리가 모자라 왼쪽에 띄운다
        val nearRight = Region(900, 100, 956, 156)
        flyoutSides(nearRight, area, emptyList(), emptyList(), gap = 4).let { (right, left) ->
            assertEquals(true, flyoutOnLeft(240, nearRight, right, left, gap = 4))
        }
        // 세로 힌지 바로 왼쪽 띠: 오른쪽은 힌지 너머라 칸 옆이 아니므로, 왼쪽에 들어가면 왼쪽에 띄운다
        val nextToHinge = Region(420, 100, 476, 156)
        flyoutSides(nextToHinge, area, listOf(Span(478, 520)), emptyList(), gap = 4).let { (right, left) ->
            assertEquals(true, flyoutOnLeft(240, nextToHinge, right, left, gap = 4))
        }
    }

    @Test
    fun stripPiece_keepsTheStripOffTheHinges() {
        val area = Region(60, 60, 1000, 700)
        // 힌지가 없으면 영역 전체
        assertEquals(area, stripPiece(100, 100, 56, area, emptyList(), emptyList()))
        // 세로 힌지(500~540): 띠 가운데가 든 쪽 조각. 가운데가 선을 넘으면 너머 조각이다.
        assertEquals(Region(60, 60, 500, 700), stripPiece(440, 100, 56, area, listOf(Span(500, 540)), emptyList()))
        assertEquals(Region(540, 60, 1000, 700), stripPiece(530, 100, 56, area, listOf(Span(500, 540)), emptyList()))
        // 띠 가운데가 힌지 띠 위면 접히는 선(520)에서 가까운 쪽 조각이다
        assertEquals(Region(60, 60, 500, 700), stripPiece(482, 100, 56, area, listOf(Span(500, 540)), emptyList()))
        assertEquals(Region(540, 60, 1000, 700), stripPiece(496, 100, 56, area, listOf(Span(500, 540)), emptyList()))
        // 가로 힌지(380~420): 띠 위 끝이 든 쪽 조각
        assertEquals(Region(60, 60, 1000, 380), stripPiece(100, 300, 56, area, emptyList(), listOf(Span(380, 420))))
        assertEquals(Region(60, 420, 1000, 700), stripPiece(100, 450, 56, area, emptyList(), listOf(Span(380, 420))))
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
    fun panelRegion_isTheTopRightPiece() {
        val area = Region(100, 50, 900, 700)
        // 힌지가 없으면 영역 전체
        assertEquals(area, panelRegion(area, emptyList(), emptyList()))
        // 세로 힌지면 오른쪽 조각, 가로 힌지면 위 조각
        assertEquals(Region(440, 50, 900, 700), panelRegion(area, listOf(Span(400, 440)), emptyList()))
        assertEquals(Region(100, 50, 900, 380), panelRegion(area, emptyList(), listOf(Span(380, 420))))
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
