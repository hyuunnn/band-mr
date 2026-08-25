package com.bandmr.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** AI OFF 모드에서 UI가 갱신하는 제거 마스크 (Stem.bit 조합) */
object DspBus {
    @Volatile
    var muteMask: Int = 0
}

/**
 * ExoPlayer 재생 중 실시간으로 MR 제거 DSP를 적용하는 AudioProcessor.
 * PCM 16bit 전용.
 */
class MrAudioProcessor : BaseAudioProcessor() {

    private var sampleRate = 48000
    private var channels = 2
    private var chain: DspChain? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        chain = DspChain(sampleRate, channels).also { it.muteMask = DspBus.muteMask }
        return inputAudioFormat
    }

    override fun onFlush() {
        chain?.muteMask = DspBus.muteMask
        super.onFlush()
    }

    override fun onReset() {
        chain = null
        super.onReset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        val out = replaceOutputBuffer(remaining)
        val mask = DspBus.muteMask
        val c = chain
        if (mask == 0 || c == null) {
            // 패스스루
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
            return
        }
        val n = remaining / 2
        scratch.ensureCapacity(n)
        val arr = scratch.data
        inputBuffer.asShortBuffer().get(arr, 0, n)
        inputBuffer.position(inputBuffer.limit())
        c.processInPlace(arr, n)
        var i = 0
        while (i < n) {
            val v = arr[i].toInt()
            out.put(v.toByte())
            out.put((v shr 8).toByte())
            i++
        }
    }

    private val scratch = ShortBuf()

    private class ShortBuf(var data: ShortArray = ShortArray(4096)) {
        fun ensureCapacity(n: Int) {
            if (data.size < n) data = ShortArray(maxOf(n, data.size * 2))
        }
    }
}
