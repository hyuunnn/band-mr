package com.bandmr.app.audio

import android.util.Log
import com.bandmr.app.data.Stem
import java.io.Closeable
import java.io.File

private const val TAG = "StemWavSet"

/**
 * 분리된 스템 WAV 묶음.
 *
 * 재생([StemMixPlayer])과 내보내기(Exporter)가 같은 규칙으로 열어야 결과가 어긋나지 않는다:
 *  - 파일이 없거나 열리지 않는 스템은 건너뛴다(모델이 6개를 다 내놓지 않을 수 있다)
 *  - [PIPELINE_SAMPLE_RATE] 불일치 스템은 **제외**한다 — 프레임 수학이 어긋난 채로 섞으면
 *    위치·길이가 전부 밀리므로, 조용히 섞지 않고 빼고 로그를 남긴다
 *  - 길이 기준은 가장 긴 스템. 짧은 스템은 끝을 지나면 0프레임을 돌려주므로 뒷부분이 무음이 된다
 *
 * 접근은 [readerAt]([Stem.ordinal] 기준) 하나뿐이다. 맵을 함께 노출하면 두 호출부의 합산
 * 순서가 갈릴 수 있는데, float 덧셈은 비결합이라 재생과 내보내기 결과가 LSB 단위로 달라진다.
 */
class StemWavSet private constructor(
    private val slots: Array<WavReader?>,
    /** 가장 긴 스템의 프레임 수 */
    val totalFrames: Long,
) : Closeable {

    val isEmpty: Boolean get() = slots.all { it == null }

    /**
     * [Stem.ordinal] 위치의 리더. 없으면 null. 오디오 스레드에서 매 청크 호출되므로 할당이 없다.
     *
     * 주의: 반환된 리더는 [close] 이후에도 호출부가 잡고 있을 수 있다(진행 중인 렌더 바퀴가 캡처).
     * 읽기 실패를 흡수하는 것은 호출부 책임이다 — [StemMixPlayer.renderChunk] 참조.
     */
    fun readerAt(ordinal: Int): WavReader? = slots[ordinal]

    override fun close() {
        // 슬롯을 비우는 것은 **안전장치가 아니라 소음 줄이기다.** 비어 있으면 오디오 스레드가
        // 닫힌 리더를 아예 잡지 않고 건너뛰므로 teardown 중 예외가 줄어든다. 다만 배열 원소
        // 쓰기는 volatile이 아니라 가시성이 보장되지 않으니 이것만 믿어선 안 된다.
        // 실제 안전장치는 호출부의 읽기 예외 흡수다([StemMixPlayer.renderChunk]) — 그쪽을
        // 지우면 오디오 스레드가 죽는다. 여기를 지워도 동작은 유지된다(예외만 늘어난다).
        for (i in slots.indices) {
            runCatching { slots[i]?.close() }
            slots[i] = null
        }
    }

    companion object {
        /** `<dir>/<stem.fileName>.wav` 6개를 연다 */
        fun open(dir: File): StemWavSet {
            val slots = arrayOfNulls<WavReader>(Stem.entries.size)
            var total = 0L
            Stem.entries.forEach { stem ->
                val file = File(dir, "${stem.fileName}.wav")
                if (!file.exists()) return@forEach
                val reader = runCatching { WavReader(file) }
                    .onFailure { Log.w(TAG, "스템 열기 실패: ${file.name}", it) }
                    .getOrNull() ?: return@forEach
                if (reader.sampleRate != PIPELINE_SAMPLE_RATE) {
                    Log.w(TAG, "샘플레이트 불일치 스템 제외: ${file.name} (${reader.sampleRate}Hz)")
                    runCatching { reader.close() }
                    return@forEach
                }
                slots[stem.ordinal] = reader
                total = maxOf(total, reader.totalFrames)
            }
            return StemWavSet(slots, total)
        }
    }
}
