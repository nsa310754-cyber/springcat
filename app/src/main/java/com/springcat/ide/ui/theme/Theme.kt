package com.springcat.ide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SpringGreen = Color(0xFF7CF3B0)
private val DeepForest = Color(0xFF0F3D2E)
private val Amber = Color(0xFFF7C948)

private val DarkColors = darkColorScheme(
    primary = SpringGreen,
    onPrimary = Color(0xFF00281A),
    secondary = Amber,
    onSecondary = Color(0xFF2A2000),
    background = Color(0xFF0B1512),
    onBackground = Color(0xFFE6EDEA),
    surface = Color(0xFF121F1A),
    onSurface = Color(0xFFE6EDEA),
    surfaceVariant = Color(0xFF1B2C25),
    onSurfaceVariant = Color(0xFFB6C6BE),
)

private val LightColors = lightColorScheme(
    primary = DeepForest,
    onPrimary = Color.White,
    secondary = Color(0xFF8A6D00),
    background = Color(0xFFF6FAF7),
    onBackground = Color(0xFF10201A),
    surface = Color.White,
    onSurface = Color(0xFF10201A),
)

@Composable
fun SpringCatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
