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
    AppDesign.AMP, AppDesign.MIXDECK, AppDesign.AURORA -> true
    AppDesign.STUDIO, AppDesign.ALBUM -> systemDark
    else -> false
}

fun designColors(design: AppDesign, systemDark: Boolean = false): ColorScheme = when (design) {
    AppDesign.MONO -> MonoScheme
    AppDesign.AMP -> AmpScheme
    AppDesign.BLUE -> BlueScheme
    AppDesign.STUDIO -> if (systemDark) DarkScheme else LightScheme
    AppDesign.MIXDECK -> MixdeckScheme
    AppDesign.ALBUM -> if (systemDark) AlbumDarkScheme else AlbumScheme
    AppDesign.GRID -> GridScheme
    AppDesign.CONSOLE -> ConsoleScheme
    AppDesign.AURORA -> AuroraScheme
}

/** DAW 채널 스트립 컬러 — 믹스덱 디자인의 스템 아이콘·슬라이더에 쓰는 표시 전용 팔레트 */
val StemChannelColors = listOf(
    Color(0xFFFF5C8A), // 보컬
    Color(0xFFFF9A3D), // 드럼
    Color(0xFFA78BFA), // 베이스
    Color(0xFF4ADE80), // 기타
    Color(0xFF38BDF8), // 피아노
    Color(0xFFFACC15), // 그 외
)

private val MixdeckScheme = darkColorScheme(
    primary = Color(0xFFF4F4F7), onPrimary = Color(0xFF0B0B0F),
    primaryContainer = Color(0xFF26262E), onPrimaryContainer = Color(0xFFF4F4F7),
    secondary = Color(0xFFB9B9C4), onSecondary = Color(0xFF0B0B0F),
    secondaryContainer = Color(0xFF20202A), onSecondaryContainer = Color(0xFFDDDDE5),
    tertiary = Color(0xFFFF5C8A), onTertiary = Color(0xFF3D0A1D),
    tertiaryContainer = Color(0xFF3D1826), onTertiaryContainer = Color(0xFFFFB8CC),
    background = Color(0xFF0B0B0F), onBackground = Color(0xFFF4F4F7),
    surface = Color(0xFF16161C), onSurface = Color(0xFFF4F4F7),
    surfaceVariant = Color(0xFF20202A), onSurfaceVariant = Color(0xFF8B8B99),
    surfaceTint = Color(0xFFF4F4F7),
    surfaceContainerLowest = Color(0xFF0E0E13), surfaceContainerLow = Color(0xFF1A1A21),
    surfaceContainer = Color(0xFF20202A), surfaceContainerHigh = Color(0xFF26262F),
    surfaceContainerHighest = Color(0xFF2E2E38),
    outline = Color(0xFF55555F), outlineVariant = Color(0xFF26262E),
    error = Color(0xFFFF6B6B), onError = Color(0xFF3D0A0A),
    errorContainer = Color(0xFF471818), onErrorContainer = Color(0xFFFFD2D2),
)

private val AlbumScheme = lightColorScheme(
    primary = Color(0xFF3D2E26), onPrimary = Color(0xFFFBF3EC),
    primaryContainer = Color(0xFFEAD9CB), onPrimaryContainer = Color(0xFF3A2A20),
    secondary = Color(0xFF7A655A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E4DA), onSecondaryContainer = Color(0xFF43322A),
    tertiary = Color(0xFFC96F4A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5DCCE), onTertiaryContainer = Color(0xFF6E3A22),
    background = Color(0xFFF3ECE4), onBackground = Color(0xFF2C241F),
    surface = Color(0xFFFAF4EC), onSurface = Color(0xFF2C241F),
    surfaceVariant = Color(0xFFEAE0D5), onSurfaceVariant = Color(0xFF8A7B6F),
    surfaceTint = Color(0xFF3D2E26),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4EDE4),
    surfaceContainer = Color(0xFFEFE6DA), surfaceContainerHigh = Color(0xFFE9DED0),
    surfaceContainerHighest = Color(0xFFE2D5C5),
    outline = Color(0xFFAA9C8E), outlineVariant = Color(0xFFDDD0C1),
    error = Color(0xFFB43E3E), onError = Color.White,
    errorContainer = Color(0xFFFCE8E5), onErrorContainer = Color(0xFF792D2D),
)

