package com.bandmr.app.audio

import android.media.AudioTrack
import java.io.File

/**
 * AI OFF 모드 재생기: 원본 전체 믹스 WAV(44.1kHz 스테레오 PCM16)를 읽어
 * 실시간 [DspChain](제거 마스크)과 [PitchShifter](키 조절)를 적용한다.
 * 트랙 출력·A-B·시크·배속 골격은 [AudioTrackEngine]이 담당한다.
 *
 * 기기의 MediaCodec 비동기 디코딩 경로가 불안정한 환경이 있어(일부 Android 16 펌웨어),
 * 압축 원본을 스트리밍하지 않고 가져온 시점에 디코딩해 둔 WAV 캐시([MixCache])를 재생한다.
 */
class SourceWavPlayer(
    file: File,
    onEndedCallback: () -> Unit = {},
) : AudioTrackEngine(threadName = "SourceMix", onEndedCallback = onEndedCallback) {

    private val reader: WavReader = WavReader(file)
    val channels: Int = reader.channels

    override val sampleRate: Int = reader.sampleRate

    override val totalFrames: Long get() = reader.totalFrames

    /** 제거할 스템 비트마크 (Stem.bit 조합). 변경 시 DSP 상태 초기화 */
    @Volatile
    var muteMask: Int = 0
        set(value) {
            field = value
            rebuildChain()
        }

    /** 보컬 제거 강도 0..1. 상태 리셋 없이 즉시 반영된다 */
    @Volatile
    var vocalStrength: Float = 1f
        set(value) {
            field = value
            chain.vocalStrength = value
        }

    // muteMask 변경 때만 객체를 교체한다(공개 전에 마스크를 걸어 경쟁 없음, 탭 1회당 1번).
    // 오디오 스레드가 락 없이 읽으므로 가시성 보장 필요.
    @Volatile
    private var chain = newChain()

    // ---------- AudioTrackEngine 훅 ----------

    private val srcShort = ShortArray(CHUNK * 2)

    override fun renderChunk(posFrames: Long, request: Int): Int {
        val got = try {
            reader.read(posFrames, srcShort, request)
        } catch (_: Exception) {
            -1
        }
        if (got <= 0) return got
        // 내보내기(Exporter)가 재생 경로와 같은 순서를 유지한다: 피치시프트 → 제거 체인
        // (스펙트럼 단계 지연 ~23ms는 내부 FIFO가 흡수)
        pitchShortToOut(srcShort, got)
        chain.processInPlace(outShort, got * 2)
        return got
    }

    /** 시크마다 호출된다(스크럽 시 초당 10회) — 재할당 없이 제자리 리셋 */
    override fun resetProcessors() {
        chain.reset()
    }

    override fun closeSources() {
        runCatching { reader.close() }
    }

    /** 트랙 종료 처리: DSP 파이프라인 잔여분(약 1블록)을 밀어낸 뒤 끝난다 */
    override fun finish() {
        val c = chain // @Volatile — 락 불필요
        if (c.muteMask != 0) {
            c.drain { arr, n ->
                track?.write(arr, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        }
        framePos = totalFrames
        super.finish()
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
}
