package com.voiceledger.lite.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF245C53),
    onPrimary = Color(0xFFF7FAF8),
    secondary = Color(0xFFC86C35),
    onSecondary = Color(0xFFFFF7F2),
    background = Color(0xFFF5F1E8),
    surface = Color(0xFFFFFBF5),
    surfaceVariant = Color(0xFFE6DDD0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7BC1B3),
    secondary = Color(0xFFF19C68),
    background = Color(0xFF171C1A),
    surface = Color(0xFF202624),
    surfaceVariant = Color(0xFF33403C),
)

@Composable
fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
