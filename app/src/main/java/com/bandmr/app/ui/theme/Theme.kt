package com.bandmr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandmr.app.data.AppDesign

val LocalAppDesign = staticCompositionLocalOf { AppDesign.MONO }

private val MonoScheme = lightColorScheme(
    primary = Color(0xFF202020), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E5), onPrimaryContainer = Color(0xFF202020),
    secondary = Color(0xFF525252), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDEA), onSecondaryContainer = Color(0xFF303030),
    tertiary = Color(0xFF626257), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0EFE6), onTertiaryContainer = Color(0xFF414137),
    background = Color(0xFFFAFAF8), onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFAFAF8), onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFECECE8), onSurfaceVariant = Color(0xFF64645F),
    surfaceTint = Color(0xFF202020),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F0EC),
    surfaceContainer = Color(0xFFEAEAE6), surfaceContainerHigh = Color(0xFFE4E4E0),
    surfaceContainerHighest = Color(0xFFDEDEDA),
    outline = Color(0xFF85857D), outlineVariant = Color(0xFFD9D9D2),
    error = Color(0xFFAF3131), onError = Color.White,
    errorContainer = Color(0xFFFFE8E3), onErrorContainer = Color(0xFF782A20),
)

private val AmpScheme = darkColorScheme(
    primary = Color(0xFFFFBE63), onPrimary = Color(0xFF251A0C),
    primaryContainer = Color(0xFF42301A), onPrimaryContainer = Color(0xFFFFD69E),
    secondary = Color(0xFFC9C3B9), onSecondary = Color(0xFF26231E),
    secondaryContainer = Color(0xFF35322D), onSecondaryContainer = Color(0xFFEAE3D8),
    tertiary = Color(0xFFE4A289), onTertiary = Color(0xFF47271A),
    tertiaryContainer = Color(0xFF4C3227), onTertiaryContainer = Color(0xFFFFD8C7),
    background = Color(0xFF141414), onBackground = Color(0xFFF3EFE8),
    surface = Color(0xFF1E1E1C), onSurface = Color(0xFFF3EFE8),
    surfaceVariant = Color(0xFF34332E), onSurfaceVariant = Color(0xFFB3AEA3),
    surfaceTint = Color(0xFFFFBE63),
    surfaceContainerLowest = Color(0xFF10100F), surfaceContainerLow = Color(0xFF242421),
    surfaceContainer = Color(0xFF2B2B26), surfaceContainerHigh = Color(0xFF32322C),
    surfaceContainerHighest = Color(0xFF3B3A34),
    outline = Color(0xFF8B8477), outlineVariant = Color(0xFF3D3B34),
    error = Color(0xFFFFB4A9), onError = Color(0xFF680F13),
    errorContainer = Color(0xFF512B2A), onErrorContainer = Color(0xFFFFDAD4),
)

private val BlueScheme = lightColorScheme(
    primary = Color(0xFF3157D5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E8FF), onPrimaryContainer = Color(0xFF263D89),
    secondary = Color(0xFF62658A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E8F8), onSecondaryContainer = Color(0xFF414466),
    tertiary = Color(0xFF89613D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDD8), onTertiaryContainer = Color(0xFF624122),
    background = Color(0xFFF1F4FC), onBackground = Color(0xFF202B43),
    surface = Color.White, onSurface = Color(0xFF202B43),
    surfaceVariant = Color(0xFFE5EAF6), onSurfaceVariant = Color(0xFF64708B),
    surfaceTint = Color(0xFF3157D5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEBEFFA),
    surfaceContainer = Color(0xFFE6EBF9), surfaceContainerHigh = Color(0xFFDDE5F5),
    surfaceContainerHighest = Color(0xFFD6E0F2),
    outline = Color(0xFF7A87A2), outlineVariant = Color(0xFFDAE2F1),
    error = Color(0xFFB43E3E), onError = Color.White,
    errorContainer = Color(0xFFFCE8E5), onErrorContainer = Color(0xFF792D2D),
)

fun designIsDark(design: AppDesign, systemDark: Boolean): Boolean = when (design) {
    AppDesign.AMP -> true
    AppDesign.STUDIO -> systemDark
    else -> false
}

fun designColors(design: AppDesign, systemDark: Boolean = false): ColorScheme = when (design) {
    AppDesign.MONO -> MonoScheme
    AppDesign.AMP -> AmpScheme
    AppDesign.BLUE -> BlueScheme
    AppDesign.STUDIO -> if (systemDark) DarkScheme else LightScheme
}

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
fun BandMrTheme(design: AppDesign = AppDesign.MONO, content: @Composable () -> Unit) {
    val corner = when (design) {
        AppDesign.MONO -> 4
        AppDesign.AMP -> 10
        AppDesign.BLUE -> 28
        AppDesign.STUDIO -> 24
    }
    val typography = when (design) {
        AppDesign.MONO -> BandTypography.copy(
            headlineLarge = type(36, 46, FontWeight.Bold).copy(letterSpacing = (-1).sp),
            labelSmall = type(11, 16, FontWeight.Medium).copy(letterSpacing = 1.sp),
        )
        AppDesign.AMP -> BandTypography.copy(
            headlineLarge = type(32, 42, FontWeight.Bold),
            labelSmall = type(11, 17, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace),
        )
        else -> BandTypography
    }
    CompositionLocalProvider(LocalAppDesign provides design) {
        MaterialTheme(
            colorScheme = designColors(design, isSystemInDarkTheme()),
            typography = typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape((corner / 3).dp),
                small = RoundedCornerShape((corner / 2).dp),
                medium = RoundedCornerShape((corner * 2 / 3).dp),
                large = RoundedCornerShape(corner.dp),
                extraLarge = RoundedCornerShape((corner + 4).dp),
            ),
            content = content,
        )
    }
}