private val AlbumDarkScheme = darkColorScheme(
    primary = Color(0xFFEACEB6), onPrimary = Color(0xFF3A2718),
    primaryContainer = Color(0xFF4A3A30), onPrimaryContainer = Color(0xFFF0DCC9),
    secondary = Color(0xFFC9B5A6), onSecondary = Color(0xFF2E241D),
    secondaryContainer = Color(0xFF3D322B), onSecondaryContainer = Color(0xFFE5D5C5),
    tertiary = Color(0xFFE3A383), onTertiary = Color(0xFF4A2413),
    tertiaryContainer = Color(0xFF4E3022), onTertiaryContainer = Color(0xFFF5D9C2),
    background = Color(0xFF171210), onBackground = Color(0xFFEFE6DC),
    surface = Color(0xFF221B17), onSurface = Color(0xFFEFE6DC),
    surfaceVariant = Color(0xFF352B24), onSurfaceVariant = Color(0xFFB3A292),
    surfaceTint = Color(0xFFEACEB6),
    surfaceContainerLowest = Color(0xFF120E0B), surfaceContainerLow = Color(0xFF251E19),
    surfaceContainer = Color(0xFF2B2320), surfaceContainerHigh = Color(0xFF322924),
    surfaceContainerHighest = Color(0xFF3C312A),
    outline = Color(0xFF7E7064), outlineVariant = Color(0xFF3A302A),
    error = Color(0xFFFFB4A9), onError = Color(0xFF680F13),
    errorContainer = Color(0xFF512B2A), onErrorContainer = Color(0xFFFFDAD4),
)

private val GridScheme = lightColorScheme(
    primary = Color(0xFFFF3B1E), onPrimary = Color(0xFFFFF3EE),
    primaryContainer = Color(0xFFF7DCD5), onPrimaryContainer = Color(0xFFC22610),
    secondary = Color(0xFF5A5750), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E3DA), onSecondaryContainer = Color(0xFF36342F),
    tertiary = Color(0xFF141410), onTertiary = Color(0xFFE7E6E0),
    tertiaryContainer = Color(0xFFDFDED5), onTertiaryContainer = Color(0xFF141410),
    background = Color(0xFFE7E6E0), onBackground = Color(0xFF141410),
    surface = Color(0xFFEEEDE7), onSurface = Color(0xFF141410),
    surfaceVariant = Color(0xFFE1E0D8), onSurfaceVariant = Color(0xFF75726A),
    surfaceTint = Color(0xFFFF3B1E),
    surfaceContainerLowest = Color(0xFFF4F3EC), surfaceContainerLow = Color(0xFFE9E8E0),
    surfaceContainer = Color(0xFFE3E2D9), surfaceContainerHigh = Color(0xFFDCDAD0),
    surfaceContainerHighest = Color(0xFFD4D2C7),
    outline = Color(0xFFA9A79A), outlineVariant = Color(0xFFC9C7BB),
    error = Color(0xFFC22610), onError = Color.White,
    errorContainer = Color(0xFFF7DCD5), onErrorContainer = Color(0xFF8A1A0A),
)

