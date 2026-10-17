package com.kiln.stratum.ui.layout

import org.junit.Assert.assertEquals
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
}
