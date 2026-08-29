package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class WaveformPeaksTest {

    @Test
    fun `막대별 RMS를 남긴다`() {
        val ch = 2
        val frames = 10
        val samples = ShortArray(frames * ch)
        // 앞 5프레임은 8192/100, 뒤 5프레임은 16384/-200
        for (i in 0 until 5) {
            samples[i * ch] = 8192
            samples[i * ch + 1] = 100
        }
        for (i in 5 until 10) {
            samples[i * ch] = 16384
            samples[i * ch + 1] = -200
        }
        val peaks = WaveformPeaks.fromInterleaved(samples, ch, bars = 2)
        val expected0 = sqrt(5.0 * (8192.0 * 8192 + 100.0 * 100) / 10.0) / 32768.0
        val expected1 = sqrt(5.0 * (16384.0 * 16384 + 200.0 * 200) / 10.0) / 32768.0
        assertEquals(2, peaks.size)
        assertEquals(expected0.toFloat(), peaks[0], 1e-5f)
        assertEquals(expected1.toFloat(), peaks[1], 1e-5f)
    }

    @Test
    fun `첨두가 있는 구간은 RMS가 피크보다 작다`() {
        // 16384, 0 반복: 피크는 0.5, RMS는 0.5/√2
        val samples = ShortArray(8) { if (it % 2 == 0) 16384 else 0 }
        val peaks = WaveformPeaks.fromInterleaved(samples, 1, bars = 1)
        assertEquals(16384f / 32768f / sqrt(2f), peaks[0], 1e-5f)
        assertTrue(peaks[0] < 16384f / 32768f)
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
    fun `WAV에서 뽑은 막대는 최댓값 기준으로 정규화된다`() {
        val f = File.createTempFile("peaks", ".wav")
        val frames = 200
        val data = ShortArray(frames * 2) { i ->
            if (i / 2 < 100) 4096 else 12288
        }
        // 일정 진폭 구간의 RMS는 그 진폭과 같다 → 정규화하면 최댓값 막대가 1.0
        WavWriter.create(f, 44100).use { it.writeShorts(data, data.size) }
        val peaks = WaveformPeaks.fromWav(f, bars = 2)
        f.delete()
        assertEquals(2, peaks.size)
        assertEquals(1f, peaks[1], 1e-5f)
        assertEquals(4096f / 12288f, peaks[0], 1e-5f)
        assertTrue(peaks[1] > peaks[0])
    }

    @Test
    fun `정규화는 최댓값을 1로 맞추고 비율을 유지한다`() {
        val peaks = floatArrayOf(0.25f, 0f, 0.5f)
        WaveformPeaks.normalize(peaks)
        assertEquals(0.5f, peaks[0], 1e-6f)
        assertEquals(0f, peaks[1], 1e-6f)
        assertEquals(1f, peaks[2], 1e-6f)
    }

    @Test
    fun `무음 파형은 정규화해도 0으로 유지된다`() {
        val peaks = FloatArray(4)
        WaveformPeaks.normalize(peaks)
        for (p in peaks) assertEquals(0f, p, 1e-6f)
    }

    @Test
    fun `모든 막대가 최대치여도 윤곽이 1로 수렴한다`() {
        val peaks = WaveformPeaks.fromInterleaved(ShortArray(20) { 32767 }, 2, 4)
        WaveformPeaks.normalize(peaks)
        for (p in peaks) assertEquals(1f, p, 1e-6f)
    }
}
