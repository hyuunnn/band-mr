package com.bandmr.app.audio

import android.util.Log
import com.bandmr.app.data.Stem
import java.io.File

private const val TAG = "StemMixPlayer"

/**
 * AI 분리 완료 후 스템 WAV 6개를 동기 재생하며 스템별 게인(제거)과 피치를 적용하는 커스텀 믹서.
 * 트랙 출력·A-B·시크·배속 골격은 [AudioTrackEngine]이 담당한다.
 */
class StemMixPlayer(onEndedCallback: () -> Unit = {}) :
    AudioTrackEngine(threadName = "StemMix", onEndedCallback = onEndedCallback) {

    private val readers = arrayOfNulls<WavReader>(Stem.entries.size)

    // 불일치 스템은 load에서 제외되므로 항상 파이프라인 레이트
    override val sampleRate = PIPELINE_SAMPLE_RATE

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
        var total = 0L
        files.forEach { (stem, file) ->
            if (file.exists()) {
                try {
                    val r = WavReader(file)
                    if (r.sampleRate != PIPELINE_SAMPLE_RATE) {
                        // 프레임 수학이 어긋나는 스템은 조용히 섞지 말고 제외한다
                        Log.w(TAG, "샘플레이트 불일치 스템 제외: ${file.name} (${r.sampleRate}Hz)")
                        runCatching { r.close() }
                    } else {
                        readers[stem.ordinal] = r
                        total = maxOf(total, r.totalFrames)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "스템 열기 실패: ${file.name}", e)
                }
            }
        }
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
