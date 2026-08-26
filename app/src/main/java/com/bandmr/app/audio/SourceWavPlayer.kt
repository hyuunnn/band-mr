package com.bandmr.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File

/**
 * AI OFF 모드 재생기: 원본 전체 믹스 WAV(44.1kHz 스테레오 PCM16)를 읽어
 * 실시간 [DspChain](제거 마스크)과 [PitchShifter](키 조절)를 적용해 AudioTrack으로 출력한다.
 *
 * 기기의 MediaCodec 비동기 디코딩 경로가 불안정한 환경이 있어(일부 Android 16 펌웨어),
 * 압축 원본을 스트리밍하지 않고 가져온 시점에 디코딩해 둔 WAV 캐시([MixCache])를 재생한다.
 */
class SourceWavPlayer(
    file: File,
    private val onEndedCallback: () -> Unit = {},
) {

    private val reader: WavReader = WavReader(file)
    val sampleRate: Int = reader.sampleRate
    val channels: Int = reader.channels

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

    @Volatile
    private var running = false

    /** 제거할 스템 비트마크 (Stem.bit 조합). 변경 시 DSP 상태 초기화 */
    @Volatile
    var muteMask: Int = 0
        set(value) {
            field = value
            rebuildChain()
        }

    @Volatile
    var semitones: Int = 0
        set(value) {
            if (field != value) {
                field = value
                shifter.semitones = value
            }
        }

    /** 보컬 제거 강도 0..1. 상태 리셋 없이 즉시 반영된다 */
    @Volatile
    var vocalStrength: Float = 1f
        set(value) {
            field = value
            chain.vocalStrength = value
        }

    private var shifter = newShifter(0)

    private var chain = newChain()

    val durationFrames: Long get() = reader.totalFrames

    fun positionFrames(): Long = framePos

    fun play() {
        if (reader.totalFrames == 0L) return
        if (framePos >= reader.totalFrames) framePos = 0
        synchronized(stateLock) {
            isPlaying = true
            stateLock.notifyAll()
        }
        startEngineIfNeeded()
    }

    fun pause() {
        synchronized(stateLock) {
            isPlaying = false
            track?.let { runCatching { it.pause(); it.flush() } }
        }
    }

    fun seekToFrame(frame: Long) {
        val f = frame.coerceIn(0, maxOf(0, reader.totalFrames))
        synchronized(stateLock) {
            framePos = f
            shifter = newShifter(semitones)
            // 스펙트럼 FIFO에 남은 이전 위치 잔여분 제거 (시크 잡음 방지)
            chain = newChain()
            track?.pause()
            track?.flush()
            if (isPlaying && reader.totalFrames > 0) track?.play()
        }
    }

    fun release() {
        stopEngine()
        runCatching { reader.close() }
    }

    // ---------- 내부 ----------

    private fun newChain(): DspChain =
        DspChain(sampleRate, channels).also {
            it.muteMask = muteMask
            it.vocalStrength = vocalStrength
        }

    private fun rebuildChain() {
        synchronized(stateLock) { chain = newChain() }
    }

    private fun newShifter(semi: Int): PitchShifter =
        PitchShifter().also { it.semitones = semi }

    private fun startEngineIfNeeded() {
        if (thread?.isAlive == true) return
        running = true
        thread = Thread({ loop() }, "SourceMix").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private val srcShort = ShortArray(CHUNK * 2)
    private val outFloat = FloatArray(CHUNK * 2)
    private val outShort = ShortArray(CHUNK * 2)

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        track = buildTrack()
        while (running) {
            val play: Boolean
            synchronized(stateLock) {
                play = isPlaying
                if (!play) stateLock.wait()
            }
            if (!running) break
            if (!play || track == null) continue

            val pos = framePos
            val total = reader.totalFrames
            if (pos >= total) {
                finish()
                continue
            }
            val got = try {
                reader.read(pos, srcShort, CHUNK)
            } catch (_: Exception) {
                -1
            }
            if (got <= 0) {
                finish()
                continue
            }
            val n = got * 2
            val sh = shifter
            var i = 0
            while (i < n) {
                sh.process(srcShort[i] / 32768f, srcShort[i + 1] / 32768f)
                outShort[i] = DspChain.clampShort(sh.outL)
                outShort[i + 1] = DspChain.clampShort(sh.outR)
                i += 2
            }
            // 제거 마스크가 있으면 실시간 DSP 적용 (스펙트럼 단계 지연 ~23ms는 내부 FIFO가 흡수)
            chain.processInPlace(outShort, n)
            val wrote = track?.write(outShort, 0, n, AudioTrack.WRITE_BLOCKING) ?: 0
            if (wrote < 0) break
            // 진행 중 시크가 끼어들었으면 스테일 값으로 덮어쓰지 않는다
            synchronized(stateLock) {
                if (framePos == pos) framePos += got
            }
            if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) track?.play()
        }
        track?.release()
        track = null
    }

    /** 트랙 종료 처리: DSP 파이프라인 잔여분(약 1블록)을 밀어낸 뒤 끝난다 */
    private fun finish() {
        val c = synchronized(stateLock) { chain }
        if (c.muteMask != 0) {
            c.drain { arr, n ->
                track?.write(arr, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        }
        synchronized(stateLock) { isPlaying = false }
        track?.pause()
        framePos = reader.totalFrames
        mainHandler.post { onEndedCallback() }
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
