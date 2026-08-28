package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import java.io.File

/**
 * AI 분리 완료 후 스템 WAV 6개를 동기 재생하며 스템별 게인(제거)과 피치를 적용하는 커스텀 믹서.
 * 트랙 출력·A-B·시크·배속 골격은 [AudioTrackEngine]이 담당한다.
 */
class StemMixPlayer(onEndedCallback: () -> Unit = {}) :
    AudioTrackEngine(threadName = "StemMix", onEndedCallback = onEndedCallback) {

    private val readers = arrayOfNulls<WavReader>(Stem.entries.size)

    // 스템 WAV는 DemucsSeparator가 전부 44.1k로 쓴다. load에서 실측값으로 덮어쓴다
    override var sampleRate = 44100

    override var totalFrames = 0L

    /** 스템별 게인. muted면 0 */
    @Volatile
    var gains: FloatArray = FloatArray(Stem.entries.size) { 1f }
        set(value) {
            field = value.copyOf()
        }

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

    // ---------- AudioTrackEngine 훅 ----------

    private val mixedFloat = FloatArray(CHUNK * 2)
    private val stemShort = ShortArray(CHUNK * 2)

    override fun renderChunk(posFrames: Long, request: Int): Int {
        java.util.Arrays.fill(mixedFloat, 0f)
        // 들리는 스템 중 가장 짧게 읽힌 프레임 수로 출력 길이 제한.
        // 전부 뮤트/EOF면 무음을 출력하며 진행한다 (전체 뮤트가 곡 종료로 오인되지 않도록)
        var minGot = Int.MAX_VALUE
        for (s in readers.indices) {
            val gain = gains[s]
            val reader = readers[s] ?: continue
            if (gain <= 0f) continue
            val got = reader.read(posFrames, stemShort, request)
            if (got <= 0) continue
            if (got < minGot) minGot = got
            var i = 0
            while (i < got * 2) {
                mixedFloat[i] += stemShort[i] / 32768f * gain
                i++
            }
        }
        val framesToWrite = minOf(
            if (minGot == Int.MAX_VALUE) request else minGot,
            request,
        )
        pitchFloatToOut(mixedFloat, framesToWrite)
        return framesToWrite
    }

    override fun closeSources() {
        readers.forEachIndexed { i, r -> runCatching { r?.close() }; readers[i] = null }
    }
}
