package com.bandmr.app.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheReadyGateTest {

    @Test
    fun `이미 준비되면 바로 반환한다`() = runBlocking {
        val gate = CacheReadyGate()
        withTimeout(200) { gate.await(1L) { true } }
    }

    @Test
    fun `해당 곡 signal이면 대기가 끝난다`() = runBlocking {
        val gate = CacheReadyGate()
        val waiting = async { gate.await(7L) { false } }
        waitUntilSubscribed(gate)
        gate.signal(7L)
        withTimeout(1000) { waiting.await() }
    }

    @Test
    fun `다른 곡 signal은 깨우지 않는다`() = runBlocking {
        val gate = CacheReadyGate()
        val waiting = async { gate.await(1L) { false } }
        waitUntilSubscribed(gate)
        gate.signal(2L)
        yield()
        assertTrue(waiting.isActive)
        gate.signal(1L)
        withTimeout(1000) { waiting.await() }
        assertFalse(waiting.isActive)
    }

    @Test
    fun `구독 직후 이미 준비면 signal 없이 끝난다`() = runBlocking {
        val gate = CacheReadyGate()
        val firstCheck = java.util.concurrent.atomic.AtomicBoolean(true)
        // 시작 직후는 아직 없고, 구독이 붙은 뒤에는 파일이 보이는 경쟁을 흉내 낸다
        withTimeout(1000) {
            gate.await(3L) { !firstCheck.getAndSet(false) }
        }
    }

    private suspend fun waitUntilSubscribed(gate: CacheReadyGate) {
        withTimeout(1000) { gate.subscriberCount.first { it > 0 } }
    }
}
