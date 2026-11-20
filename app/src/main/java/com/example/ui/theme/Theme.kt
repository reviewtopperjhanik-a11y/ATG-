package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.model.ThemeMode

@Immutable
data class AgtSemanticColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val terminalBackground: Color,
    val terminalForeground: Color,
)

private val LightSemanticColors = AgtSemanticColors(
    success = PorcelainSuccess,
    successContainer = PorcelainSuccessContainer,
    warning = PorcelainWarning,
    warningContainer = PorcelainWarningContainer,
    terminalBackground = Color(0xFF18181B),
    terminalForeground = Color(0xFFE4E4E7),
)

private val DarkSemanticColors = AgtSemanticColors(
    success = DarkSuccess,
    successContainer = DarkSuccessContainer,
    warning = DarkWarning,
    warningContainer = DarkWarningContainer,
    terminalBackground = Color(0xFF0C0C0E),
    terminalForeground = Color(0xFFE4E4E7),
)

val LocalAgtColors = staticCompositionLocalOf { LightSemanticColors }

private val AgtLightColorScheme = lightColorScheme(
    primary = PorcelainPrimary,
    onPrimary = Color.White,
    primaryContainer = PorcelainPrimaryContainer,
    onPrimaryContainer = PorcelainOnPrimaryContainer,
    secondary = PorcelainSecondary,
    onSecondary = Color.White,
    secondaryContainer = PorcelainPrimaryContainer,
    onSecondaryContainer = PorcelainOnPrimaryContainer,
    background = PorcelainBackground,
    onBackground = PorcelainOnBackground,
    surface = PorcelainSurface,
    onSurface = PorcelainOnBackground,
    surfaceVariant = PorcelainSurfaceVariant,
    onSurfaceVariant = PorcelainOnSurfaceVariant,
    surfaceContainerLowest = PorcelainSurface,
    surfaceContainerLow = PorcelainBackground,
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = PorcelainSurfaceVariant,
    outline = PorcelainOutline,
    outlineVariant = PorcelainOutline,
    error = PorcelainError,
    onError = Color.White,
    errorContainer = PorcelainErrorContainer,
    onErrorContainer = Color(0xFF991B1B),
)

private val AgtDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = Color.White,
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = DarkError,
    onError = Color.White,
    errorContainer = DarkErrorContainer,
    onErrorContainer = Color(0xFFFCA5A5),
)

private val AgtShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun AgtTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDarkTheme) AgtDarkColorScheme else AgtLightColorScheme
    val semanticColors = if (useDarkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalAgtColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AgtTypography,
            shapes = AgtShapes,
            content = content,
        )
    }
}
