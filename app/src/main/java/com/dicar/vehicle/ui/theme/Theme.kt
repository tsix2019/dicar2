package com.dicar.vehicle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF3DA5FF)
private val AccentDark = Color(0xFF0A6CC2)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00213D),
    primaryContainer = Color(0xFF123A5C),
    onPrimaryContainer = Color(0xFFD2E6FF),
    secondary = Color(0xFF7FD6A4),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF0B0E12),
    surface = Color(0xFF0B0E12),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1E252E),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    primaryContainer = Color(0xFFD2E6FF),
    onPrimaryContainer = Color(0xFF00213D),
    secondary = Color(0xFF1E8A52),
    tertiary = Color(0xFFB2600A),
)

/** 跟随系统深浅色（DiLink 昼夜模式会切换系统 UI mode）。 */
@Composable
fun DiCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
