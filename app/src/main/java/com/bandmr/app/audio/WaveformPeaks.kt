package com.bandmr.app.audio

import java.io.File

/**
 * 시크바용 개요 파형. 원본 WAV를 한 번 훑어 막대별 최대 진폭만 남긴다.
 * 전체 PCM을 메모리에 올리지 않는다.
 */
object WaveformPeaks {
    const val DEFAULT_BARS = 480

    fun fromWav(file: File, bars: Int = DEFAULT_BARS): FloatArray {
        if (bars <= 0 || !file.exists()) return FloatArray(0)
        WavReader(file).use { reader ->
            return fromReader(reader, bars)
        }
    }

    internal fun fromReader(reader: WavReader, bars: Int): FloatArray {
        val total = reader.totalFrames
        val ch = reader.channels.coerceAtLeast(1)
        if (total <= 0L || bars <= 0) return FloatArray(0)
        val peaks = FloatArray(bars)
        val chunk = 8192
        val buf = ShortArray(chunk * ch)
        var frame = 0L
        while (frame < total) {
            val got = reader.read(frame, buf, chunk)
            if (got <= 0) break
            var i = 0
            while (i < got) {
                putPeak(peaks, barIndex(frame + i, total, bars), frameAbsPeak(buf, i, ch))
                i++
            }
            frame += got
        }
        return peaks
    }

    /** interleaved PCM16 → 막대 피크. 테스트와 [fromReader]가 같은 식을 쓴다. */
    internal fun fromInterleaved(samples: ShortArray, channels: Int, bars: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = samples.size / ch
        if (frames <= 0 || bars <= 0) return FloatArray(0)
        val peaks = FloatArray(bars)
        var i = 0
        while (i < frames) {
            putPeak(peaks, barIndex(i.toLong(), frames.toLong(), bars), frameAbsPeak(samples, i, ch))
            i++
        }
        return peaks
    }

    internal fun barIndex(frame: Long, totalFrames: Long, bars: Int): Int {
        if (totalFrames <= 0L || bars <= 0) return 0
        return (frame * bars / totalFrames).toInt().coerceIn(0, bars - 1)
    }

    internal fun frameAbsPeak(samples: ShortArray, frameIndex: Int, channels: Int): Float {
        var max = 0
        val base = frameIndex * channels
        var c = 0
        while (c < channels) {
            val s = samples[base + c].toInt()
            val a = if (s < 0) -s else s
            if (a > max) max = a
            c++
        }
        return max / 32768f
    }

    private fun putPeak(peaks: FloatArray, bar: Int, peak: Float) {
        if (peak > peaks[bar]) peaks[bar] = peak
    }
}
