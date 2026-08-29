package com.bandmr.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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


/**
 * 파형 막대 캐시(mixcache/<songId>.peaks) 검증.
 * 플레이어 재진입마다 WAV 전체(수십 MB)를 다시 훑지 않기 위한 장치라,
 * "캐시 값이 직접 계산과 같다"와 "규격이 어긋난 캐시는 무시한다"를 함께 고정한다.
 */
class WaveformPeaksCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wav(frames: Int = 400, amp: Int = 8192): File {
        val f = tmp.newFile("src_${frames}_$amp.wav")
        val data = ShortArray(frames * 2) { i -> if (i / 2 < frames / 2) amp.toShort() else (amp * 2).toShort() }
        WavWriter.create(f, 44100).use { it.writeShorts(data, data.size) }
        return f
    }

    @Test
    fun `캐시 값은 직접 계산과 같고 캐시 파일이 생긴다`() {
        val src = wav()
        val cache = File(tmp.root, "1.peaks")
        val expected = WaveformPeaks.fromWav(src, bars = 8)

        val first = WaveformPeaks.fromWavCached(src, cache, bars = 8)
        assertTrue("캐시 파일이 만들어져야 한다", cache.isFile)
        assertArrayEquals(expected, first, 0f)

        // 두 번째 호출은 캐시에서 읽는다 — 값이 같아야 한다
        assertArrayEquals(expected, WaveformPeaks.fromWavCached(src, cache, bars = 8), 0f)
    }

    @Test
    fun `두 번째 호출은 원본을 다시 훑지 않고 캐시를 읽는다`() {
        val src = wav()
        val cache = File(tmp.root, "2.peaks")
        WaveformPeaks.fromWavCached(src, cache, bars = 8)

        // 캐시 파일의 막대 값만 표식으로 바꿔 둔다(헤더는 유효 그대로).
        // 다시 훑는다면 원래 값이 나오고, 캐시를 읽는다면 표식이 나온다.
        val marker = FloatArray(8) { 0.125f * (it + 1) }
        val bytes = cache.readBytes()
        java.nio.ByteBuffer.wrap(bytes).apply {
            position(HEADER_BYTES)
            for (v in marker) putFloat(v)
        }
        cache.writeBytes(bytes)

        assertArrayEquals(marker, WaveformPeaks.fromWavCached(src, cache, bars = 8), 0f)
    }

    @Test
    fun `막대 수가 달라지면 캐시를 무시하고 다시 계산한다`() {
        val src = wav()
        val cache = File(tmp.root, "3.peaks")
        WaveformPeaks.fromWavCached(src, cache, bars = 8)

        val bars16 = WaveformPeaks.fromWavCached(src, cache, bars = 16)
        assertEquals(16, bars16.size)
        assertArrayEquals(WaveformPeaks.fromWav(src, bars = 16), bars16, 0f)
    }

    @Test
    fun `원본 크기가 달라지면 캐시를 무시한다`() {
        val cache = File(tmp.root, "4.peaks")
        val shortSrc = wav(frames = 400)
        val old = WaveformPeaks.fromWavCached(shortSrc, cache, bars = 8)

        val longSrc = wav(frames = 1200, amp = 4096)
        val fresh = WaveformPeaks.fromWavCached(longSrc, cache, bars = 8)
        assertArrayEquals(WaveformPeaks.fromWav(longSrc, bars = 8), fresh, 0f)
        assertEquals(old.size, fresh.size)
    }

    @Test
    fun `손상된 캐시는 조용히 무시하고 다시 계산한다`() {
        val src = wav()
        val cache = File(tmp.root, "5.peaks")
        val expected = WaveformPeaks.fromWav(src, bars = 8)

        // 길이는 맞지만 내용이 쓰레기
        cache.writeBytes(ByteArray(HEADER_BYTES + 8 * 4) { 0x5A })
        assertArrayEquals(expected, WaveformPeaks.fromWavCached(src, cache, bars = 8), 0f)

        // 길이가 짧게 잘린 경우
        cache.writeBytes(ByteArray(10))
        assertArrayEquals(expected, WaveformPeaks.fromWavCached(src, cache, bars = 8), 0f)
    }

    @Test
    fun `원본이 없으면 캐시를 만들지 않는다`() {
        val cache = File(tmp.root, "6.peaks")
        val missing = File(tmp.root, "nope.wav")
        assertEquals(0, WaveformPeaks.fromWavCached(missing, cache, bars = 8).size)
        assertFalse(cache.exists())
    }

    private companion object {
        /** magic + version + bars + sourceBytes */
        const val HEADER_BYTES = 4 + 4 + 4 + 8
    }
}
