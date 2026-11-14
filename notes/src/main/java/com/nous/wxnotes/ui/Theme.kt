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
    secondaryContainer = Color(0xFFD2E8D7),
    background = Color(0xFFF7FBF6),
    surface = Color(0xFFF7FBF6),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurface = Color(0xFF191C19),
    onSurfaceVariant = Color(0xFF414941)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CD5B3),
    onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF005231),
    onPrimaryContainer = Color(0xFFB8F1D3),
    secondary = Color(0xFFB6CCBA),
    secondaryContainer = Color(0xFF384B3E),
    background = Color(0xFF111411),
    surface = Color(0xFF111411),
    surfaceVariant = Color(0xFF414941),
    onSurface = Color(0xFFE1E3DE),
    onSurfaceVariant = Color(0xFFC1C9C1)
)

@Composable
fun WxNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        content = content
    )
}
