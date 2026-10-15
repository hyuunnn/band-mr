package com.bandmr.app.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourcePreparationTest {
    @Test
    fun `준비 중 종료하고 다시 재생하면 새 요청만 재생기를 연다`() = runTest {
        val preparation = preparation()
        val oldReady = CompletableDeferred<Unit>()
        val newReady = CompletableDeferred<Unit>()
        val openedPlayers = mutableListOf<Boolean>()
        var pendingPlay = true
        var preparing = true
        val attach: (Boolean) -> Unit = { ok ->
            assertTrue(ok)
            preparing = false
            openedPlayers += pendingPlay
            pendingPlay = false
        }
        preparation.start({ oldReady.await() }, attach)
        runCurrent()
        preparation.invalidate()
        pendingPlay = false
        preparation.start({ newReady.await() }, attach)
        pendingPlay = true
        runCurrent()

        oldReady.complete(Unit)
        runCurrent()
        assertTrue(openedPlayers.isEmpty())
        assertTrue(preparing)
        assertTrue(pendingPlay)

        newReady.complete(Unit)
        runCurrent()
        assertEquals(listOf(true), openedPlayers)
        assertFalse(preparing)
        assertFalse(pendingPlay)
    }

    @Test
    fun `새 요청 완료 뒤 이전 작업이 끝나도 재생기를 덮어쓰지 않는다`() = runTest {
        val preparation = preparation()
        val oldReady = CompletableDeferred<Unit>()
        val newReady = CompletableDeferred<Unit>()
        val openedPlayers = mutableListOf<String>()
        preparation.start({ oldReady.await() }) { openedPlayers += "old" }
        runCurrent()
        preparation.invalidate()
        preparation.start({ newReady.await() }) { openedPlayers += "new" }
        runCurrent()

        newReady.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), openedPlayers)
        oldReady.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), openedPlayers)
    }

    @Test
    fun `이전 작업 실패는 새 요청의 준비 상태와 재생 의도를 바꾸지 않는다`() = runTest {
        val preparation = preparation()
        val oldReady = CompletableDeferred<Unit>()
        val newReady = CompletableDeferred<Unit>()
        var failed = false
        var preparing = true
        var pendingPlay = true
        preparation.start({ oldReady.await(); error("old failure") }) {
            failed = true
            preparing = false
            pendingPlay = false
        }
        runCurrent()
        // 곡 전환처럼 새 요청이 시작되면 이전 요청은 자동으로 무효화된다.
        preparation.start({ newReady.await() }) { ok ->
            assertTrue(ok)
            preparing = false
        }
        runCurrent()
        oldReady.complete(Unit)
        runCurrent()
        assertFalse(failed)
        assertTrue(preparing)
        assertTrue(pendingPlay)
        newReady.complete(Unit)
        runCurrent()
        assertFalse(preparing)
        assertFalse(failed)
    }

    @Test
    fun `재준비 없이 종료하면 캐시만 완성하고 재생기는 열지 않는다`() = runTest {
        val preparation = preparation()
        val ready = CompletableDeferred<Unit>()
        var cacheReady = false
        var opened = false
        preparation.start({ ready.await(); cacheReady = true }) { opened = true }
        runCurrent()
        preparation.invalidate()
        ready.complete(Unit)
        runCurrent()
        assertTrue(cacheReady)
        assertFalse(opened)
    }

    @Test
    fun `현재 요청 실패는 전달하고 다음 요청은 정상 완료한다`() = runTest {
        val preparation = preparation()
        val results = mutableListOf<Boolean>()
        preparation.start({ error("decode failed") }) { results += it }
        runCurrent()
        assertEquals(listOf(false), results)
        preparation.start({}) { results += it }
        runCurrent()
        assertEquals(listOf(false, true), results)
    }

    private fun TestScope.preparation(): SourcePreparation {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SourcePreparation(backgroundScope, dispatcher, dispatcher)
    }
}
