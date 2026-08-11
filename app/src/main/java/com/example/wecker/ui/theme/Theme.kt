package com.example.wecker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Purple40,
    onPrimary = NightText,
    secondary = PurpleGrey40,
    onSecondary = NightText,
    tertiary = Pink40,
    onTertiary = NightText,
    background = NightBlack,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightMutedText,
    error = NightError,
    onError = NightText
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    onPrimary = NightText,
    secondary = PurpleGrey40,
    onSecondary = NightText,
    tertiary = Pink40,
    onTertiary = NightText,
    background = NightBlack,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightMutedText,
    error = NightError,
    onError = NightText
)

@Composable
fun WeckerTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Feste Farben verhindern OEM-/Wallpaper-bedingte Abweichungen im Release.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}