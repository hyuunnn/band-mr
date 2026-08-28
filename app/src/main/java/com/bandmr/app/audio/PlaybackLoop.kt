package com.bandmr.app.audio

/**
 * A-B 구간 반복. 엔진은 [isArmed]일 때만 B에 닿으면 A로 되돌린다.
 * 시크/점프도 무장 중에는 [startMs, endMs] 안으로 가둔다.
 */
object PlaybackLoop {
    const val MIN_GAP_MS = 500L
    const val DISABLED_FRAME = -1L

    fun isArmed(startMs: Long?, endMs: Long?): Boolean =
        startMs != null && endMs != null && endMs - startMs >= MIN_GAP_MS

    /** 끝점이 시작보다 앞이면 서로 바꾼다. */
    fun ordered(aMs: Long, bMs: Long): Pair<Long, Long> =
        if (bMs < aMs) bMs to aMs else aMs to bMs

    fun clampSeek(ms: Long, startMs: Long?, endMs: Long?, durationMs: Long): Long {
        val base = PlaybackSkip.clamp(ms, durationMs)
        val start = startMs
        val end = endMs
        return if (start != null && end != null && isArmed(start, end)) {
            base.coerceIn(start, end)
        } else {
            base
        }
    }

    /** A 또는 B를 찍는다. 둘 다 있으면 시간 순으로 맞춘다. */
    fun applyPoint(
        startMs: Long?,
        endMs: Long?,
        markMs: Long,
        isStart: Boolean,
    ): Pair<Long?, Long?> {
        val start = if (isStart) markMs else startMs
        val end = if (isStart) endMs else markMs
        return if (start != null && end != null) ordered(start, end) else start to end
    }

    fun framesArmed(startFrame: Long, endFrame: Long): Boolean =
        startFrame >= 0 && endFrame > startFrame

    /** B에 도달했을 때 돌아갈 A. 무장 아니면 null (곡 종료). */
    fun restartFrame(startFrame: Long, endFrame: Long): Long? =
        if (framesArmed(startFrame, endFrame)) startFrame else null

    fun limitFrames(totalFrames: Long, startFrame: Long, endFrame: Long): Long =
        if (framesArmed(startFrame, endFrame)) minOf(endFrame, totalFrames) else totalFrames

    fun chunkFrames(posFrames: Long, limitFrames: Long, chunk: Int): Int {
        val remaining = (limitFrames - posFrames).coerceAtLeast(0L)
        return remaining.toInt().coerceAtMost(chunk)
    }
}
