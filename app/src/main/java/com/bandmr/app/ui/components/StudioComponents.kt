package com.bandmr.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandmr.app.data.AppDesign
import com.bandmr.app.ui.theme.LocalAppDesign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val StudioCovers = listOf(
    Color(0xFF264E43) to Color(0xFFC3DDC3),
    Color(0xFF47556A) to Color(0xFFCFDCEB),
    Color(0xFF765644) to Color(0xFFF1D9AF),
    Color(0xFF635A70) to Color(0xFFE3D9EF),
)
private val MeterHeights = intArrayOf(3, 6, 8, 5, 4)
private val MonoMarkHeights = floatArrayOf(0.24f, 0.42f, 0.58f, 0.38f, 0.20f)

/** 앨범 디자인의 시드별 커버 팔레트 (밝은 톤 → 어두운 톤) */
private val AlbumCovers = listOf(
    Color(0xFFF2B183) to Color(0xFF9C4A2E),
    Color(0xFFA9B8D9) to Color(0xFF42557F),
    Color(0xFFC4A8E0) to Color(0xFF59427E),
    Color(0xFFA5CBA9) to Color(0xFF3E6B4F),
)
private val AuroraBlobs = listOf(
    Color(0xFF5E6BFA) to Color(0xFF6EE7D8),
    Color(0xFF8E5CFA) to Color(0xFF5CE8C8),
    Color(0xFF4F9CFA) to Color(0xFF8FD9C0),
    Color(0xFF6B4FFA) to Color(0xFF5CC8E8),
)
private val VuNeedleDegrees = floatArrayOf(-62f, -30f, 8f, 44f)

/** Shared flat panel: spacing and outlines stay consistent across all three screens. */
@Composable
fun StudioPanel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val design = LocalAppDesign.current
    if (design == AppDesign.MONO) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
        return
    }
    val openCard = design == AppDesign.BLUE || design == AppDesign.SNOW || design == AppDesign.MOSS || design == AppDesign.ALBUM
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = when (design) {
            AppDesign.SNOW -> 2.dp
            AppDesign.ALBUM -> 3.dp
            else -> 0.dp
        },
        border = when {
            design == AppDesign.AURORA -> BorderStroke(
                1.dp,
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                else Color.White.copy(alpha = 0.10f),
            )
            openCard && !highlighted -> null
            else -> BorderStroke(
                1.dp,
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant,
            )
        },
    ) {
        Column(
            Modifier.padding(if (design == AppDesign.AMP) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * Screen-level backdrop painted under the scrollable content.
 * AURORA gets light blooms, ALBUM gets a cover-tinted wash.
 */
@Composable
fun DesignBackdrop(seed: Long? = null) {
    when (LocalAppDesign.current) {
        AppDesign.AURORA -> Canvas(Modifier.fillMaxSize()) {
            val blobs = listOf(
                Triple(Color(0xFF4F46E5), Offset(size.width * 0.15f, size.height * 0.08f), size.width * 0.55f),
                Triple(Color(0xFF0E7490), Offset(size.width * 0.95f, size.height * 0.42f), size.width * 0.5f),
                Triple(Color(0xFF7C3AED), Offset(size.width * 0.3f, size.height * 0.85f), size.width * 0.6f),
            )
            blobs.forEach { (color, center, radius) ->
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = 0.42f), Color.Transparent), center = center, radius = radius),
                    radius = radius, center = center,
                )
            }
        }
        AppDesign.ALBUM -> {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
            val (bright, deep) = AlbumCovers[Math.floorMod(seed ?: 0L, 4L).toInt()]
            Canvas(Modifier.fillMaxSize()) {
                val top = (if (dark) deep else bright).copy(alpha = if (dark) 0.4f else 0.45f)
                drawRect(
                    Brush.verticalGradient(
                        listOf(top, top.copy(alpha = 0f)),
                        endY = size.height * 0.6f,
                    ),
                )
            }
        }
        else -> Unit
    }
}

