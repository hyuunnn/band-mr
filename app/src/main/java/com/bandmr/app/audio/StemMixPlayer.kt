package com.bandmr.app.audio

import android.util.Log
import com.bandmr.app.data.Stem
import java.io.File

private const val TAG = "StemMixPlayer"

/**
 * AI 분리 완료 후 스템 WAV 6개를 동기 재생하며 스템별 게인(제거)과 피치를 적용하는 커스텀 믹서.
 * 트랙 출력·A-B·시크·배속 골격은 [AudioTrackEngine]이 담당하고,
 * 스템 파일을 여는 규칙(누락·레이트 불일치 처리)은 [StemWavSet]이 내보내기와 공유한다.
 */
class StemMixPlayer(onEndedCallback: () -> Unit = {}) :
    AudioTrackEngine(threadName = "StemMix", onEndedCallback = onEndedCallback) {

    // load/closeSources는 stopEngine 뒤에 돌지만, 오디오 스레드가 락 없이 읽으므로 가시성 보장
    @Volatile
    private var stems: StemWavSet? = null

    // 불일치 스템은 StemWavSet에서 제외되므로 항상 파이프라인 레이트
    override val sampleRate = PIPELINE_SAMPLE_RATE

    override var totalFrames = 0L

    /** 스템별 게인. muted면 0 */
    @Volatile
    var gains: FloatArray = FloatArray(Stem.entries.size) { 1f }
        set(value) {
            field = value.copyOf()
        }

    // 읽기 실패 로그를 스템별 1회로 억제한다. 렌더는 초당 20여 회 돌아서 그냥 찍으면 로그가 쏟아진다
    private val readFailureLogged = BooleanArray(Stem.entries.size)

    /** [dir]의 스템 WAV를 열어 재생을 준비한다 */
    fun load(dir: File) {
        stopEngine()
        closeSources()
        readFailureLogged.fill(false)
        val set = StemWavSet.open(dir)
        stems = set
        totalFrames = set.totalFrames
        framePos = 0
    }

    // ---------- AudioTrackEngine 훅 ----------

    private val mixedFloat = FloatArray(CHUNK * 2)
    private val stemShort = ShortArray(CHUNK * 2)

    override fun renderChunk(posFrames: Long, request: Int): Int {
        // teardown 경쟁: stopEngine이 running=false로 만든 뒤에도, 이미 루프 안쪽까지 들어온
        // 바퀴 하나는 끝까지 돈다(`if (!running) break`를 이미 지난 상태). 그 창에서 closeSources가
        // 먼저 끝나면 여기서 stems가 null로 보이거나 readerAt이 닫힌 리더를 준다.
        //
        // 그래서 (1) null이어도 조기 반환하지 않고 (2) 읽기 예외를 스템 단위로 흡수한다.
        // 0 이하를 돌려주면 엔진이 곡 끝으로 보고 onEnded를 던지는데, 그게 메인 스레드에 늦게
        // 도착하면 방금 새로 만든 엔진의 재생이 UI에서 일시정지로 뒤집히고 포커스까지 반납된다.
        val set = stems
        // @Volatile 필드를 청크 안에서 여러 번 읽으면 판정과 적용이 다른 스냅샷에서 올 수 있다
        val gainSnapshot = gains
        java.util.Arrays.fill(mixedFloat, 0f)
        // 들리는 스템 중 가장 짧게 읽힌 프레임 수로 출력 길이 제한.
        // 전부 뮤트/EOF면 무음을 출력하며 진행한다 (전체 뮤트가 곡 종료로 오인되지 않도록)
        var minGot = Int.MAX_VALUE
        for (ordinal in Stem.entries.indices) {
            val gain = gainSnapshot[ordinal]
            if (gain <= 0f) continue
            val reader = set?.readerAt(ordinal) ?: continue
            // 닫힌 리더를 읽으면 IOException이 나는데 loop()에는 catch가 없어 오디오 스레드가
            // 그대로 죽는다(프로세스 사망). 이 스템만 건너뛴다 — 0을 돌려주면 위 주석대로
            // 곡 끝으로 오인된다. (SourceWavPlayer는 같은 예외를 -1로 바꿔 곡 끝으로 보내는데,
            // 그건 곡이 1개뿐이라 스템 단위로 건너뛸 수가 없어서다)
            val got = try {
                reader.read(posFrames, stemShort, request)
            } catch (e: Exception) {
                // 해제 중이면 닫힌 리더를 읽은 정상적인 경우라 조용히 넘긴다.
                // 그 밖의 실패(스템 파일 손상 등)는 흔적을 남긴다 — 안 남기면 사용자에게는
                // "악기 하나가 안 들린다"로만 보이고 원인을 추적할 방법이 없다
                if (!isReleased && !readFailureLogged[ordinal]) {
                    readFailureLogged[ordinal] = true
                    Log.w(TAG, "스템 읽기 실패로 무음 처리: ${Stem.entries[ordinal].fileName}.wav", e)
                }
                continue
            }
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
        stems?.close()
        stems = null
    }
}
