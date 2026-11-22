package com.bandmr.app.ui.player

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.audio.WavWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WaveformPreparationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `재생기가 캐시를 만들지 않는 AI ON도 파형 입력을 준비한다`() = runTest {
        val wav = File(tmp.root, "song.wav")
        val peaksFile = File(tmp.root, "song.peaks")
        var prepared = 0
        val peaks = prepareWaveformPeaks(wav, peaksFile, prepare = { prepared++; writeWav(wav) })
        assertEquals(1, prepared)
        assertTrue(peaks.isNotEmpty())
        assertTrue(peaksFile.exists())
    }

    @Test
    fun `이미 준비된 캐시는 다시 디코딩하지 않는다`() = runTest {
        val wav = File(tmp.root, "song.wav")
        writeWav(wav)
        val peaks = prepareWaveformPeaks(wav, File(tmp.root, "song.peaks"),
            prepare = { error("중복 디코딩") },
        )
        assertTrue(peaks.isNotEmpty())
    }

    @Test
    fun `입력 준비가 실패하면 파형을 만들지 않고 실패를 전달한다`() = runTest {
        val peaksFile = File(tmp.root, "song.peaks")
        try {
            prepareWaveformPeaks(File(tmp.root, "song.wav"), peaksFile,
                prepare = { throw IOException("source unavailable") },
            )
            fail("준비 실패가 전달돼야 한다")
        } catch (expected: IOException) {
            assertFalse(peaksFile.exists())
        }
    }

    private fun writeWav(file: File) {
        val pcm = ShortArray(4096 * 2) { (it % 12000).toShort() }
        WavWriter.create(file, PIPELINE_SAMPLE_RATE).use { it.writeShorts(pcm, pcm.size) }
    }
}