@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, active: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(26.dp)) {
        val heights = floatArrayOf(0.28f, 0.63f, 1f, 0.63f, 0.28f)
        heights.forEachIndexed { i, height ->
            val x = size.width * (i + 0.5f) / heights.size
            val half = size.height * height * 0.42f
            drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half), 2.7.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Decorative record sleeves, not a representation of the song's actual waveform. */
@Composable
fun TrackArtwork(seed: Long, modifier: Modifier = Modifier, size: Dp = 56.dp, label: String? = null) {
    val design = LocalAppDesign.current
    val variant = Math.floorMod(seed, 4L).toInt()
    val background = when (design) {
        AppDesign.MONO -> if (variant % 2 == 0) Color(0xFF242424) else Color(0xFFE4E3DD)
        AppDesign.AMP -> Color(0xFF292720)
        AppDesign.BLUE -> when (variant) {
            0 -> Color(0xFF4769D4)
            1 -> Color(0xFF9B9AE2)
            2 -> Color(0xFFE8B592)
            else -> Color(0xFF81AAB6)
        }
        AppDesign.STUDIO -> StudioCovers[variant].first
        AppDesign.SNOW -> when (variant) {
            1 -> Color(0xFFE6E6E8)
            3 -> Color(0xFFCFCFD2)
            else -> Color(0xFF161616)
        }
        AppDesign.INK -> Color(0xFF2A2D36)
        AppDesign.MOSS -> when (variant) {
            1 -> Color(0xFFC5D9C4)
            3 -> Color(0xFF8FB196)
            else -> Color(0xFF145C38)
        }
        AppDesign.ALBUM -> AlbumCovers[variant].first
        AppDesign.CONSOLE -> Color(0xFFF9F0DC)
        AppDesign.AURORA -> Color(0xFF12162E)
    }
    val ink = when (design) {
        AppDesign.MONO -> if (variant % 2 == 0) Color(0xFFECEBE6) else Color(0xFF30302D)
        AppDesign.AMP -> Color(0xFFFFBE63)
        AppDesign.BLUE -> Color(0xFFFFF9EC)
        AppDesign.STUDIO -> StudioCovers[variant].second
        AppDesign.SNOW -> if (variant == 1 || variant == 3) Color(0xFF161616) else Color.White
        AppDesign.INK -> Color(0xFFF3F0E8)
        AppDesign.MOSS -> if (variant == 1 || variant == 3) Color(0xFF142117) else Color(0xFFF4FFF8)
        AppDesign.ALBUM -> Color(0xFFFDF7EE)
        AppDesign.CONSOLE -> Color(0xFF5C4B33)
        AppDesign.AURORA -> Color(0xFFEDEFF5)
    }
    if (design == AppDesign.SNOW || design == AppDesign.INK || design == AppDesign.MOSS) {
        val mark = label?.trim()?.firstOrNull()?.toString()
        Box(
            modifier.size(size).clip(MaterialTheme.shapes.medium).background(background),
            contentAlignment = Alignment.Center,
        ) {
            if (mark != null) {
                Text(mark, color = ink, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val heights = floatArrayOf(0.28f, 0.55f, 0.82f, 0.55f, 0.28f)
                    heights.forEachIndexed { index, height ->
                        val x = this.size.width * (0.28f + index * 0.11f)
                        val half = this.size.height * height * 0.22f
                        drawLine(ink, Offset(x, center.y - half), Offset(x, center.y + half), this.size.width * 0.045f, StrokeCap.Round)
                    }
                }
            }
        }
        return
    }
    Canvas(modifier.size(size).clip(MaterialTheme.shapes.medium)) {
        drawRect(background)
        if (design == AppDesign.MONO) {
            // Small, quiet audio mark with enough negative space to read at thumbnail size.
            MonoMarkHeights.forEachIndexed { index, height ->
                val x = this.size.width * (0.26f + index * 0.12f)
                val half = this.size.height * height / 2f
                drawLine(
                    color = ink,
                    start = Offset(x, center.y - half),
                    end = Offset(x, center.y + half),
                    strokeWidth = this.size.width * 0.055f,
                    cap = StrokeCap.Round,
                )
            }
            return@Canvas
        }
        if (design == AppDesign.AMP) {
            // Static sleeve graphic; these segments are not a live audio meter.
            for (column in 0..4) {
                for (row in 0..7) {
                    val filled = row < MeterHeights[(column + variant) % MeterHeights.size]
                    drawRect(
                        if (filled) ink.copy(alpha = 0.8f) else ink.copy(alpha = 0.1f),
                        Offset(this.size.width * (0.16f + column * 0.145f), this.size.height * (0.79f - row * 0.085f)),
                        Size(this.size.width * 0.085f, this.size.height * 0.045f),
                    )
                }
            }
            return@Canvas
        }
        if (design == AppDesign.BLUE) {
            drawCircle(ink.copy(alpha = 0.18f), this.size.width * 0.65f, Offset(this.size.width * 0.9f, this.size.height * 0.12f))
            drawCircle(ink.copy(alpha = 0.22f), this.size.width * 0.55f, Offset(this.size.width * 0.12f, this.size.height * 0.95f))
            val disc = this.size.width * 0.31f
            drawCircle(ink, disc, center)
            drawCircle(background.copy(alpha = 0.8f), disc * 0.72f, center)
            drawCircle(ink, disc * 0.22f, center)
            return@Canvas
        }
        if (design == AppDesign.ALBUM) {
            val (_, dark) = AlbumCovers[variant]
            drawRect(Brush.verticalGradient(listOf(background, dark)))
            drawCircle(ink.copy(alpha = 0.2f), this.size.width * 0.5f, Offset(this.size.width * 0.85f, this.size.height * 0.1f))
            drawCircle(ink.copy(alpha = 0.12f), this.size.width * 0.55f, Offset(this.size.width * 0.1f, this.size.height * 0.95f))
            drawCircle(ink.copy(alpha = 0.9f), this.size.width * 0.16f, center)
            return@Canvas
        }
        if (design == AppDesign.CONSOLE) {
            // VU meter dial: ticked arc and a seed-parked needle on a cream faceplate.
            val hub = Offset(this.size.width * 0.5f, this.size.height * 0.72f)
            val radius = this.size.width * 0.36f
            val accent = Color(0xFFB3492E)
            for (tick in 0..10) {
                val angle = (200f + tick * 14f) * PI.toFloat() / 180f
                val outer = Offset(hub.x + radius * cos(angle), hub.y + radius * sin(angle))
                val inner = Offset(hub.x + radius * 0.82f * cos(angle), hub.y + radius * 0.82f * sin(angle))
                drawLine(if (tick > 7) accent else ink.copy(alpha = 0.7f), inner, outer, 1.5.dp.toPx())
            }
            val needle = (180f + 90f - VuNeedleDegrees[variant]) * PI.toFloat() / 180f
            drawLine(accent, hub, Offset(hub.x + radius * 0.76f * cos(needle), hub.y + radius * 0.76f * sin(needle)), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(ink, this.size.width * 0.05f, hub)
            drawLine(ink.copy(alpha = 0.4f), Offset(this.size.width * 0.1f, this.size.height * 0.9f), Offset(this.size.width * 0.9f, this.size.height * 0.9f), 1.dp.toPx())
            return@Canvas
        }
        if (design == AppDesign.AURORA) {
            val (a, b) = AuroraBlobs[variant]
            drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.8f), Color.Transparent), center = Offset(this.size.width * 0.2f, this.size.height * 0.25f), radius = this.size.width * 0.55f), radius = this.size.width * 0.55f, center = Offset(this.size.width * 0.2f, this.size.height * 0.25f))
            drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.7f), Color.Transparent), center = Offset(this.size.width * 0.85f, this.size.height * 0.85f), radius = this.size.width * 0.5f), radius = this.size.width * 0.5f, center = Offset(this.size.width * 0.85f, this.size.height * 0.85f))
            drawCircle(ink.copy(alpha = 0.9f), this.size.width * 0.07f, center)
            return@Canvas
        }
        val recordCenter = Offset(this.size.width * 0.74f, this.size.height * 0.35f)
        for (ring in 1..5) {
            drawCircle(ink.copy(alpha = 0.12f), this.size.width * ring * 0.145f, recordCenter, style = Stroke(1.dp.toPx()))
        }
        val bars = 13
        for (i in 0 until bars) {
            val x = this.size.width * (0.15f + i * 0.7f / (bars - 1))
            val envelope = sin(PI * (i + 1) / (bars + 1)).toFloat()
            val half = this.size.height * (0.04f + 0.19f * envelope * if (i % 3 == 0) 0.6f else 1f)
            drawLine(ink, Offset(x, center.y - half), Offset(x, center.y + half), this.size.width * 0.025f, StrokeCap.Round)
        }
    }
}
