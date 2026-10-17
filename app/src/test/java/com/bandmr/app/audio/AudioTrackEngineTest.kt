package com.bandmr.app.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AudioTrackEngineTest {
    @Test
    fun `다시 재생한 뒤 도착한 이전 종료 콜백은 무시한다`() {
        var ended = 0
        val engine = Engine { ended++ }
        engine.end()
        engine.setFlag("isPlaying", true) // 메인 큐가 실행되기 전에 재시작한 순서
        engine.deliver()
        assertEquals(0, ended)
        assertTrue(engine.isPlaying)
    }

    @Test
    fun `정상 곡 끝은 알리고 해제 뒤 늦게 도착한 콜백은 무시한다`() {
        var ended = 0
        val engine = Engine { ended++ }
        engine.end()
        engine.deliver()
        assertEquals(1, ended)
        engine.end()
        engine.release()
        engine.deliver()
        assertEquals(1, ended)
    }

    @Test(timeout = 5000)
    fun `종료 통지가 대기 진입보다 먼저 지나가도 스레드는 끝난다`() {
        val engine = Engine()
        engine.setFlag("running", true)
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { result.set(engine.await()) } catch (t: Throwable) { failure.set(t) }
        }.apply { isDaemon = true }
        try {
            synchronized(engine.lock) {
                worker.start()
                awaitState(worker, Thread.State.BLOCKED)
                // 이 통지가 끝난 뒤에야 worker가 모니터를 얻는다.
                engine.stop()
            }
            worker.join(1000)
            assertFalse("종료 뒤 wait에 남아 있다", worker.isAlive)
            assertNull(failure.get())
            assertEquals(false, result.get())
        } finally {
            engine.stop()
            worker.join(1000)
        }
    }

    @Test(timeout = 5000)
    fun `일시정지 대기는 재개 통지를 받으면 재생을 허용한다`() {
        val engine = Engine()
        engine.setFlag("running", true)
        val result = AtomicReference<Boolean>()
        val worker = Thread { result.set(engine.await()) }.apply { isDaemon = true }
        try {
            worker.start()
            awaitState(worker, Thread.State.WAITING)
            synchronized(engine.lock) {
                engine.setFlag("isPlaying", true)
                engine.lock.notifyAll()
            }
            worker.join(1000)
            assertFalse(worker.isAlive)
            assertEquals(true, result.get())
        } finally {
            engine.stop()
            worker.join(1000)
        }
    }

    private fun awaitState(thread: Thread, state: Thread.State) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (thread.state != state && System.nanoTime() < deadline) Thread.yield()
        assertEquals(state, thread.state)
    }

    private class Engine(onEnded: () -> Unit = {}) : AudioTrackEngine("test", onEnded) {
        private val callbacks = ArrayDeque<() -> Unit>()
        override val totalFrames = 1L
        override val sampleRate = PIPELINE_SAMPLE_RATE
        override fun renderChunk(posFrames: Long, request: Int) = 0
        override fun closeSources() {}
        override fun postToMain(callback: () -> Unit) { callbacks.addLast(callback) }
        val lock get() = stateLock
        fun end() = finish()
        fun deliver() = callbacks.removeFirst().invoke()
        fun stop() = stopEngine()
        fun setFlag(name: String, value: Boolean) {
            AudioTrackEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(this, value)
        }
        fun await(): Boolean = AudioTrackEngine::class.java.getDeclaredMethod("awaitPlayback")
            .apply { isAccessible = true }.invoke(this) as Boolean
    }
}
