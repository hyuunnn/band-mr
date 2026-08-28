package com.bandmr.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bandmr.app.audio.PlaybackLoop

/**
 * MixCache 개요 파형. 탭/드래그로 시크하고, A-B 구간과 재생 위치를 표시한다.
 */
@Composable
fun WaveformBar(
    peaks: FloatArray,
    durationMs: Long,
    posMs: Long,
    dragging: Boolean,
    dragPosMs: Float,
    loopStartMs: Long?,
    loopEndMs: Long?,
    onDraggingChange: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val loopFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val playhead = MaterialTheme.colorScheme.secondary
    val duration = durationMs.coerceAtLeast(1L)
    val playMs = if (dragging) dragPosMs else posMs.toFloat()
    val armed = PlaybackLoop.isArmed(loopStartMs, loopEndMs)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(88.dp)
            .semantics { contentDescription = "파형 시크바" }
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onDraggingChange(true)
                    onDrag(xToMs(down.position.x, size.width, duration))
                    drag(down.id) { change ->
                        onDrag(xToMs(change.position.x, size.width, duration))
                        change.consume()
                    }
                    onDragEnd()
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        if (peaks.isEmpty()) return@Canvas
        if (armed && loopStartMs != null && loopEndMs != null) {
            val x0 = (loopStartMs.toFloat() / duration) * w
            val x1 = (loopEndMs.toFloat() / duration) * w
            drawRect(
                color = loopFill,
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(1f), h),
            )
        }
        val barW = w / peaks.size
        val stroke = (barW * 0.72f).coerceIn(1f, 3f)
        val playX = (playMs / duration) * w
        peaks.forEachIndexed { i, p ->
            val x = (i + 0.5f) * barW
            val amp = (p.coerceIn(0f, 1f) * mid * 0.92f).coerceAtLeast(1f)
            drawLine(
                color = if (x <= playX) played else idle,
                start = Offset(x, mid - amp),
                end = Offset(x, mid + amp),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        val headX = ((playMs / duration) * w).coerceIn(0f, w)
        drawLine(
            color = playhead,
            start = Offset(headX, 4f),
            end = Offset(headX, h - 4f),
            strokeWidth = 3f,
            cap = StrokeCap.Round,
        )
        if (loopStartMs != null) {
            drawMarker(loopStartMs.toFloat() / duration * w, h, playhead)
        }
        if (loopEndMs != null) {
            drawMarker(loopEndMs.toFloat() / duration * w, h, playhead)
        }
    }
}

private fun DrawScope.drawMarker(
    x: Float,
    h: Float,
    color: Color,
) {
    drawLine(
        color = color.copy(alpha = 0.85f),
        start = Offset(x, 0f),
        end = Offset(x, h),
        strokeWidth = 2f,
    )
}

private fun xToMs(x: Float, width: Int, durationMs: Long): Float {
    val w = width.coerceAtLeast(1)
    return (x / w * durationMs).coerceIn(0f, durationMs.toFloat())
}
