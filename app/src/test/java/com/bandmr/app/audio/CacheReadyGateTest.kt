package com.bandmr.app.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    @Test
    fun `완료 알림이 몰려도 마지막 곡의 대기를 끝낸다`() = runTest {
        val gate = CacheReadyGate()
        var ready = false
        val waiting = backgroundScope.async { gate.await(999L) { ready } }
        runCurrent()
        assertFalse(waiting.isCompleted)

        repeat(32) { gate.signal(it.toLong()) }
        ready = true
        gate.signal(999L)
        runCurrent()

        assertTrue("버퍼가 차도 완료 신호를 잃으면 안 된다", waiting.isCompleted)
        waiting.await()
    }

    @Test
    fun `해당 곡 알림 뒤 다른 알림이 몰려도 파일 상태를 확인해 대기를 끝낸다`() = runTest {
        val gate = CacheReadyGate()
        var ready = false
        val waiting = backgroundScope.async { gate.await(999L) { ready } }
        runCurrent()
        assertFalse(waiting.isCompleted)

        ready = true
        gate.signal(999L)
        repeat(32) { gate.signal(it.toLong()) }
        runCurrent()

        assertTrue("다른 곡 알림만 남아도 완성 파일을 확인해야 한다", waiting.isCompleted)
        waiting.await()
    }

    @Test
    fun `알림 폭주 시 완성된 곡들만 깨우고 취소된 대기의 구독은 해제한다`() = runTest {
        val gate = CacheReadyGate()
        val ready = mutableSetOf<Long>()
        val waiting = (1L..3L).map { id ->
            backgroundScope.async { gate.await(id) { id in ready } }
        }
        runCurrent()
        assertEquals(3, gate.subscriberCount.value)

        ready += setOf(1L, 2L)
        gate.signal(1L)
        gate.signal(2L)
        repeat(64) { gate.signal(100L + it) }
        runCurrent()

        assertTrue(waiting[0].isCompleted)
        assertTrue(waiting[1].isCompleted)
        assertFalse(waiting[2].isCompleted)
        assertEquals(1, gate.subscriberCount.value)
        waiting[2].cancelAndJoin()
        assertEquals(0, gate.subscriberCount.value)
    }

    private suspend fun waitUntilSubscribed(gate: CacheReadyGate) {
        withTimeout(1000) { gate.subscriberCount.first { it > 0 } }
    }
}
