package com.kiln.stratum.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 앱 테마. [darkTheme]이면 다크(기본), 아니면 라이트 색을 쓴다.
 * - 고정 색만 쓴다. 배경화면 색을 따라가는 다이내믹 컬러는 쓰지 않는다.
 * - 표면에 주 색을 섞는 효과(tonal elevation)를 끈다. 패널 색이 높이에 따라 바뀌지 않는다.
 * - 색은 [StratumTheme.colors], 모서리는 [StratumShapes], 크기는 [StratumDimens], 글자는 [MaterialTheme.typography]로 읽는다.
 */
@Composable
fun StratumTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = stratumColors(darkTheme)
    val scheme = remember(colors) { stratumColorScheme(colors) }
    CompositionLocalProvider(
        LocalStratumColors provides colors,
        LocalTonalElevationEnabled provides false
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = StratumMaterialShapes,
            typography = StratumTypography,
            content = content
        )
    }
}

/** 테마 토큰을 읽는 곳(MaterialTheme와 같은 방식) */
object StratumTheme {
    /** 지금 테마의 색 토큰 */
    val colors: StratumColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStratumColors.current
}

internal val LocalStratumColors = staticCompositionLocalOf { DarkStratumColors }

/**
 * M3 부품이 읽는 색 역할을 모두 토큰으로 정한다. 하나라도 빠뜨리면 그 역할은 M3 기본(보라 계열)이 남는다.
 * 선택(칩·분할 버튼·탭 표시)은 선택 배경·글자, 패널 계열 표면은 패널, 가장 낮은 표면은 작업 공간이다.
 */
internal fun stratumColorScheme(c: StratumColors): ColorScheme = ColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.selectedBackground,
    onPrimaryContainer = c.selectedContent,
    inversePrimary = c.inverseAccent,
    secondary = c.selectedContent,
    onSecondary = c.background,
    secondaryContainer = c.selectedBackground,
    onSecondaryContainer = c.selectedContent,
    tertiary = c.adjustAccent,
    onTertiary = c.background,
    tertiaryContainer = c.selectedBackground,
    onTertiaryContainer = c.selectedContent,
    background = c.background,
    onBackground = c.textPrimary,
    surface = c.background,
    onSurface = c.textPrimary,
    surfaceVariant = c.panel,
    onSurfaceVariant = c.textSecondary,
    surfaceTint = c.background,
    inverseSurface = c.inverseBackground,
    inverseOnSurface = c.inverseText,
    error = c.error,
    onError = c.onError,
    errorContainer = c.errorContainer,
    onErrorContainer = c.onErrorContainer,
    outline = c.fieldBorder,
    outlineVariant = c.divider,
    // M3 부품(아래 시트 등)은 스스로 투명도를 곱하므로 막 색은 불투명한 검정이다
    scrim = Color.Black,
    surfaceBright = c.panel,
    surfaceDim = c.workspace,
    surfaceContainer = c.panel,
    surfaceContainerHigh = c.panel,
    surfaceContainerHighest = c.divider,
    surfaceContainerLow = c.panel,
    surfaceContainerLowest = c.workspace,
    primaryFixed = c.accent,
    primaryFixedDim = c.accent,
    onPrimaryFixed = c.onAccent,
    onPrimaryFixedVariant = c.onAccent,
    secondaryFixed = c.selectedBackground,
    secondaryFixedDim = c.selectedBackground,
    onSecondaryFixed = c.selectedContent,
    onSecondaryFixedVariant = c.selectedContent,
    tertiaryFixed = c.selectedBackground,
    tertiaryFixedDim = c.selectedBackground,
    onTertiaryFixed = c.selectedContent,
    onTertiaryFixedVariant = c.selectedContent
)
