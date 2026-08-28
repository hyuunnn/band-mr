package com.bandmr.app.audio

/**
 * 재생 위치 점프. 시크/스킵 목표를 [0, durationMs] 안으로 자른다.
 * duration이 0 이하면 0 (엔진 미준비).
 */
object PlaybackSkip {
    const val SMALL_MS = 5_000L
    const val LARGE_MS = 10_000L

    fun clamp(ms: Long, durationMs: Long): Long =
        ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
}
