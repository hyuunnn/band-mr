package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 엔진을 새로 만들 때 넘기는 시작 위치 규약.
 *
 * [PlayerController.ensureLoaded]는 AI ON↔OFF 전환에서 재생 위치를 유지하는데, 같은 코드가
 * **곡 전환**에도 타면서 이전 곡의 위치를 새 곡에 적용했다(실기기 재현: 3분35초 곡 0:59에서
 * 12초 곡을 열면 "0:12 / 0:12"로 열림). 이 테스트가 두 경로의 차이를 고정한다.
 */
class PlayerLoadPositionTest {

    @Test
    fun `곡이 바뀌면 이전 위치를 물려받지 않는다`() {
        assertEquals(0L, PlayerController.startPositionMs(songChanged = true, currentPosMs = 59_000))
        assertEquals(0L, PlayerController.startPositionMs(songChanged = true, currentPosMs = 0))
    }

    @Test
    fun `같은 곡의 모드 전환은 위치를 유지한다`() {
        assertEquals(
            59_000L,
            PlayerController.startPositionMs(songChanged = false, currentPosMs = 59_000),
        )
    }

    /**
     * 물려받으면 왜 나쁜지: 새 곡이 더 짧으면 시크 클램프가 위치를 끝으로 접는다.
     * 곡 끝에서 열리면 진행바가 "끝/끝"으로 보이고, 재생은 곡 끝 판정으로 빠진다
     * ([PlaybackLoop.isAtLimit]가 없으면 재생 버튼이 죽은 것처럼 보였다).
     */
    @Test
    fun `짧은 곡에 이전 위치를 넘기면 곡 끝으로 접힌다`() {
        val prevPosMs = 59_000L // 3분35초 곡에서 재생 중이던 위치
        val newDurationMs = 12_000L // 새로 여는 12초 곡

        val carried = PlaybackSkip.clamp(prevPosMs, newDurationMs)
        assertEquals("물려주면 끝으로 접힌다", newDurationMs, carried)

        val fixed = PlayerController.startPositionMs(songChanged = true, currentPosMs = prevPosMs)
        assertEquals(0L, PlaybackSkip.clamp(fixed, newDurationMs))
        assertNotEquals("수정 전과 결과가 같으면 검증이 무의미", carried, fixed)
    }
}
