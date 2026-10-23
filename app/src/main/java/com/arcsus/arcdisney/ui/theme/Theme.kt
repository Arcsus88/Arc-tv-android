package com.arcsus.arcdisney.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The splash's night blue, which the poster wall keeps. */
object Night {
    val Deep = Color(0xFF060B22)
    val Sky = Color(0xFF0B1D5A)
    val Glow = Color(0xFF3B6BFF)
    val Card = Color(0xFF111A3D)
    val Field = Color(0x1AFFFFFF)
    val Line = Color(0x24FFFFFF)
    val Text = Color(0xFFF2F5FF)
    val Muted = Color(0xFF9AA6CC)
}

private val scheme = darkColorScheme(
    primary = Color(0xFF6C93FF),
    onPrimary = Color(0xFF020A2A),
    primaryContainer = Color(0xFF1C2E6E),
    onPrimaryContainer = Night.Text,
    secondary = Color(0xFFB9C7FF),
    background = Night.Deep,
    onBackground = Night.Text,
    surface = Color(0xFF0C1433),
    onSurface = Night.Text,
    surfaceVariant = Night.Card,
    onSurfaceVariant = Night.Muted,
    surfaceContainerHigh = Color(0xFF16204A),
    outline = Night.Line,
)

@Composable
fun ArcDisneyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
