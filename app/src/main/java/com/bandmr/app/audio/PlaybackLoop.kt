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

    /**
     * 외부에서 들어온 위치가 **사실상** 한계([limitFrames]: 곡 끝 또는 B점)인지.
     *
     * 재생 버튼은 "끝에 서 있으면 처음으로 되돌린 뒤 재생"해야 하는데, 엔진 밖에서 오는 위치는
     * 전부 ms로 양자화된다 — UI 슬라이더의 [clampSeek], 그리고 모드 전환 때 PlayerController가
     * 프레임→ms→프레임으로 넘기는 값(양쪽 절단으로 최대 46프레임이 깎인다). 그래서 끝까지 들은 뒤
     * AI를 켜면 새 엔진의 위치가 끝보다 수십 프레임 앞이 되고, `>=` 비교로는 끝으로 보이지 않는다.
     * 그 상태로 재생하면 30프레임(0.7ms)만 렌더하고 곡 끝 처리로 빠져 **재생 버튼이 죽은 것처럼
     * 보인다**(실기기 재현: 끝에서 무반응, -5초 후에는 정상).
     *
     * [SLACK_MS]는 ms 양자화로 구분할 수 없는 차이라 끝으로 취급한다. 오디오 스레드의 곡 끝
     * 판정에는 쓰지 않는다 — 그쪽은 정확한 프레임에서 청크 단위로 진행하므로 여유가 필요 없고,
     * 넣으면 A-B 랩 지점이 밀려 출력이 달라진다.
     */
    fun isAtLimit(posFrames: Long, limitFrames: Long, sampleRate: Int): Boolean =
        limitFrames - posFrames <= sampleRate * SLACK_MS / 1000

    /** ms 왕복 절단 한계(최대 46프레임 @44.1k)를 덮는 여유 */
    const val SLACK_MS = 2L

    fun limitFrames(totalFrames: Long, startFrame: Long, endFrame: Long): Long =
        if (framesArmed(startFrame, endFrame)) minOf(endFrame, totalFrames) else totalFrames

    fun chunkFrames(posFrames: Long, limitFrames: Long, chunk: Int): Int {
        val remaining = (limitFrames - posFrames).coerceAtLeast(0L)
        return remaining.toInt().coerceAtMost(chunk)
    }
}
