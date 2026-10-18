package com.kiln.stratum.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 설정의 테마(결정 기록 '테마 고르기')가 다크로 그릴지 정하는 것을 확인한다. */
class ThemeModeTest {

    @Test
    fun darkAndLight_ignoreThePhoneSetting() {
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = false))
    }

    @Test
    fun system_followsThePhone() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun theDefault_isDark() {
        assertEquals(ThemeMode.DARK, SettingsValues().themeMode)
    }
}
