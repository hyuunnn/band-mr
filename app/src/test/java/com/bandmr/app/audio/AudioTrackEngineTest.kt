package com.bandmr.app.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AudioTrackEngineTest {
    @Test(timeout = 10_000)
    fun `키 요청은 오디오 스레드가 다음 청크를 준비할 때 적용한다`() {
        val engine = Engine()
        val input = ShortArray(2048) { ((it * 7919L % 60001) - 30000).toShort() }
        val rendered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val output = AtomicReference<ShortArray>()
        val failure = AtomicReference<Throwable>()
        engine.semitones = 5
        val worker = Thread {
            try {
                engine.render(input)
                rendered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                output.set(engine.render(input))
            } catch (t: Throwable) {
                failure.set(t)
                rendered.countDown()
            }
        }.apply { isDaemon = true }
        try {
            worker.start()
            assertTrue(rendered.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            val phase = engine.pitchPhase()
            assertTrue(phase != 0f)
            engine.semitones = 12
            engine.semitones = -5
            assertEquals(-5, engine.semitones)
            assertEquals(5, engine.appliedSemitones())
            assertEquals(phase, engine.pitchPhase(), 0f)
            resume.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            failure.get()?.let { throw it }
            assertEquals(-5, engine.appliedSemitones())

            val reference = PitchShifter().also { it.semitones = 5 }
            reference.renderTo(input, input.size / 2, ShortArray(input.size))
            reference.semitones = -5
            val expected = ShortArray(input.size)
            reference.renderTo(input, input.size / 2, expected)
            assertArrayEquals(expected, output.get())
        } finally {
            resume.countDown()
            worker.join(5000)
        }
    }

    @Test
    fun `청크 경계에서 바꾼 키의 PCM16과 float 출력은 기존 피치 계산과 바이트 동일하다`() {
        for (floatInput in listOf(false, true)) {
            val engine = Engine()
            val reference = PitchShifter()
            val keys = (-12..12).toList() + listOf(0, 7, 7, -5, 0)
            for ((index, semi) in keys.withIndex()) {
                val frames = 1024 + index * 137 % 1025
                val input = ShortArray(frames * 2) {
                    (((it + index * 4096L) * 7919 % 60001) - 30000).toShort()
                }
                engine.semitones = semi
                reference.semitones = semi
                val expected = ShortArray(input.size)
                val actual = if (floatInput) {
                    val floats = FloatArray(input.size) { input[it] / 32768f }
                    reference.renderTo(floats, frames, expected)
                    engine.render(floats)
                } else {
                    reference.renderTo(input, frames, expected)
                    engine.render(input)
                }
                assertArrayEquals("float=$floatInput chunk=$index key=$semi", expected, actual)
                assertEquals("키 변경만으로 제거 DSP를 리셋하면 안 된다", 0, engine.resetCalls)
            }
        }
    }

    @Test
    fun `시크와 키 변경이 겹쳐도 새 키로 리셋된 출력을 낸다`() {
        val engine = Engine()
        val input = ShortArray(4096) { ((it * 7919L % 60001) - 30000).toShort() }
        engine.semitones = 7
        engine.render(input)
        engine.semitones = -3
        engine.seekToFrame(2048)
        val actual = engine.render(input)
        val reference = PitchShifter().also { it.semitones = -3 }
        val expected = ShortArray(input.size)
        reference.renderTo(input, input.size / 2, expected)
        assertArrayEquals(expected, actual)
        assertEquals(1, engine.resetCalls)
        engine.render(input)
        assertEquals(1, engine.resetCalls)
    }

    @Test
    fun `출력 오류는 곡 위치와 DSP를 건드리지 않고 재생 중단을 알린다`() {
        var ended = 0
        val engine = Engine { ended++ }
        engine.setPosition(123)
        engine.setFlag("running", true)
        engine.setFlag("isPlaying", true)

        engine.failOutput()

        assertFalse(engine.isPlaying)
        assertEquals(123L, engine.positionFrames())
        assertEquals(0, engine.finishCalls)
        assertEquals(0, ended)
        assertFalse(engine.await())
        engine.deliver()
        assertEquals(1, ended)
    }

    @Test
    fun `출력 오류 뒤 해제된 엔진은 늦은 중단 통보를 보내지 않는다`() {
        var ended = 0
        val engine = Engine { ended++ }
        engine.setFlag("isPlaying", true)
        engine.failOutput()
        engine.release()
        engine.deliver()
        assertEquals(0, ended)
    }

    @Test
    fun `출력 오류 뒤 재시작하면 이전 중단 통보는 재생을 덮지 않는다`() {
        var ended = 0
        val engine = Engine { ended++ }
        engine.setFlag("isPlaying", true)
        engine.failOutput()
        engine.setFlag("isPlaying", true)
        engine.deliver()
        assertTrue(engine.isPlaying)
        assertEquals(0, ended)
    }

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
        var finishCalls = 0
        var resetCalls = 0
        override val totalFrames = 44_100L
        override val sampleRate = PIPELINE_SAMPLE_RATE
        override fun renderChunk(posFrames: Long, request: Int) = 0
        override fun closeSources() {}
        override fun resetProcessors() { resetCalls++ }
        override fun postToMain(callback: () -> Unit) { callbacks.addLast(callback) }
        override fun finish() { finishCalls++; super.finish() }
        val lock get() = stateLock
        private fun shifter() = AudioTrackEngine::class.java.getDeclaredField("shifter")
            .apply { isAccessible = true }.get(this) as PitchShifter
        fun appliedSemitones() = shifter().semitones
        fun pitchPhase() = PitchShifter::class.java.getDeclaredField("phase")
            .apply { isAccessible = true }.getFloat(shifter())
        private fun prepareChunk() = synchronized(stateLock) {
            AudioTrackEngine::class.java.getDeclaredMethod("prepareProcessors")
                .apply { isAccessible = true }.invoke(this)
        }
        fun render(input: ShortArray): ShortArray {
            prepareChunk()
            pitchShortToOut(input, input.size / 2)
            return outShort.copyOf(input.size)
        }
        fun render(input: FloatArray): ShortArray {
            prepareChunk()
            pitchFloatToOut(input, input.size / 2)
            return outShort.copyOf(input.size)
        }
        fun setPosition(frame: Long) { framePos = frame }
        fun failOutput() {
            AudioTrackEngine::class.java.getDeclaredMethod("onOutputError")
                .apply { isAccessible = true }.invoke(this)
        }
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
