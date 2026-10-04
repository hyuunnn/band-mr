package com.bandmr.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Shared flat panel: spacing and outlines stay consistent across all three screens. */
@Composable
fun StudioPanel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, active: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
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
fun TrackArtwork(seed: Long, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val palettes = listOf(
        Color(0xFF264E43) to Color(0xFFC3DDC3),
        Color(0xFF47556A) to Color(0xFFCFDCEB),
        Color(0xFF765644) to Color(0xFFF1D9AF),
        Color(0xFF635A70) to Color(0xFFE3D9EF),
    )
    val (background, ink) = palettes[Math.floorMod(seed, palettes.size.toLong()).toInt()]
    Canvas(modifier.size(size).clip(RoundedCornerShape(16.dp))) {
        drawRect(background)
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
