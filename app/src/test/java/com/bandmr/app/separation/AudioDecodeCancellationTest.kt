package com.bandmr.app.separation

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class AudioDecodeCancellationTest {
    @Test
    fun `출력과 진행률이 없는 반복에서도 취소된다`() {
        val job = Job()
        var steps = 0
        assertThrows(CancellationException::class.java) {
            AudioDecode.decodeUntilEos({ job.ensureActive() }) {
                steps++
                job.cancel()
                false // MediaCodec의 TRY_AGAIN처럼 출력이 없는 반복
            }
        }
        assertEquals(1, steps)
    }

    @Test
    fun `마지막 출력 도중 취소돼도 정상 완료로 반환하지 않는다`() {
        val job = Job()
        assertThrows(CancellationException::class.java) {
            AudioDecode.decodeUntilEos({ job.ensureActive() }) {
                job.cancel()
                true
            }
        }
    }

    @Test
    fun `이미 취소됐으면 디코드를 시작하지 않는다`() {
        val job = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) {
            AudioDecode.decodeUntilEos({ job.ensureActive() }) { error("디코드가 시작됨") }
        }
    }

    @Test
    fun `정상 입력은 EOS까지 처리한다`() {
        var steps = 0
        AudioDecode.decodeUntilEos({}) { ++steps == 3 }
        assertEquals(3, steps)
    }
}
