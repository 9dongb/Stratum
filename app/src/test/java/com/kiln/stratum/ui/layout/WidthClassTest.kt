package com.kiln.stratum.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidthClassTest {

    @Test
    fun boundariesAre600And840() {
        assertEquals(WidthClass.Compact, widthClassOf(599.9f))
        assertEquals(WidthClass.Medium, widthClassOf(600f))
        assertEquals(WidthClass.Medium, widthClassOf(839.9f))
        assertEquals(WidthClass.Expanded, widthClassOf(840f))
    }

    @Test
    fun fold8ScreensGetTheirLayouts() {
        // 커버 약 475dp, 메인 세로 약 700dp, 메인 가로 약 930dp(기획서 '대상 화면 사양')
        assertEquals(WidthClass.Compact, widthClassOf(475f))
        assertEquals(WidthClass.Medium, widthClassOf(700f))
        assertEquals(WidthClass.Expanded, widthClassOf(930f))
    }

    @Test
    fun theShortSideDecidesTheCoverLayout_soARotatedCoverStaysCover() {
        // 사용자 피드백 12차 3: 커버를 가로로 돌리면 커버 배치가 돌아간 형태(폴드8 커버 가로 약 751×475, 에뮬레이터 797×411·891×411)
        for ((width, height) in listOf(751f to 475f, 797f to 411f, 891f to 411f, 900f to 320f)) {
            assertEquals("$width×$height", WidthClass.Compact, layoutClassOf(width, height))
            assertTrue("$width×$height", isCoverLandscape(width, height))
        }
        // 커버·폰 세로는 그대로 커버 배치(돌아가지 않음)
        for ((width, height) in listOf(475f to 751f, 411f to 797f, 599f to 900f)) {
            assertEquals("$width×$height", WidthClass.Compact, layoutClassOf(width, height))
            assertFalse("$width×$height", isCoverLandscape(width, height))
        }
        // 짧은 쪽이 600dp 이상이면 폭으로 메인 세로·가로(폴드8 메인 약 704×933·933×704)
        assertEquals(WidthClass.Medium, layoutClassOf(704f, 933f))
        assertEquals(WidthClass.Expanded, layoutClassOf(933f, 704f))
        assertEquals(WidthClass.Medium, layoutClassOf(600f, 600f))
        assertFalse(isCoverLandscape(933f, 704f))
    }
}
