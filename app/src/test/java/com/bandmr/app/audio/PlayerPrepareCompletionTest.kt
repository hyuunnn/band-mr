package com.bandmr.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐시 준비 완료가 원본 엔진을 붙이는 조건([PlayerController.shouldAttachPrepared]).
 *
 * 캐시 없는 곡 X를 열고(준비 1회차) → 곡 Y를 열어 준비 슬롯이 바뀐 뒤 → X로 돌아오면 준비 2회차가
 * 시작된다. 2회차는 곡 단위 락에서 기다렸다가 곧바로 성공하므로 완료가 연달아 두 번 온다.
 * 두 번째 완료가 엔진을 또 붙이면 첫 엔진이 참조 없이 계속 재생돼 멈출 방법이 없다.
 */
class PlayerPrepareCompletionTest {

    @Test
    fun `첫 완료는 붙이고 이어서 온 두 번째 완료는 붙이지 않는다`() {
        assertTrue(PlayerController.shouldAttachPrepared(1, 1, aiMode = false, hasSource = false))
        assertFalse(PlayerController.shouldAttachPrepared(1, 1, aiMode = false, hasSource = true))
    }

    @Test
    fun `다른 곡이거나 AI ON이면 붙이지 않는다`() {
        assertFalse(PlayerController.shouldAttachPrepared(2, 1, aiMode = false, hasSource = false))
        assertFalse(PlayerController.shouldAttachPrepared(1, 1, aiMode = true, hasSource = false))
    }
}
