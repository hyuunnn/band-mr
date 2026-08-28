package com.bandmr.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * AudioTrack 기반 WAV 재생 엔진의 공통 골격.
 * [SourceWavPlayer](AI OFF 원본+DSP)와 [StemMixPlayer](AI ON 스템 믹서)가 공유하는
 * 재생 스레드 루프·A-B 랩·시크/배속 상태 관리를 한곳에 모아, 동시성 수정이
 * 양쪽에 이중으로 반영되어야 하는 중복을 없앤다.
 *
 * 서브클래스가 구현할 것:
 *  - [totalFrames] / [sampleRate]: 곡 길이와 출력 샘플레이트
 *  - [renderChunk]: [AudioTrackEngine.CHUNK] 이하 프레임을 [outShort]에 렌더
 *  - [closeSources]: 보유한 WAV 리더 정리
 *
 * 스레드 규약: track/running/isPlaying/framePos는 오디오 스레드와 UI 스레드가 함께
 * 건드리므로 @Volatile로 가시성을 보장하고, framePos 갱신은 stateLock 아래에서
 * 기존 값 비교로만 한다(진행 중 시크가 끼어들면 스테일 값으로 덮어쓰지 않음).
 */
abstract class AudioTrackEngine(
    private val threadName: String,
    private val onEndedCallback: () -> Unit = {},
) {

    private var thread: Thread? = null

    // 오디오 스레드가 생성하고 UI 스레드가 speed 적용 시 읽으므로 가시성 보장 필요
    @Volatile
    protected var track: AudioTrack? = null

    // wait/notify가 필요한 모니터 객체 — kotlin.Any에는 이 메서드가 없어 java.lang.Object를 쓴다
    protected val stateLock = Object()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 렌더 결과 버퍼. [renderChunk]가 채우고 [loop]가 트랙에 쓴다 */
    protected val outShort = ShortArray(CHUNK * 2)

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var framePos = 0L
        protected set

    @Volatile
    private var running = false

    @Volatile
    var semitones: Int = 0
        set(value) {
            if (field != value) {
                field = value
                shifter.semitones = value
            }
        }

    /** 재생 속도(0.25~2.0). 키와 독립 — AudioTrack 타임스트레치 */
    @Volatile
    var speed: Float = PlaybackSpeed.DEFAULT
        set(value) {
            val v = PlaybackSpeed.snap(value)
            if (field != v) {
                field = v
                applySpeed()
            }
        }

    /** A-B 반복. [PlaybackLoop.DISABLED_FRAME]이면 꺼짐. 오디오 스레드가 읽는다. */
    @Volatile
    var loopStartFrame: Long = PlaybackLoop.DISABLED_FRAME

    @Volatile
    var loopEndFrame: Long = PlaybackLoop.DISABLED_FRAME

    private var shifter = newShifter(0)

    /** 엔진이 재생할 전체 프레임 수 (시크 클램프·A-B 상한·곡 끝 판정) */
    protected abstract val totalFrames: Long

    /** 출력 AudioTrack 샘플레이트 */
    protected abstract val sampleRate: Int

    val durationFrames: Long get() = totalFrames

    fun positionFrames(): Long = framePos

    fun play() {
        if (totalFrames == 0L) return
        val limit = PlaybackLoop.limitFrames(totalFrames, loopStartFrame, loopEndFrame)
        if (framePos >= limit) {
            val restart = PlaybackLoop.restartFrame(loopStartFrame, loopEndFrame)
            if (restart != null) seekToFrame(restart) else framePos = 0
        }
        synchronized(stateLock) {
            isPlaying = true
            stateLock.notifyAll()
        }
        startEngineIfNeeded()
        applySpeed()
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
            resetProcessors()
            track?.pause()
            track?.flush()
            if (isPlaying && totalFrames > 0) {
                track?.play()
                applySpeed()
            }
        }
    }

    fun release() {
        stopEngine()
        closeSources()
    }

    // ---------- 서브클래스 훅 ----------

    /**
     * [posFrames]부터 최대 [request]프레임을 [outShort]에 렌더하고 실제 프레임 수를 반환한다.
     * <=0을 반환하면 곡 끝(또는 읽기 실패)으로 보고 랩 또는 종료 처리한다.
     * 내부 파이프라인 순서(피치 → 제거 DSP 등)는 서브클래스가 소유한다.
     */
    protected abstract fun renderChunk(posFrames: Long, request: Int): Int

    /**
     * 시크 직후 프로세서 상태를 리셋한다. stateLock 안에서 호출된다.
     * 스펙트럼 FIFO 잔여분이 시크 직후 잡음으로 붙는 것을 막는 훅이다.
     */
    protected open fun resetProcessors() {}

    /** 보유 오디오 소스(WAV 리더 등)를 닫는다. [release]에서 stopEngine 뒤에 호출된다. */
    protected abstract fun closeSources()

    /** 곡 끝 처리. 종료 전에 잔여 DSP flush가 필요하면 확장한다 */
    protected open fun finish() {
        synchronized(stateLock) { isPlaying = false }
        track?.pause()
        mainHandler.post { onEndedCallback() }
    }

    // ---------- 내부 ----------

    private fun newShifter(semi: Int): PitchShifter =
        PitchShifter().also { it.semitones = semi }

    /** interleaved shorts [frames]프레임 → 피치 적용해 [outShort]에 기록 */
    protected fun pitchShortToOut(src: ShortArray, frames: Int) {
        val sh = shifter
        var i = 0
        while (i < frames * 2) {
            sh.process(src[i] / 32768f, src[i + 1] / 32768f)
            outShort[i] = DspChain.clampShort(sh.outL)
            outShort[i + 1] = DspChain.clampShort(sh.outR)
            i += 2
        }
    }

    /** interleaved floats [frames]프레임 → 피치 적용해 [outShort]에 기록 */
    protected fun pitchFloatToOut(src: FloatArray, frames: Int) {
        val sh = shifter
        var i = 0
        while (i < frames * 2) {
            sh.process(src[i], src[i + 1])
            outShort[i] = DspChain.clampShort(sh.outL)
            outShort[i + 1] = DspChain.clampShort(sh.outR)
            i += 2
        }
    }

    private fun applySpeed() {
        PlaybackSpeed.applyTo(track, speed)
    }

    private fun startEngineIfNeeded() {
        if (thread?.isAlive == true) return
        running = true
        thread = Thread({ loop() }, threadName).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun wrapOrFinish() {
        val restart = PlaybackLoop.restartFrame(loopStartFrame, loopEndFrame)
        if (restart != null) seekToFrame(restart) else finish()
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        track = buildTrack()
        applySpeed()
        while (running) {
            val play: Boolean
            synchronized(stateLock) {
                play = isPlaying
                if (!play) stateLock.wait()
            }
            if (!running) break
            if (!play || track == null) continue

            val pos = framePos
            val limit = PlaybackLoop.limitFrames(totalFrames, loopStartFrame, loopEndFrame)
            if (pos >= limit) {
                wrapOrFinish()
                continue
            }
            val request = PlaybackLoop.chunkFrames(pos, limit, CHUNK)
            val produced = renderChunk(pos, request)
            if (produced <= 0) {
                wrapOrFinish()
                continue
            }
            val wrote = track?.write(outShort, 0, produced * 2, AudioTrack.WRITE_BLOCKING) ?: 0
            if (wrote < 0) break
            // 진행 중 시크가 끼어들었으면(framePos 변경) 스테일 값으로 덮어쓰지 않는다
            synchronized(stateLock) {
                if (framePos == pos) framePos += produced
            }
            if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track?.play()
                applySpeed()
            }
        }
        track?.release()
        track = null
    }

    protected fun stopEngine() {
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

    companion object {
        protected const val CHUNK = 2048
    }
}
