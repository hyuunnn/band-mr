package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLoopTest {

    @Test
    fun `둘 다 있고 0_5초 이상이면 무장한다`() {
        assertFalse(PlaybackLoop.isArmed(null, null))
        assertFalse(PlaybackLoop.isArmed(10_000, null))
        assertFalse(PlaybackLoop.isArmed(null, 20_000))
        assertFalse(PlaybackLoop.isArmed(10_000, 10_400))
        assertTrue(PlaybackLoop.isArmed(10_000, 10_500))
        assertTrue(PlaybackLoop.isArmed(10_000, 20_000))
    }

    @Test
    fun `B가 A보다 앞이면 순서를 바꾼다`() {
        assertEquals(10_000L to 20_000L, PlaybackLoop.ordered(10_000, 20_000))
        assertEquals(10_000L to 20_000L, PlaybackLoop.ordered(20_000, 10_000))
    }

    @Test
    fun `무장 중 시크는 구간 안으로 가둔다`() {
        val duration = 60_000L
        assertEquals(12_000L, PlaybackLoop.clampSeek(12_000, 10_000, 20_000, duration))
        assertEquals(10_000L, PlaybackLoop.clampSeek(3_000, 10_000, 20_000, duration))
        assertEquals(20_000L, PlaybackLoop.clampSeek(40_000, 10_000, 20_000, duration))
        assertEquals(3_000L, PlaybackLoop.clampSeek(3_000, 10_000, null, duration))
        assertEquals(0L, PlaybackLoop.clampSeek(-100, null, null, duration))
    }

    @Test
    fun `마지막 청크는 끝을 넘기지 않는다`() {
        assertEquals(2048, PlaybackLoop.chunkFrames(0, 10_000, 2048))
        assertEquals(100, PlaybackLoop.chunkFrames(9_900, 10_000, 2048))
        assertEquals(0, PlaybackLoop.chunkFrames(10_000, 10_000, 2048))
        assertEquals(0, PlaybackLoop.chunkFrames(10_100, 10_000, 2048))
    }

    @Test
    fun `A나 B만 찍거나 순서를 맞춘다`() {
        assertEquals(10_000L to null, PlaybackLoop.applyPoint(null, null, 10_000, isStart = true))
        assertEquals(null to 20_000L, PlaybackLoop.applyPoint(null, null, 20_000, isStart = false))
        assertEquals(10_000L to 20_000L, PlaybackLoop.applyPoint(10_000, null, 20_000, isStart = false))
        assertEquals(10_000L to 20_000L, PlaybackLoop.applyPoint(null, 20_000, 10_000, isStart = true))
        assertEquals(8_000L to 20_000L, PlaybackLoop.applyPoint(10_000, 20_000, 8_000, isStart = true))
        assertEquals(5_000L to 10_000L, PlaybackLoop.applyPoint(10_000, 20_000, 5_000, isStart = false))
    }

    @Test
    fun `무장일 때만 A로 돌아간다`() {
        assertEquals(10_000L, PlaybackLoop.restartFrame(10_000, 20_000))
        assertEquals(null, PlaybackLoop.restartFrame(PlaybackLoop.DISABLED_FRAME, 20_000))
        assertEquals(null, PlaybackLoop.restartFrame(10_000, 10_000))
    }

    @Test
    fun `프레임 한계는 무장일 때만 B다`() {
        assertEquals(20_000L, PlaybackLoop.limitFrames(60_000, 10_000, 20_000))
        assertEquals(60_000L, PlaybackLoop.limitFrames(60_000, PlaybackLoop.DISABLED_FRAME, 20_000))
        assertEquals(60_000L, PlaybackLoop.limitFrames(60_000, 10_000, 10_000))
        assertEquals(8_000L, PlaybackLoop.limitFrames(8_000, 10_000, 20_000))
    }
}
