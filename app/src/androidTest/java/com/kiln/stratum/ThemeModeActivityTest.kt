package com.kiln.stratum

import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.WindowInsetsController
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kiln.stratum.settings.AppSettings
import com.kiln.stratum.settings.ThemeMode
import com.kiln.stratum.ui.theme.stratumColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 설정의 테마(결정 기록 '테마 고르기')를 바꾸면 앱 창에 바로 들어가는지 확인한다: 창 바탕색과 아래 제스처 막대 아이콘 색.
 * 앱의 실제 설정을 바꾸므로 끝나면 처음 값으로 되돌린다.
 */
@RunWith(AndroidJUnit4::class)
class ThemeModeActivityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val settings = AppSettings.get(instrumentation.targetContext)
    private lateinit var before: ThemeMode

    private fun setTheme(mode: ThemeMode) = instrumentation.runOnMainSync { settings.update { it.copy(themeMode = mode) } }

    @Before
    fun rememberTheme() {
        before = settings.values.value.themeMode
        setTheme(ThemeMode.DARK)
    }

    @After
    fun restoreTheme() = setTheme(before)

    private fun windowColor(scenario: ActivityScenario<MainActivity>): Int? {
        var color: Int? = null
        scenario.onActivity { color = (it.window.decorView.background as? ColorDrawable)?.color }
        return color
    }

    private fun lightBarIcons(scenario: ActivityScenario<MainActivity>): Boolean {
        var light = false
        scenario.onActivity {
            val appearance = it.window.insetsController?.systemBarsAppearance ?: 0
            light = appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0
        }
        return light
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
    }

    @Test
    fun light_paintsTheWindowLight_andDarkensTheBarIcons_thenDarkComesBack() {
        val dark = stratumColors(dark = true).background.toArgb()
        val light = stratumColors(dark = false).background.toArgb()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor { windowColor(scenario) == dark }
            assertEquals(dark, windowColor(scenario))
            assertFalse("dark theme should keep light bar icons", lightBarIcons(scenario))

            setTheme(ThemeMode.LIGHT)
            waitFor { windowColor(scenario) == light }

            assertEquals(light, windowColor(scenario))
            assertTrue("light theme should use dark bar icons", lightBarIcons(scenario))

            setTheme(ThemeMode.DARK)
            waitFor { windowColor(scenario) == dark }
            assertEquals(dark, windowColor(scenario))
        }
    }
}
