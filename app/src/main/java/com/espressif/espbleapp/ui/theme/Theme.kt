package com.espressif.espbleapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E4F),
    secondary = Color(0xFF1B998B),
    tertiary = Color(0xFFE36414),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    secondary = Color(0xFF5CDBD3),
    tertiary = Color(0xFFFF9F1C),
)

@Composable
fun EspBleAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
