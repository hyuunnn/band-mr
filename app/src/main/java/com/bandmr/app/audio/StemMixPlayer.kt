package com.bandmr.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.bandmr.app.data.Stem
import java.io.File

/**
 * AI 분리 완료 후 스템 WAV 4개를 동기 재생하며 스템별 게인(제거)과 피치를 적용하는 커스텀 믹서.
 */
class StemMixPlayer(private val onEndedCallback: () -> Unit = {}) {

    private val readers = arrayOfNulls<WavReader>(Stem.entries.size)
    private var sampleRate = 44100
    private var totalFrames = 0L

    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private val stateLock = Object()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var framePos = 0L
        private set

    /** 스템별 게인. muted면 0 */
    @Volatile
    var gains: FloatArray = FloatArray(Stem.entries.size) { 1f }
        set(value) {
            field = value.copyOf()
        }

    @Volatile
    var semitones = 0
        set(value) {
            if (field != value) {
                field = value
                shifter.semitones = value
            }
        }

    private var shifter = newShifter(0)

    fun load(files: Map<Stem, File>) {
        stopEngine()
        readers.forEachIndexed { i, r -> r?.close(); readers[i] = null }
        var sr = 44100
        var total = 0L
        files.forEach { (stem, file) ->
            if (file.exists()) {
                try {
                    val r = WavReader(file)
                    readers[stem.ordinal] = r
                    sr = r.sampleRate
                    total = maxOf(total, r.totalFrames)
                } catch (_: Exception) {
                }
            }
        }
        sampleRate = sr
        totalFrames = total
        framePos = 0
    }

    val durationFrames: Long get() = totalFrames

    fun positionFrames(): Long = framePos

    fun play() {
        if (totalFrames == 0L) return
        if (framePos >= totalFrames) framePos = 0
        synchronized(stateLock) {
            isPlaying = true
            stateLock.notifyAll()
        }
        startEngineIfNeeded()
    }

    fun pause() {
        synchronized(stateLock) {
            isPlaying = false
            // flush로 대기 중인 WRITE_BLOCKING write를 확실히 풀어준다 (스레드 정지 방지)
            track?.let { runCatching { it.pause(); it.flush() } }
        }
    }

    fun seekToFrame(frame: Long) {
        val f = frame.coerceIn(0, maxOf(0, totalFrames))
        synchronized(stateLock) {
            framePos = f
            shifter = newShifter(semitones)
            track?.pause()
            track?.flush()
            if (isPlaying && totalFrames > 0) startWriteLoopPlayback()
        }
    }

    fun release() {
        stopEngine()
        readers.forEachIndexed { i, r -> runCatching { r?.close() }; readers[i] = null }
    }

    // ---------- 내부 ----------

    private fun newShifter(semi: Int): PitchShifter =
        PitchShifter().also { it.semitones = semi }

    private fun startEngineIfNeeded() {
        if (thread?.isAlive == true) return
        running = true
        thread = Thread({ loop() }, "StemMix").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Volatile
    private var running = false

    private fun startWriteLoopPlayback() {
        track ?: return
        track?.play()
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 4)
            .build()
    }

    private val mixedFloat = FloatArray(CHUNK * 2)
    private val outShort = ShortArray(CHUNK * 2)
    private val stemShort = ShortArray(CHUNK * 2)

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        track = buildTrack()
        val nStems = Stem.entries.size
        while (running) {
            val play: Boolean
            synchronized(stateLock) {
                play = isPlaying
                if (!play) stateLock.wait()
            }
            if (!running) break
            if (!play || track == null) continue

            val pos = framePos
            if (pos >= totalFrames) {
                synchronized(stateLock) { isPlaying = false }
                track?.pause()
                mainHandler.post { onEndedCallback() }
                continue
            }
            java.util.Arrays.fill(mixedFloat, 0f)
            // 들리는 스템 중 가장 짧게 읽힌 프레임 수로 출력 길이 제한
            var minGot = Int.MAX_VALUE
            var anyAudible = false
            for (s in 0 until nStems) {
                val gain = gains[s]
                val reader = readers[s] ?: continue
                if (gain <= 0f) continue
                val got = reader.read(pos, stemShort, CHUNK)
                if (got <= 0) continue
                anyAudible = true
                if (got < minGot) minGot = got
                var i = 0
                while (i < got * 2) {
                    mixedFloat[i] += stemShort[i] / 32768f * gain
                    i++
                }
            }
            val remainingFrames = (totalFrames - pos).toInt()
            val framesToWrite = when {
                !anyAudible -> 0
                else -> minOf(minGot, remainingFrames)
            }
            if (framesToWrite <= 0) {
                synchronized(stateLock) { isPlaying = false }
                track?.pause()
                mainHandler.post { onEndedCallback() }
                continue
            }
            val sh = shifter
            var i = 0
            while (i < framesToWrite * 2) {
                sh.process(mixedFloat[i], mixedFloat[i + 1])
                outShort[i] = DspChain.clampShort(sh.outL)
                outShort[i + 1] = DspChain.clampShort(sh.outR)
                i += 2
            }
            val wrote = track?.write(outShort, 0, framesToWrite * 2, AudioTrack.WRITE_BLOCKING) ?: 0
            if (wrote < 0) break
            // 진행 중에 시크가 끼어들었으면(framePos 변경) 스테일 값으로 덮어쓰지 않는다
            synchronized(stateLock) {
                if (framePos == pos) framePos += framesToWrite
            }
            if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) track?.play()
        }
        track?.release()
        track = null
    }

    private fun stopEngine() {
        synchronized(stateLock) {
            isPlaying = false
            running = false
            stateLock.notifyAll()
        }
        thread?.join(500)
        thread = null
        track?.let {
            runCatching { it.pause(); it.flush(); it.release() }
        }
        track = null
    }

    companion object {
        private const val CHUNK = 2048
    }
}
