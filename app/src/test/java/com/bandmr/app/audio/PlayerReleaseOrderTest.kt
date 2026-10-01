package com.bandmr.app.audio

import com.bandmr.app.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [PlayerController.publishReleased]의 순서와 [PlaybackService]의 종료 판정.
 *
 * 서비스는 `Main.immediate`로 수집해서, 메인 스레드에서 값을 대입하는 그 자리에서 수집기가 돈다.
 * 여기서는 같은 경로(`isDispatchNeeded == false` → 그 자리에서 재개)를 타는 [Dispatchers.Unconfined]로
 * 흉내 낸다. 수정 전 순서(제목 먼저)로는 종료 분기를 한 번도 타지 않아, 재생 중인 곡을 지우면
 * 무반응 알림과 FGS가 남는다.
 */
class PlayerReleaseOrderTest {

    /** PlaybackService.onCreate의 두 수집기와 같은 모양으로 [publish] 동안의 판정을 기록한다 */
    private fun serviceDecisions(
        publish: (MutableStateFlow<Boolean>, MutableStateFlow<Long>, MutableStateFlow<String?>) -> Unit,
    ): List<String> {
        val playing = MutableStateFlow(true)
        val duration = MutableStateFlow(215_000L)
        val title = MutableStateFlow<String?>("곡")
        val log = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            scope.launch { playing.collect { log += "update" } }
            scope.launch {
                title.collect { t ->
                    log += if (PlaybackService.shouldStop(t, playing.value)) "stop" else "update"
                }
            }
            log.clear() // 구독하자마자 현재 값으로 한 번씩 도는 것은 빼고 본다
            publish(playing, duration, title)
        } finally {
            scope.cancel()
        }
        return log
    }

    @Test
    fun `해제 순서대로면 서비스가 종료 분기를 탄다`() {
        val log = serviceDecisions { playing, duration, title ->
            PlayerController.publishReleased(playing, duration, title)
        }
        assertEquals(listOf("update", "stop"), log)
    }

    @Test
    fun `제목을 먼저 내리면 종료 분기를 한 번도 타지 않는다`() {
        val log = serviceDecisions { playing, duration, title ->
            title.value = null // 수정 전 release() 순서
            playing.value = false
            duration.value = 0L
        }
        assertFalse("수정 전 순서로도 종료된다면 이 테스트는 순서를 검증하지 못한다", "stop" in log)
    }
}
