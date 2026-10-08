package com.qs.tallerxr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val QsColors = darkColorScheme(
    primary = Color(0xFF52C7F5),
    onPrimary = Color(0xFF001F2A),
    secondary = Color(0xFF9EC9DC),
    background = Color(0xFF071019),
    onBackground = Color(0xFFE4F1F7),
    surface = Color(0xFF0E1A24),
    onSurface = Color(0xFFE4F1F7),
    surfaceVariant = Color(0xFF172732),
    onSurfaceVariant = Color(0xFFB8CAD3),
    error = Color(0xFFFFB4AB),
)

@Composable
fun QsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = QsColors, content = content)
}
