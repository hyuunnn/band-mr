package com.bandmr.app.youtube

import android.net.Uri
import com.bandmr.app.Locator
import com.bandmr.app.audio.MixCache
import com.bandmr.app.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

sealed interface ImportState {
    data object Idle : ImportState

    /** 링크 파싱·정보 추출 중 */
    data class Resolving(val query: String) : ImportState

    /**
     * 오디오 다운로드 중. [progress]는 0..1, Content-Length 미제공 시 null(불명).
     */
    data class Downloading(
        val title: String,
        val progress: Float?,
        val receivedBytes: Long,
        val totalBytes: Long?,
    ) : ImportState

    /** 곡 DB 등록 + WAV 캐시 준비 */
    data class PreparingCache(val title: String) : ImportState
    data class Done(val songId: Long, val title: String) : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * 유튜브 링크로 곡을 추가하는 임포터. 화면 수명과 무관하게 끝까지 진행되어야 하므로
 * [Locator.appScope]에서 실행한다 (PlayerController 스코프 취소 버그 교훈 준수).
 *
 * 흐름: 링크 파싱 → StreamInfo 추출(NewPipeExtractor) → 오디오 스트림 선택 →
 * `filesDir/sources/<videoId>.<ext>` 다운로드 → Song 등록(file:// URI) → MixCache 프리페어
 *
 * 다운로드된 원본은 압축 원본 그대로이며 재생 파이프라인도 기존과 동일하게
 * MixCache의 WAV로 디코딩해 사용한다(압축 원본 스트리밍 금지 불변식 준수).
 */
object YouTubeImport {

    val state = MutableStateFlow<ImportState>(ImportState.Idle)

    private var job: Job? = null

    private const val SOURCES_DIR = "sources"

    /** 라이브러리 목록 과다 스크롤 방지용 표시명 상한 */
    private const val MAX_TITLE_LEN = 120

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    @Volatile
    private var newPipeReady = false

    /** 이미 진행 중이면 false를 반환하고 무시한다 */
    fun start(rawInput: String): Boolean {
        val input = rawInput.trim()
        if (input.isEmpty()) return false
        if (job?.isActive == true) return false
        job = Locator.appScope.launch {
            runCatchingImport(input)
        }
        return true
    }

    /** 백그라운드에서 계속 진행하고 UI만 닫고 싶을 때 상태 노출을 끈다 */
    fun hideDialog() {
        if (!isRunning()) state.value = ImportState.Idle
    }

    fun cancel() {
        job?.cancel()
        job = null
        state.value = ImportState.Idle
    }

    fun isRunning(): Boolean = job?.isActive == true

    private suspend fun runCatchingImport(input: String) {
        try {
            import(input)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            state.value = ImportState.Failed(userMessage(t))
        }
    }

    private suspend fun import(input: String) {
        val videoId = YouTubeUrl.videoIdOf(input)
            ?: throw IllegalArgumentException("유효한 유튜브 링크가 아닙니다")
        val context = Locator.context

        state.value = ImportState.Resolving(input)
        val info = withContext(Dispatchers.IO) { resolveInfo(videoId) }

        val title = info.name.orEmpty().ifBlank { "제목 없음" }.take(MAX_TITLE_LEN)
        val candidates = withContext(Dispatchers.IO) {
            info.audioStreams.map {
                AudioCandidate(
                    url = it.url,
                    mimeType = it.format?.mimeType,
                    avgBitrateKbps = it.averageBitrate,
                )
            }
        }
        // 선택 로직 자체는 순수하지만 AudioStream 접근이 모두 포함된 상태라 IO 위에서 처리
        val bestUrl = AudioChooser.choose(candidates)?.url
            ?: throw IllegalStateException("다운로드 가능한 오디오 스트림이 없습니다")
        val ext = info.audioStreams.firstOrNull { it.url == bestUrl }
            ?.format?.suffix ?: "m4a"

        state.value = ImportState.Downloading(title, null, 0, null)
        val source = withContext(Dispatchers.IO) {
            download(context, videoId, bestUrl, ext)
        }

        state.value = ImportState.PreparingCache(title)
        val songId = Locator.songDao.insert(
            Song(
                title = title,
                uri = Uri.fromFile(source).toString(),
                durationMs = info.duration * 1000L,
            ),
        )
        withContext(Dispatchers.IO) {
            MixCache.prepare(context, songId, Uri.fromFile(source))
        }
        state.value = ImportState.Done(songId, info.name)
    }

    private fun resolveInfo(videoId: String): StreamInfo {
        initNewPipeIfNeeded()
        val service = ServiceList.YouTube
        val url = service.streamLHFactory.fromId(videoId).url
        return StreamInfo.getInfo(service, url)
    }

    private fun initNewPipeIfNeeded() {
        if (newPipeReady) return
        synchronized(this) {
            if (newPipeReady) return
            NewPipe.init(
                HttpDownloader(),
                Localization.fromLocale(Locale.getDefault()),
            )
            newPipeReady = true
        }
    }

    /**
     * 스트림 URL을 part 파일로 내려받은 뒤 최종 파일로 rename한다.
     * 같은 영상 ID 원본이 이미 있으면 즉시 반환한다(재임포트 비용 절감).
     * 실패 시 part 파일은 폐기한다 — 구간별 googlevideo URL이 시간이 지나면 만료되어
     * 이어받기 가치가 없다(ModelManager의 .tmp 보존 규칙은 모델 전용).
     */
    private suspend fun download(
        context: android.content.Context,
        videoId: String,
        url: String,
        ext: String,
    ): File {
        val dir = File(context.filesDir, SOURCES_DIR).apply { mkdirs() }
        val final = File(dir, "$videoId.$ext")
        if (final.exists()) return final

        val part = File(dir, "${final.name}.part")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val total = conn.contentLengthLong.takeIf { it > 0 }
            var received = 0L
            var lastPercent = -1

            conn.inputStream.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        // 전체 1% 경계마다만 상태 갱신 (리소스 절약, StateFlow도 conflate 함)
                        if (total != null && received >= (lastPercent + 1) * total / 100.0) {
                            val pct = ((received * 100) / total).toInt()
                            lastPercent = pct
                            state.value = ImportState.Downloading(
                                state.value.currentTitle(), received.toFloat() / total,
                                received, total,
                            )
                        } else if (total == null && received % (512 * 1024) < buf.size.toLong()) {
                            state.value = ImportState.Downloading(
                                state.value.currentTitle(), null, received, null,
                            )
                        }
                    }
                }
            }
            check(part.renameTo(final)) { "다운로드 파일 이동 실패: $part" }
            return final
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun userMessage(t: Throwable): String = when (t) {
        is IllegalArgumentException -> t.message ?: "유효한 유튜브 링크가 아닙니다"
        is ContentNotAvailableException -> "영상을 찾을 수 없습니다 (삭제·비공개·지역제한일 수 있음)"
        is IOException -> "네트워크 오류가 발생했습니다: ${t.message ?: "알 수 없음"}"
        else -> "가져오기 실패: ${t.message ?: t::class.java.simpleName}"
    }
}

private fun ImportState.currentTitle(): String =
    (this as? ImportState.Downloading)?.title.orEmpty()

/**
 * NewPipeExtractor 요청 전송기. HttpURLConnection 기반으로 OkHttp 등 추가 의존성 없이 동작.
 * 추출기는 페이지 html/json 본문 문자열만 필요로 한다.
 */
private class HttpDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT2)
            request.headers().forEach { (name, values) ->
                if (!name.equals("user-agent", ignoreCase = true)) {
                    setRequestProperty(name, values.joinToString(", "))
                }
            }
        }
        try {
            val code = conn.responseCode
            val bodyStream = conn.errorStream ?: conn.inputStream
            val body = bodyStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val headers = conn.headerFields.filterKeys { it != null }
                .mapKeysTo(mutableMapOf<String, List<String>>()) { it.key as String }
            return Response(code, body, headers, null, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT2 =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}

