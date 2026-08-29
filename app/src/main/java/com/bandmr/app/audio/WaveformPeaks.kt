package com.bandmr.app.audio

import java.io.File
import kotlin.math.sqrt

/**
 * 시크바용 개요 파형. 원본 WAV를 한 번 훑어 막대별 RMS를 남긴다.
 * 전체 PCM을 메모리에 올리지 않는다.
 *
 * 막대 피크 대신 RMS를 쓰는 이유: 마스터링이 강한 음원은 막대 피크가 거의
 * 전부 풀스케일에 붙아 파형이 직사각형이 된다. RMS는 구간별 실제 크기
 * 차이를 남겨 파형 윤곽이 보인다. 결과는 곡 내 최댓값 기준 정규화(표시 전용).
 */
object WaveformPeaks {
    const val DEFAULT_BARS = 480

    fun fromWav(file: File, bars: Int = DEFAULT_BARS): FloatArray {
        if (bars <= 0 || !file.exists()) return FloatArray(0)
        WavReader(file).use { reader ->
            val peaks = fromReader(reader, bars)
            normalize(peaks)
            return peaks
        }
    }

    internal fun fromReader(reader: WavReader, bars: Int): FloatArray {
        val total = reader.totalFrames
        val ch = reader.channels.coerceAtLeast(1)
        if (total <= 0L || bars <= 0) return FloatArray(0)
        val sumSq = DoubleArray(bars)
        val counts = LongArray(bars)
        val chunk = 8192
        val buf = ShortArray(chunk * ch)
        var frame = 0L
        while (frame < total) {
            val got = reader.read(frame, buf, chunk)
            if (got <= 0) break
            var i = 0
            while (i < got) {
                accumulateFrame(sumSq, counts, barIndex(frame + i, total, bars), buf, i, ch)
                i++
            }
            frame += got
        }
        return rmsPeaks(sumSq, counts, bars)
    }

    /** interleaved PCM16 → 막대 RMS. 테스트와 [fromReader]가 같은 식을 쓴다. */
    internal fun fromInterleaved(samples: ShortArray, channels: Int, bars: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val frames = samples.size / ch
        if (frames <= 0 || bars <= 0) return FloatArray(0)
        val sumSq = DoubleArray(bars)
        val counts = LongArray(bars)
        var i = 0
        while (i < frames) {
            accumulateFrame(sumSq, counts, barIndex(i.toLong(), frames.toLong(), bars), samples, i, ch)
            i++
        }
        return rmsPeaks(sumSq, counts, bars)
    }

    internal fun barIndex(frame: Long, totalFrames: Long, bars: Int): Int {
        if (totalFrames <= 0L || bars <= 0) return 0
        return (frame * bars / totalFrames).toInt().coerceIn(0, bars - 1)
    }

    /** 막대 값을 곡 내 최댓값 기준 0~1로 정규화. 무음(max 0)이면 그대로 둔다. */
    internal fun normalize(peaks: FloatArray) {
        var max = 0f
        for (p in peaks) if (p > max) max = p
        if (max <= 0f) return
        for (i in peaks.indices) peaks[i] /= max
    }

    private fun accumulateFrame(
        sumSq: DoubleArray,
        counts: LongArray,
        bar: Int,
        samples: ShortArray,
        frameIndex: Int,
        channels: Int,
    ) {
        val base = frameIndex * channels
        var c = 0
        while (c < channels) {
            val s = samples[base + c].toDouble()
            sumSq[bar] += s * s
            c++
        }
        counts[bar] += channels
    }

    private fun rmsPeaks(sumSq: DoubleArray, counts: LongArray, bars: Int): FloatArray {
        val peaks = FloatArray(bars)
        for (b in 0 until bars) {
            peaks[b] = if (counts[b] > 0L) (sqrt(sumSq[b] / counts[b]) / 32768.0).toFloat() else 0f
        }
        return peaks
    }
}
