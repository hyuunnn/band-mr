package com.bandmr.app.separation

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 임의 포맷 오디오 파일 → 44.1kHz 스테레오 PCM16 raw 파일 디코더 */
object AudioDecode {

    const val TARGET_SR = 44_100

    /**
     * @return 디코딩된 총 프레임 수 (44.1k 기준)
     */
    fun decodeToRaw44k(
        context: Context,
        uri: Uri,
        outFile: File,
        onProgress: (Float) -> Unit = {},
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val out = BufferedOutputStream(FileOutputStream(outFile), 256 * 1024)
        val leBuf = ThreadLocal.withInitial { ByteArray(8192) }
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) error("오디오 트랙을 찾을 수 없습니다")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            // PCM16 출력 요청
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var inSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val resampler = LinearResampler(inSr, TARGET_SR)

            var sawInputEos = false
            var sawOutputEos = false
            var outFrames = 0L
            val info = MediaCodec.BufferInfo()

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib: ByteBuffer = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(ib, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        inSr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        resampler.reset(inSr, TARGET_SR)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = ShortArray(ob.remaining() / 2)
                        for (i in shorts.indices) shorts[i] = ob.short
                        codec.releaseOutputBuffer(outIdx, false)

                        outFrames += emitStereo44k(shorts, inCh, resampler, out, leBuf.get())
                        if (durationUs > 0 && info.presentationTimeUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        sawOutputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }
            return outFrames
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { out.close() }
        }
    }

    /**
     * 디코딩된 interleaved shorts(ch채널)를 리샘플+스테레오 변환해 raw에 기록.
     * @return 기록된 출력 프레임 수
     */
    private fun emitStereo44k(
        data: ShortArray,
        channels: Int,
        resampler: LinearResampler,
        out: BufferedOutputStream,
        scratch: ByteArray,
    ): Long {
        val framesIn = data.size / channels.coerceAtLeast(1)
        if (framesIn == 0) return 0
        val l = FloatArray(framesIn)
        val r = FloatArray(framesIn)
        when (channels) {
            1 -> for (i in 0 until framesIn) {
                val v = data[i] / 32768f
                l[i] = v; r[i] = v
            }
            2 -> for (i in 0 until framesIn) {
                l[i] = data[i * 2] / 32768f
                r[i] = data[i * 2 + 1] / 32768f
            }
            else -> for (i in 0 until framesIn) {
                var sumL = 0f
                var sumR = 0f
                for (c in 0 until channels) {
                    val v = data[i * channels + c] / 32768f
                    if (c % 2 == 0) sumL += v else sumR += v
                }
                val half = (channels + 1) / 2f
                l[i] = sumL / half; r[i] = sumR / (channels - half)
            }
        }
        return resampler.process(l, r, framesIn) { lo, ro ->
            writeShortLe(out, scratch, DspChainPublic.clamp(lo))
            writeShortLe(out, scratch, DspChainPublic.clamp(ro))
        }
    }

    private fun writeShortLe(out: BufferedOutputStream, scratch: ByteArray, v: Short) {
        scratch[0] = (v.toInt() and 0xFF).toByte()
        scratch[1] = ((v.toInt() shr 8) and 0xFF).toByte()
        out.write(scratch, 0, 2)
    }

    /** 스트리밍 선형 보간 리샘플러 (블록 경계 처리 포함) */
    class LinearResampler(inRate: Int, outRate: Int) {
        private var step = inRate.toDouble() / outRate
        private var pos = 0.0
        private var base = 0L
        private var len = 0
        private var curL = FloatArray(0)
        private var curR = FloatArray(0)
        private var hist1L = 0f; private var hist1R = 0f
        private var hist2L = 0f; private var hist2R = 0f
        private var hasHist = false

        fun reset(inRate: Int, outRate: Int) {
            step = inRate.toDouble() / outRate
            pos = 0.0
            base = 0L
            hasHist = false
        }

        fun process(l: FloatArray, r: FloatArray, n: Int, sink: (Float, Float) -> Unit): Long {
            curL = l; curR = r
            base = baseOfNextBlock
            len = n
            baseOfNextBlock += n
            var emitted = 0L
            while (pos < base + n - 1) {
                val i0 = pos.toLong()
                val frac = (pos - i0).toFloat()
                val a = sampleAt(i0)
                val b = sampleAt(i0 + 1)
                sink(a.first + (b.first - a.first) * frac, a.second + (b.second - a.second) * frac)
                pos += step
                emitted++
            }
            // 블록 말미 2프레임 히스토리 저장
            if (n >= 2) {
                hist2L = l[n - 2]; hist2R = r[n - 2]
                hist1L = l[n - 1]; hist1R = r[n - 1]
                hasHist = true
            }
            return emitted
        }

        /** 마지막 블록 처리 후 남은 출력 샘플 플러시 */
        fun flush(sink: (Float, Float) -> Unit): Long {
            if (!hasHist || len == 0) return 0
            var emitted = 0L
            while (pos <= base + len - 1) {
                val i0 = pos.toLong()
                val frac = (pos - i0).toFloat()
                val a = sampleAt(i0)
                val b = if (i0 + 1 > base + len - 1) 0f to 0f else sampleAt(i0 + 1)
                sink(a.first + (b.first - a.first) * frac, a.second + (b.second - a.second) * frac)
                pos += step
                emitted++
            }
            return emitted
        }

        private fun sampleAt(idx: Long): Pair<Float, Float> {
            return if (idx >= base) {
                val i = (idx - base).toInt()
                curL[i] to curR[i]
            } else {
                when (idx - (base - 1)) {
                    0L -> hist1L to hist1R
                    -1L -> hist2L to hist2R
                    else -> 0f to 0f
                }
            }
        }

        private var baseOfNextBlock = 0L
    }
}

/** AudioDecode 내부에서 사용하는 클램프 모음 (패키지 공개용) */
internal object DspChainPublic {
    fun clamp(v: Float): Short =
        (v.coerceIn(-1f, 0.9999f) * 32767f).toInt().toShort()
}
