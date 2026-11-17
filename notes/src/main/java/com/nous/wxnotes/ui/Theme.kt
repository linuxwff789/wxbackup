package com.nous.wxnotes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1F7A4D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F1D3),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF4F6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8D7),
    onSecondaryContainer = Color(0xFF0D1F14),
    background = Color(0xFFF7FBF6),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFF7FBF6),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF414941),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F6F1),
    surfaceContainer = Color(0xFFEBF1EB),
    surfaceContainerHigh = Color(0xFFE5ECE5),
    surfaceContainerHighest = Color(0xFFDFE7DF),
    outline = Color(0xFF717A72),
    outlineVariant = Color(0xFFC1CAC1)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CD5B3),
    onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF0B5B39),
    onPrimaryContainer = Color(0xFFB8F1D3),
    secondary = Color(0xFFB6CCBA),
    onSecondary = Color(0xFF223527),
    secondaryContainer = Color(0xFF384B3E),
    onSecondaryContainer = Color(0xFFD2E8D7),
    background = Color(0xFF0E1210),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF0E1210),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF3F4942),
    onSurfaceVariant = Color(0xFFBFC9C1),
    surfaceContainerLowest = Color(0xFF090C0A),
    surfaceContainerLow = Color(0xFF161B18),
    surfaceContainer = Color(0xFF1A201C),
    surfaceContainerHigh = Color(0xFF242B26),
    surfaceContainerHighest = Color(0xFF2F3732),
    outline = Color(0xFF89938B),
    outlineVariant = Color(0xFF3F4942),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun WxNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        content = content
    )
}
