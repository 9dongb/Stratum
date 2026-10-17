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
}
