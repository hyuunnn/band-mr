package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WaveformPeaksTest {

    @Test
    fun `막대별 최대 진폭을 남긴다`() {
        val ch = 2
        val frames = 10
        val samples = ShortArray(frames * ch)
        // 앞 5프레임은 약 0.25, 뒤 5프레임은 약 0.5
        for (i in 0 until 5) {
            samples[i * ch] = 8192
            samples[i * ch + 1] = 100
        }
        for (i in 5 until 10) {
            samples[i * ch] = 16384
            samples[i * ch + 1] = -200
        }
        val peaks = WaveformPeaks.fromInterleaved(samples, ch, bars = 2)
        assertEquals(2, peaks.size)
        assertEquals(8192 / 32768f, peaks[0], 1e-5f)
        assertEquals(16384 / 32768f, peaks[1], 1e-5f)
    }

    @Test
    fun `막대 인덱스는 구간 안에 머문다`() {
        assertEquals(0, WaveformPeaks.barIndex(0, 100, 4))
        assertEquals(3, WaveformPeaks.barIndex(99, 100, 4))
        assertEquals(0, WaveformPeaks.barIndex(0, 0, 4))
    }

    @Test
    fun `빈 입력은 빈 파형`() {
        assertEquals(0, WaveformPeaks.fromInterleaved(ShortArray(0), 2, 8).size)
        assertEquals(0, WaveformPeaks.fromInterleaved(ShortArray(4), 2, 0).size)
    }

    @Test
    fun `WAV에서 뽑은 피크가 샘플과 맞다`() {
        val f = File.createTempFile("peaks", ".wav")
        val frames = 200
        val data = ShortArray(frames * 2) { i ->
            if (i / 2 < 100) 4096 else 12288
        }
        WavWriter.create(f, 44100).use { it.writeShorts(data, data.size) }
        val peaks = WaveformPeaks.fromWav(f, bars = 2)
        f.delete()
        assertEquals(2, peaks.size)
        assertEquals(4096 / 32768f, peaks[0], 1e-5f)
        assertEquals(12288 / 32768f, peaks[1], 1e-5f)
        assertTrue(peaks[1] > peaks[0])
    }
}