private val ConsoleScheme = lightColorScheme(
    primary = Color(0xFFB3492E), onPrimary = Color(0xFFFBF3E6),
    primaryContainer = Color(0xFFEFDCC8), onPrimaryContainer = Color(0xFF7A3018),
    secondary = Color(0xFF8C6D3F), onSecondary = Color(0xFFFBF3E6),
    secondaryContainer = Color(0xFFEBDFC5), onSecondaryContainer = Color(0xFF4D3A1D),
    tertiary = Color(0xFF5E5B40), onTertiary = Color(0xFFFBF3E6),
    tertiaryContainer = Color(0xFFE8E2C9), onTertiaryContainer = Color(0xFF3E3C26),
    background = Color(0xFFE9E1D0), onBackground = Color(0xFF2B241B),
    surface = Color(0xFFF4EEDF), onSurface = Color(0xFF2B241B),
    surfaceVariant = Color(0xFFE3D9C3), onSurfaceVariant = Color(0xFF8C7F68),
    surfaceTint = Color(0xFFB3492E),
    surfaceContainerLowest = Color(0xFFFAF5E9), surfaceContainerLow = Color(0xFFEFE7D5),
    surfaceContainer = Color(0xFFEAE0CB), surfaceContainerHigh = Color(0xFFE4D8BF),
    surfaceContainerHighest = Color(0xFFDCCFB2),
    outline = Color(0xFFB3A78C), outlineVariant = Color(0xFFD4C9AE),
    error = Color(0xFFAF3131), onError = Color.White,
    errorContainer = Color(0xFFFFE8E3), onErrorContainer = Color(0xFF782A20),
)

private val AuroraScheme = darkColorScheme(
    primary = Color(0xFF6EE7D8), onPrimary = Color(0xFF052E28),
    primaryContainer = Color(0xFF143F3A), onPrimaryContainer = Color(0xFF6EE7D8),
    secondary = Color(0xFFA8ADEB), onSecondary = Color(0xFF1B1F4A),
    secondaryContainer = Color(0xFF262B55), onSecondaryContainer = Color(0xFFD3D6FF),
    tertiary = Color(0xFFC4A5F5), onTertiary = Color(0xFF2E1A55),
    tertiaryContainer = Color(0xFF352C60), onTertiaryContainer = Color(0xFFE2D3FF),
    background = Color(0xFF0B0E1E), onBackground = Color(0xFFEDEFF5),
    surface = Color(0xFF161B36), onSurface = Color(0xFFEDEFF5),
    surfaceVariant = Color(0xFF1E2440), onSurfaceVariant = Color(0xFF8F93B5),
    surfaceTint = Color(0xFF6EE7D8),
    surfaceContainerLowest = Color(0xFF0E1228), surfaceContainerLow = Color(0xFF1A1F3C),
    surfaceContainer = Color(0xFF212747), surfaceContainerHigh = Color(0xFF272E52),
    surfaceContainerHighest = Color(0xFF30385E),
    outline = Color(0xFF3A4066), outlineVariant = Color(0xFF252B4A),
    error = Color(0xFFFF8BA7), onError = Color(0xFF4A0E26),
    errorContainer = Color(0xFF45203A), onErrorContainer = Color(0xFFFFC4D4),
)

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
        AppDesign.MIXDECK -> 12
        AppDesign.ALBUM -> 24
        AppDesign.GRID -> 4
        AppDesign.CONSOLE -> 12
        AppDesign.AURORA -> 20
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
        AppDesign.MIXDECK -> BandTypography.copy(
            labelSmall = type(11, 17, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace),
            labelMedium = type(12, 18, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace),
        )
        AppDesign.GRID -> BandTypography.copy(
            headlineLarge = type(30, 38, FontWeight.Bold).copy(fontFamily = FontFamily.Monospace, letterSpacing = (-1).sp),
            labelSmall = type(11, 16, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp),
            labelMedium = type(12, 18, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace),
        )
        AppDesign.CONSOLE -> BandTypography.copy(
            headlineLarge = type(30, 40, FontWeight.Bold).copy(fontFamily = FontFamily.Serif),
            headlineMedium = type(25, 34, FontWeight.Bold).copy(fontFamily = FontFamily.Serif),
            labelSmall = type(10, 16, FontWeight.Bold).copy(letterSpacing = 2.sp),
        )
        AppDesign.AURORA -> BandTypography.copy(
            labelSmall = type(11, 16, FontWeight.Medium).copy(letterSpacing = 2.sp),
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
