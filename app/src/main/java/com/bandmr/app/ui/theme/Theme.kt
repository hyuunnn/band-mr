package com.bandmr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEEE7),
    onPrimaryContainer = Color(0xFF164C40),
    secondary = Color(0xFF52645D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6ECE7),
    onSecondaryContainer = Color(0xFF354A40),
    tertiary = Color(0xFF916239),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E8D8),
    onTertiaryContainer = Color(0xFF624024),
    background = Color(0xFFF6F7F4),
    onBackground = Color(0xFF202924),
    surface = Color(0xFFFCFDFA),
    onSurface = Color(0xFF202924),
    surfaceVariant = Color(0xFFE8ECE6),
    onSurfaceVariant = Color(0xFF626D65),
    surfaceTint = Color(0xFF176B5B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F3EE),
    surfaceContainer = Color(0xFFEAEEE8),
    surfaceContainerHigh = Color(0xFFE4E9E2),
    surfaceContainerHighest = Color(0xFFDEE5DD),
    outline = Color(0xFF78847B),
    outlineVariant = Color(0xFFDFE5DD),
    error = Color(0xFFB43E3E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E5),
    onErrorContainer = Color(0xFF792D2D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF90D5BB),
    onPrimary = Color(0xFF103B30),
    primaryContainer = Color(0xFF23483D),
    onPrimaryContainer = Color(0xFFB3EBD4),
    secondary = Color(0xFFB5C9BE),
    onSecondary = Color(0xFF233B30),
    secondaryContainer = Color(0xFF30453A),
    onSecondaryContainer = Color(0xFFD5E9DE),
    tertiary = Color(0xFFE2BA91),
    onTertiary = Color(0xFF442B17),
    tertiaryContainer = Color(0xFF51402F),
    onTertiaryContainer = Color(0xFFF5DFC7),
    background = Color(0xFF111714),
    onBackground = Color(0xFFE5EBE4),
    surface = Color(0xFF1A221D),
    onSurface = Color(0xFFE5EBE4),
    surfaceVariant = Color(0xFF2D3930),
    onSurfaceVariant = Color(0xFFA7B3A9),
    surfaceTint = Color(0xFF90D5BB),
    surfaceContainerLowest = Color(0xFF0D120F),
    surfaceContainerLow = Color(0xFF19211C),
    surfaceContainer = Color(0xFF202A23),
    surfaceContainerHigh = Color(0xFF28332B),
    surfaceContainerHighest = Color(0xFF333F36),
    outline = Color(0xFF859488),
    outlineVariant = Color(0xFF323E35),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680F13),
    errorContainer = Color(0xFF512B2A),
    onErrorContainer = Color(0xFFFFDAD4),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

private val BandTypography = Typography(
    displaySmall = type(34, 44, FontWeight.Bold),
    headlineLarge = type(30, 40, FontWeight.Bold),
    headlineMedium = type(26, 36, FontWeight.Bold),
    headlineSmall = type(23, 32, FontWeight.SemiBold),
    titleLarge = type(20, 28, FontWeight.SemiBold),
    titleMedium = type(16, 24, FontWeight.SemiBold),
    titleSmall = type(14, 21, FontWeight.SemiBold),
    bodyLarge = type(16, 25),
    bodyMedium = type(14, 22),
    bodySmall = type(12, 19),
    labelLarge = type(14, 20, FontWeight.SemiBold),
    labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(11, 16, FontWeight.Medium),
)

@Composable
fun BandMrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = BandTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
