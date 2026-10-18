package com.bandmr.app.ui.player

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.audio.CacheReadyGate
import com.bandmr.app.audio.SourcePreparation
import com.bandmr.app.audio.WavWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WaveformPreparationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `원본 재생의 캐시 준비가 실패해도 파형이 다시 디코딩하지 않는다`() = runTest {
        val wav = File(tmp.root, "song.wav")
        val ready = CacheReadyGate()
        val finishDecode = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preparation = SourcePreparation(backgroundScope, dispatcher, dispatcher)
        var attempts = 0
        val waveform = async(start = CoroutineStart.UNDISPATCHED) {
            prepareWaveformPeaks(wav, File(tmp.root, "song.peaks"),
                prepare = null,
                awaitReady = { ready.await(1L) { wav.exists() } },
            )
        }
        preparation.start({
            attempts++
            finishDecode.await()
            throw IOException("source unavailable")
        }) { ok ->
            assertFalse(ok)
            // 화면은 준비 실패 상태를 관찰하면 파형 대기를 취소한다.
            waveform.cancel()
        }
        runCurrent()
        assertEquals(1, attempts)
        assertFalse(waveform.isCompleted)
        finishDecode.complete(Unit)
        runCurrent()
        waveform.join()

        assertTrue(waveform.isCancelled)
        assertEquals(1, attempts)
        assertFalse(wav.exists())
        assertFalse(File(tmp.root, "song.peaks").exists())
    }

    @Test
    fun `원본 재생의 캐시가 완성되면 대기 중인 파형을 계산한다`() = runTest {
        val wav = File(tmp.root, "song.wav")
        val peaksFile = File(tmp.root, "song.peaks")
        val ready = CacheReadyGate()
        val waveform = async(start = CoroutineStart.UNDISPATCHED) {
            prepareWaveformPeaks(wav, peaksFile,
                prepare = null,
                awaitReady = { ready.await(1L) { wav.exists() } },
            )
        }
        try {
            runCurrent()
            assertFalse(waveform.isCompleted)
            assertFalse(wav.exists())
            writeWav(wav)
            ready.signal(1L)
            assertTrue(waveform.await().isNotEmpty())
            assertTrue(peaksFile.exists())
        } finally {
            waveform.cancelAndJoin()
        }
    }

    @Test
    fun `재생기가 캐시를 만들지 않는 AI ON도 파형 입력을 준비한다`() = runTest {
        val wav = File(tmp.root, "song.wav")
        val peaksFile = File(tmp.root, "song.peaks")
        val events = mutableListOf<String>()
        val peaks = prepareWaveformPeaks(wav, peaksFile,
            prepare = { events += "prepare"; writeWav(wav) },
            awaitReady = { assertTrue(wav.exists()); events += "ready" },
        )
        assertEquals(listOf("prepare", "ready"), events)
        assertTrue(peaks.isNotEmpty())
        assertTrue(peaksFile.exists())
    }

    @Test
    fun `이미 준비된 캐시는 다시 디코딩하지 않는다`() = runTest {
        val wav = File(tmp.root, "song.wav")
        writeWav(wav)
        val peaks = prepareWaveformPeaks(wav, File(tmp.root, "song.peaks"),
            prepare = { error("중복 디코딩") }, awaitReady = {},
        )
        assertTrue(peaks.isNotEmpty())
    }

    @Test
    fun `입력 준비가 실패하면 완료 신호를 기다리지 않는다`() = runTest {
        var waited = false
        try {
            prepareWaveformPeaks(File(tmp.root, "song.wav"), File(tmp.root, "song.peaks"),
                prepare = { throw IOException("source unavailable") },
                awaitReady = { waited = true },
            )
            fail("준비 실패가 전달돼야 한다")
        } catch (expected: IOException) {
            assertFalse(waited)
        }
    }

    private fun writeWav(file: File) {
        val pcm = ShortArray(4096 * 2) { (it % 12000).toShort() }
        WavWriter.create(file, PIPELINE_SAMPLE_RATE).use { it.writeShorts(pcm, pcm.size) }
    }
}
