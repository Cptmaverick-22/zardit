package io.github.cptmaverick_22.zardit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.cptmaverick_22.zardit.downloader.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = ZarditRed,
    onPrimary = Color.White,
    primaryContainer = ZarditRedContainerDark,
    onPrimaryContainer = Color(0xFFFFDADC),
    secondary = Color(0xFF8B95A8),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = ZarditRedDark,
    onPrimary = Color.White,
    primaryContainer = ZarditRedContainerLight,
    onPrimaryContainer = Color(0xFF410008),
    secondary = Color(0xFF4B5563),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
)

@Composable
fun ZarditTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
