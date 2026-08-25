package com.bandmr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Violet = Color(0xFF7C4DFF)
private val VioletDark = Color(0xFF4527A0)
private val Teal = Color(0xFF64FFDA)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    secondary = Teal,
    tertiary = Color(0xFFFFB59B),
    background = Color(0xFF101018),
    surface = Color(0xFF181822),
    surfaceVariant = Color(0xFF23232F),
)

private val LightScheme = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    secondary = VioletDark,
)

@Composable
fun BandMrTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
