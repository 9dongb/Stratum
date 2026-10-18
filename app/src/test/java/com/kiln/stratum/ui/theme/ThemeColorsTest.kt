package com.kiln.stratum.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 테마 색 토큰(결정 기록 '디자인(시안)')과 M3 색 역할 매핑, 글자 대비를 확인한다. */
class ThemeColorsTest {

    private val dark = stratumColors(dark = true)
    private val light = stratumColors(dark = false)

    @Test
    fun dark_isTheUsersMockupPalette() {
        assertEquals(Color(0xFF121315), dark.workspace)
        assertEquals(Color(0xFF1B1C1F), dark.background)
        assertEquals(Color(0xFF232428), dark.panel)
        assertEquals(Color(0xFF2E3036), dark.divider)
        assertEquals(Color(0xFF3A3C43), dark.fieldBorder)
        assertEquals(Color(0xFFE9EAEE), dark.textPrimary)
        assertEquals(Color(0xFFC9CCD3), dark.textSecondary)
        assertEquals(Color(0xFF9EA3AD), dark.textDescription)
        assertEquals(Color(0xFF6C707A), dark.textDisabled)
        assertEquals(Color(0xFF2F6FE4), dark.accent)
        assertEquals(Color(0xFF8DB6FF), dark.selectedContent)
        assertEquals(Color(0xFF2A3550), dark.selectedBackground)
        assertEquals(Color(0xFF6EA2FF), dark.sliderFill)
        assertEquals(Color(0xFF4C7BD9), dark.chipSelectedBorder)
        assertTrue(dark.isDark)
        assertTrue(!light.isDark)
    }

    @Test
    fun materialRoles_followTheTokens() {
        for (c in listOf(dark, light)) {
            val scheme = stratumColorScheme(c)
            assertEquals(c.accent, scheme.primary)
            assertEquals(c.onAccent, scheme.onPrimary)
            assertEquals(c.selectedBackground, scheme.secondaryContainer)
            assertEquals(c.selectedContent, scheme.onSecondaryContainer)
            assertEquals(c.background, scheme.background)
            assertEquals(c.background, scheme.surface)
            assertEquals(c.textPrimary, scheme.onSurface)
            assertEquals(c.workspace, scheme.surfaceContainerLowest)
            assertEquals(c.panel, scheme.surfaceContainerLow)
            assertEquals(c.panel, scheme.surfaceContainer)
            assertEquals(c.panel, scheme.surfaceContainerHigh)
            assertEquals(c.fieldBorder, scheme.outline)
            assertEquals(c.divider, scheme.outlineVariant)
            assertEquals(c.inverseBackground, scheme.inverseSurface)
        }
    }

    @Test
    fun everyMaterialRole_comesFromThePalette() {
        // 빠뜨린 역할이 있으면 M3 기본(보라 계열)이 남는다. 모든 역할이 토큰 색(또는 막의 검정)인지 본다.
        for (c in listOf(dark, light)) {
            val palette = c.allColors() + Color.Black
            for ((index, color) in stratumColorScheme(c).allRoles().withIndex()) {
                assertTrue("role $index = $color", color in palette)
            }
        }
    }

    @Test
    fun text_isReadableOnItsBackground() {
        for (c in listOf(dark, light)) {
            val name = if (c.isDark) "dark" else "light"
            for (surface in listOf(c.panel, c.background)) {
                assertContrast("$name primary", c.textPrimary, surface, 4.5)
                assertContrast("$name secondary", c.textSecondary, surface, 4.5)
                assertContrast("$name description", c.textDescription, surface, 4.5)
                assertContrast("$name text button", c.selectedContent, surface, 4.5)
            }
            assertContrast("$name selected", c.selectedContent, c.selectedBackground, 4.5)
            assertContrast("$name accent button", c.onAccent, c.accent, 4.5)
            assertContrast("$name toast", c.inverseText, c.inverseBackground, 4.5)
            assertContrast("$name error", c.error, c.panel, 4.5)
            // 캔버스 테두리는 작업 공간과 구별된다(글자가 아니라 선이라 낮은 기준)
            assertContrast("$name canvas outline", c.canvasOutline, c.workspace, 1.5)
        }
    }

    private fun assertContrast(what: String, foreground: Color, background: Color, minimum: Double) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05) / (darker + 0.05)
        assertTrue("$what contrast $ratio < $minimum", ratio >= minimum)
    }

    private fun StratumColors.allColors(): Set<Color> = setOf(
        workspace, background, panel, divider, fieldBorder, fieldBackground, textPrimary, textSecondary, textDescription,
        textDisabled, accent, onAccent, selectedContent, selectedBackground, chipSelectedBorder, sliderFill, tabIndicator,
        sliderTrack, sliderThumb, sliderThumbBorder, canvasOutline, inverseBackground, inverseText, inverseAccent, error,
        onError, errorContainer, onErrorContainer, scrim
    )

    private fun ColorScheme.allRoles(): List<Color> = listOf(
        primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary, secondary, onSecondary, secondaryContainer,
        onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface,
        onSurface, surfaceVariant, onSurfaceVariant, surfaceTint, inverseSurface, inverseOnSurface, error, onError,
        errorContainer, onErrorContainer, outline, outlineVariant, scrim, surfaceBright, surfaceDim, surfaceContainer,
        surfaceContainerHigh, surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest, primaryFixed,
        primaryFixedDim, onPrimaryFixed, onPrimaryFixedVariant, secondaryFixed, secondaryFixedDim, onSecondaryFixed,
        onSecondaryFixedVariant, tertiaryFixed, tertiaryFixedDim, onTertiaryFixed, onTertiaryFixedVariant
    )
}
