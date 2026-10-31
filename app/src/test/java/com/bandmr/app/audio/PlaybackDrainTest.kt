package com.bandmr.app.audio

import org.junit.Assert.*
import org.junit.Test

class PlaybackDrainTest {
    @Test
    fun `전곡을 기록해도 재생 헤드가 끝에 도달하기 전에는 잔여 출력이 있다`() {
        val output = PlaybackDrain()
        output.recordWrite(44_100 * 2)
        assertEquals(7_115L, output.pendingFrames(36_985))
        assertEquals(1L, output.pendingFrames(44_099))
        assertEquals(0L, output.pendingFrames(44_100))
    }

    @Test
    fun `DSP 잔여분과 부분 쓰기도 실제 기록한 스테레오 프레임 수로 기다린다`() {
        val output = PlaybackDrain()
        output.recordWrite(4096)
        output.recordWrite(1024)
        output.recordWrite(1024)
        assertEquals(1024L, output.pendingFrames(2048))
        assertEquals(0L, output.pendingFrames(3072))
    }

    @Test
    fun `flush 뒤에는 이전 재생 헤드와 종료 세대를 재사용하지 않는다`() {
        val output = PlaybackDrain()
        output.recordWrite(88_200)
        val oldEpoch = output.epoch
        output.reset()
        assertNotEquals(oldEpoch, output.epoch)
        assertEquals(0L, output.pendingFrames(0))
        output.recordWrite(2048)
        assertEquals(1024L, output.pendingFrames(0))
        assertEquals(0L, output.pendingFrames(1024))
    }

    @Test
    fun `unsigned 재생 헤드가 32비트를 돌아도 마지막 프레임까지 기다린다`() {
        val output = PlaybackDrain()
        // 배열을 만들지 않고 2^32 + 100 프레임의 장시간 출력을 누적한다.
        repeat(8) { output.recordWrite(1 shl 30) }
        output.recordWrite(200)
        assertEquals(101L, output.pendingFrames(-1))
        assertEquals(100L, output.pendingFrames(0))
        assertEquals(0L, output.pendingFrames(100))
    }
}
