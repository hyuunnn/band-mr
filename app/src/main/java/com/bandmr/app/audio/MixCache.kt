package com.bandmr.app.audio

import android.content.Context
import android.net.Uri
import com.bandmr.app.io.FilePromote
import com.bandmr.app.separation.AudioDecode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import java.io.File

/**
 * 오디오 파이프라인 전체(캐시·분리·재생·내보내기)가 공유하는 샘플레이트.
 * MixCache WAV와 Demucs 스템 WAV는 모두 이 레이트로 생성되며, 불일치 입력은
 * 재생([StemMixPlayer])·내보내기([Exporter])에서 제외된다 — 프레임 수학 불일치 방지.
 */
const val PIPELINE_SAMPLE_RATE = 44_100

/**
 * 원본 곡의 디코딩 캐시. 압축 원본(content://)을 그 자리에서 스트리밍하지 않고,
 * 일부 기기(MediaCodec 비동기 경로가 불안정한 Android 16 펌웨어 등)에서도 재생이 보장되도록
 * 가져온 시점/첫 재생 시 44.1kHz 스테레오 PCM16 WAV로 변환해 앱 내부 저장소에 둔다.
 */
object MixCache {

    private const val DIR = "mixcache"

    fun cacheFile(context: Context, songId: Long): File =
        File(File(context.filesDir, DIR), "$songId.wav")

    /**
     * 파형 막대 캐시 파일. `<songId>.peaks`라서 [delete]·cleanUpOrphans의
     * `substringBefore('.')` 규칙이 같은 songId로 인식한다.
     */
    fun peaksFile(context: Context, songId: Long): File =
        File(File(context.filesDir, DIR), "$songId.peaks")

    fun delete(context: Context, songId: Long) {
        cacheFile(context, songId).delete()
        peaksFile(context, songId).delete()
    }

    // 같은 곡을 앱 시작 프리캐시와 재생 시점 준비가 동시에 만들지 않도록 곡 단위 직렬화
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>()

    private val ready = CacheReadyGate()

    /**
     * 캐시 WAV가 생길 때까지 중단한다. 이미 있으면 즉시 반환.
     * [prepare]가 정식 이름으로 승격한 뒤에만 깨운다. 화면을 떠나면 호출 코루틴 취소로 끝난다.
     */
    suspend fun awaitReady(context: Context, songId: Long) {
        ready.await(songId) { cacheFile(context, songId).exists() }
    }

    /**
     * [sourceUri]를 디코딩해 `mixcache/<songId>.wav`로 저장한다.
     * 완료까지 수 초가 걸릴 수 있으므로 반드시 IO 디스패처에서 호출할 것.
     * 이미 캐시가 있으면(또는 다른 스레드가 방금 만들었으면) 즉시 반환한다.
     *
     * 디코딩 결과를 `.part`에 WAV로 곧바로 쓴다(중간 raw 파일 없음 — 쓰기량·피크 절반).
     * 헤더 크기 필드는 [WavWriter]가 close 시 패치하므로 총 길이를 미리 알 필요가 없다.
     * 승격([FilePromote])은 반드시 close 뒤에 일어나야 한다(헤더 패치 완료 후 공개).
     *
     * @return 캐시된 WAV 파일 (총 프레임 수는 [WavReader]로 확인)
     */
    fun prepare(
        context: Context,
        songId: Long,
        sourceUri: Uri,
        onProgress: (Float) -> Unit = {},
    ): File {
        val lock = locks.computeIfAbsent(songId) { Any() }
        synchronized(lock) {
            val final = cacheFile(context, songId)
            if (final.exists()) return final
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val part = File(dir, "${final.name}.part")
            try {
                val frames = WavWriter.create(part, PIPELINE_SAMPLE_RATE).use { writer ->
                    AudioDecode.decodeTo44kStereo(context, sourceUri, onProgress) { buf, n ->
                        writer.writeShorts(buf, n)
                    }
                }
                // 0프레임이면 헤더만 있는 44바이트 WAV가 된다. 이걸 승격하면 "열리기는 하는데
                // 길이가 0인" 캐시가 정식 파일로 공개되고, 그 뒤로는 아무도 못 잡는다 —
                // FilePromote의 크기 검사는 44 > 0이라 통과하고, WavReader도 정상 WAV로
                // 파싱하므로 openSourceOrDiscardCache가 버리지 못하며, 재생은 play()의
                // `totalFrames == 0` 가드에 걸려 조용히 no-op이 된다(재생 버튼 영구 무반응).
                // 여기서 던지면 호출부의 실패 경로(prepareFailedSongId·내보내기 오류)로 나간다.
                check(frames > 0) { "곡에서 소리를 찾지 못했습니다 (빈 오디오)" }
                FilePromote.file(part, final)
                // 승격 뒤에만 알린다 — awaitReady는 구독 후 exists를 다시 봐서
                // 이 emit을 놓쳐도 파일이 있으면 바로 끝난다
                ready.signal(songId)
                return final
            } finally {
                // 성공 시 part는 이미 final로 rename됨. 실패 시 남은 부분 파일을
                // final로 승격하면 손상 캐시가 재생에 쓰이므로 반드시 삭제만 한다.
                part.delete()
            }
        }
    }
}

/**
 * 곡 캐시 완료 신호. [signal]은 파일이 이미 보이는 뒤에만 호출한다.
 * [await]는 구독을 건 다음 현재 상태를 다시 확인해, signal과 exists 검사 사이 경쟁을 막는다.
 */
internal class CacheReadyGate {
    private val ready = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** 테스트에서 구독 시점 경쟁을 확인하는 용도 */
    @androidx.annotation.VisibleForTesting
    val subscriberCount get() = ready.subscriptionCount

    fun signal(songId: Long) {
        ready.tryEmit(songId)
    }

    suspend fun await(songId: Long, isReady: () -> Boolean) {
        if (isReady()) return
        val subscribed = CompletableDeferred<Unit>()
        coroutineScope {
            val job = launch {
                ready
                    .onSubscription { subscribed.complete(Unit) }
                    .first { it == songId }
            }
            subscribed.await()
            if (isReady()) job.cancel() else job.join()
        }
    }
}
