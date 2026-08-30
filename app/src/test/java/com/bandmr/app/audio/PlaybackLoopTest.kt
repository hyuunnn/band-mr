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

    // ---------- isAtLimit: 끝까지 들은 뒤 AI 전환 시 재생 버튼이 죽던 회귀 ----------

    // PlayerController의 ms↔프레임 변환(양쪽 절단). 왕복 손실을 그대로 재현하려고 옮겨 왔다
    private fun framesToMs(frames: Long): Long =
        if (frames <= 0) 0 else frames * 1000 / PIPELINE_SAMPLE_RATE

    private fun msToFrames(ms: Long): Long = ms * PIPELINE_SAMPLE_RATE / 1000

    /**
     * 실기기에서 잡힌 값: 3분 35초 곡(MixCache WAV 37,945,388B → 9,486,336프레임).
     * 끝까지 재생 → AI ON → 새 엔진에 위치를 ms로 넘기면 9,486,306프레임(30프레임 앞)이 되고,
     * `>=` 비교로는 끝이 아니어서 재생이 30프레임만 나고 끝나버렸다.
     */
    @Test
    fun `ms 왕복으로 끝보다 앞에 놓인 위치도 끝으로 본다`() {
        val total = 9_486_336L // 37,945,344 오디오 바이트 / 4
        val roundTripped = msToFrames(framesToMs(total))

        assertEquals(215_109L, framesToMs(total))
        assertEquals(9_486_306L, roundTripped)
        // 손실이 실제로 있어야 검증이 무의미해지지 않는다
        assertTrue("왕복 손실이 없으면 이 테스트는 의미가 없다", roundTripped < total)

        assertTrue(PlaybackLoop.isAtLimit(roundTripped, total, PIPELINE_SAMPLE_RATE))
        assertTrue(PlaybackLoop.isAtLimit(total, total, PIPELINE_SAMPLE_RATE))
        // 수정 전 판정(>=)으로는 끝으로 보이지 않았다
        assertFalse(roundTripped >= total)
    }

    @Test
    fun `ms 왕복 최대 손실이 여유 안에 들어온다`() {
        // 임의 길이에서도 왕복 손실이 SLACK_MS 안이어야 한다(여유 산정의 근거)
        var worst = 0L
        for (frames in 9_000_000L..9_000_000L + 100_000L) {
            val loss = frames - msToFrames(framesToMs(frames))
            if (loss > worst) worst = loss
        }
        val slackFrames = PIPELINE_SAMPLE_RATE * PlaybackLoop.SLACK_MS / 1000
        assertTrue("최대 손실 $worst 프레임 > 여유 $slackFrames 프레임", worst <= slackFrames)
    }

    @Test
    fun `여유를 넘게 남았으면 끝이 아니다`() {
        val total = 9_486_336L
        val slackFrames = PIPELINE_SAMPLE_RATE * PlaybackLoop.SLACK_MS / 1000
        assertFalse(PlaybackLoop.isAtLimit(total - slackFrames - 1, total, PIPELINE_SAMPLE_RATE))
        assertFalse(PlaybackLoop.isAtLimit(0, total, PIPELINE_SAMPLE_RATE))
        // 곡 중간(1분)은 당연히 끝이 아니다 — 여유가 너무 커지지 않았는지 확인
        assertFalse(PlaybackLoop.isAtLimit(msToFrames(60_000), total, PIPELINE_SAMPLE_RATE))
    }

    @Test
    fun `A-B 무장 중에는 B가 한계다`() {
        val total = 9_486_336L
        val endFrame = msToFrames(63_000)
        val limit = PlaybackLoop.limitFrames(total, msToFrames(58_000), endFrame)
        assertEquals(endFrame, limit)
        assertTrue(PlaybackLoop.isAtLimit(msToFrames(63_000), limit, PIPELINE_SAMPLE_RATE))
        assertFalse(PlaybackLoop.isAtLimit(msToFrames(60_000), limit, PIPELINE_SAMPLE_RATE))
    }
}
