package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSkipTest {

    @Test
    fun `5초 10초 앞뒤는 현재 위치에 더해 자른다`() {
        val duration = 60_000L
        val pos = 20_000L
        assertEquals(15_000L, PlaybackSkip.clamp(pos - PlaybackSkip.SMALL_MS, duration))
        assertEquals(25_000L, PlaybackSkip.clamp(pos + PlaybackSkip.SMALL_MS, duration))
        assertEquals(10_000L, PlaybackSkip.clamp(pos - PlaybackSkip.LARGE_MS, duration))
        assertEquals(30_000L, PlaybackSkip.clamp(pos + PlaybackSkip.LARGE_MS, duration))
    }

    @Test
    fun `시작과 끝을 넘지 않는다`() {
        assertEquals(0L, PlaybackSkip.clamp(3_000 - PlaybackSkip.LARGE_MS, 60_000))
        assertEquals(60_000L, PlaybackSkip.clamp(55_000 + PlaybackSkip.LARGE_MS, 60_000))
        assertEquals(0L, PlaybackSkip.clamp(0 - PlaybackSkip.SMALL_MS, 60_000))
        assertEquals(60_000L, PlaybackSkip.clamp(60_000 + PlaybackSkip.SMALL_MS, 60_000))
    }

    @Test
    fun `길이가 없으면 0에 고정한다`() {
        assertEquals(0L, PlaybackSkip.clamp(PlaybackSkip.SMALL_MS, 0))
        assertEquals(0L, PlaybackSkip.clamp(1_000 + PlaybackSkip.LARGE_MS, -1))
        assertEquals(0L, PlaybackSkip.clamp(-100, 0))
    }
}
