package com.bandmr.app.youtube

import android.content.Context
import android.net.Uri
import android.util.Log
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
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal const val HTTP_COPY_BUF = 64 * 1024
private const val TAG = "YouTubeImport"

/** 추출 API(NewPipe)와 구간 다운로드(googlevideo) 요청에 공통으로 쓰는 UA */
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

sealed interface ImportState {
    data object Idle : ImportState

    /** 링크 파싱·정보 추출 중 */
    data object Resolving : ImportState

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

    /**
     * 터미널 상태(Done/Failed)의 UI 노출을 끊는다. 실행 중에는 건드리지 않는다 —
     * 다이얼로그를 닫을 때와 다시 열 때 남은 성공/실패 메시지를 초기화하는 용도.
     */
    fun dismiss() {
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
            Log.e(TAG, "import failed", t)
            state.value = ImportState.Failed(userMessage(t))
        }
    }

    private suspend fun import(input: String) {
        val videoId = YouTubeUrl.videoIdOf(input)
            ?: throw IllegalArgumentException("유효한 유튜브 링크가 아닙니다")
        val context = Locator.context

        state.value = ImportState.Resolving
        val info = withContext(Dispatchers.IO) { resolveInfo(videoId) }

        val title = info.name.orEmpty().ifBlank { "제목 없음" }.take(MAX_TITLE_LEN)
        // deprecated getUrl 대신 getContent 사용. 빈 콘텐츠/매니페스트 전용 스트림도
        // 후보에 넣되(AAC만 있을 때 폴백 용도) 선택 시 맨 뒤로 밀린다
        val candidates = withContext(Dispatchers.IO) {
            info.audioStreams.map {
                AudioCandidate(
                    url = it.content,
                    mimeType = it.format?.mimeType,
                    avgBitrateKbps = it.averageBitrate,
                    progressiveHttp = it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
                )
            }
        }
        // 선택 로직 자체는 순수하지만 AudioStream 접근이 모두 포함된 상태라 IO 위에서 처리
        val bestUrl = AudioChooser.choose(candidates)?.url
            ?: throw IllegalStateException("다운로드 가능한 오디오 스트림이 없습니다")
        val ext = info.audioStreams.firstOrNull { it.content == bestUrl }
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
                // 라이브·길이 불명 영상은 음수가 나올 수 있다
                durationMs = info.duration.coerceAtLeast(0) * 1000L,
            ),
        )
        withContext(Dispatchers.IO) {
            MixCache.prepare(context, songId, Uri.fromFile(source))
        }
        state.value = ImportState.Done(songId, title)
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
        context: Context,
        videoId: String,
        url: String,
        ext: String,
    ): File {
        val dir = File(context.filesDir, SOURCES_DIR).apply { mkdirs() }
        val final = File(dir, "$videoId.$ext")
        if (final.exists()) return final

        val part = File(dir, "${final.name}.part")
        if (part.exists()) part.delete()
        var conn: HttpURLConnection? = null
        var succeeded = false
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Referer", "https://www.youtube.com")

            // 만료된 구간별 URL은 403으로 답한다 — 암묵적 스트림 예외 대신 상태 코드를 명시해 확인
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("스트림 서버 응답 오류 (HTTP $code)")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            var received = 0L
            var lastPercent = -1
            var copyReturned = false

            try {
                conn.inputStream.use { ins ->
                    part.outputStream().use { out ->
                        received = copyHttpBody(ins, out, total) { rec ->
                            received = rec
                            // 갱신 빈도 제한: 크기 불명은 512KB마다, 크기 확인 시 1% 경계마다만
                            if (total != null) {
                                val pct = ((rec * 100) / total).toInt()
                                if (pct != lastPercent) {
                                    lastPercent = pct
                                    state.value = ImportState.Downloading(
                                        state.value.currentTitle(), pct / 100f,
                                        rec, total,
                                    )
                                }
                            } else if (rec % (512 * 1024) < HTTP_COPY_BUF.toLong()) {
                                state.value = ImportState.Downloading(
                                    state.value.currentTitle(), null, rec, null,
                                )
                            }
                        }
                        copyReturned = true
                    }
                }
            } catch (e: IOException) {
                // 본문 복사가 끝난 뒤의 close/RST, 또는 디스크에 전량이 있으면 정상 종료
                if (!shouldKeepDownload(e, received, total, part.length(), copyReturned)) {
                    throw e
                }
            }
            if (part.length() <= 0L) {
                throw IOException("다운로드 파일이 비어 있습니다")
            }
            promotePart(part, final)
            succeeded = true
            return final
        } finally {
            conn?.disconnect()
            if (!succeeded && part.exists()) part.delete()
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
 * NewPipeExtractor 요청 전송기. OkHttp 등 추가 의존성 없이 HttpURLConnection 기반으로 동작.
 * 유튜브 스트림 추출은 innertube API(/player 등)에 JSON 본문을 POST로 보내므로
 * [Request.dataToSend]가 있으면 반드시 함께 전송한다.
 */
private class HttpDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            request.headers().forEach { (name, values) ->
                if (!name.equals("user-agent", ignoreCase = true)) {
                    setRequestProperty(name, values.joinToString(", "))
                }
            }
        }
        try {
            val data = request.dataToSend()
            if (data != null && data.isNotEmpty()) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(data.size)
                conn.outputStream.use { it.write(data) }
            }
            val code = conn.responseCode
            val bodyStream = conn.errorStream ?: conn.inputStream
            val body = bodyStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val headers = conn.headerFields.filterKeys { it != null }
                .mapKeysTo(mutableMapOf<String, List<String>>()) { it.key as String }
            return newPipeResponse(code, conn.responseMessage, headers, body, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * NewPipe [Response] 생성자 순서는 (code, message, headers, body, latestUrl).
 * body/message를 바꾸면 추출기가 HTTP 상태문구("OK")를 JSON으로 읽고
 * `JSON response is too short`로 죽는다.
 */
internal fun newPipeResponse(
    code: Int,
    httpMessage: String?,
    headers: Map<String, List<String>>,
    body: String,
    latestUrl: String,
): Response = Response(code, httpMessage, headers, body, latestUrl)

internal fun downloadReachedTotal(received: Long, total: Long?): Boolean =
    total != null && received >= total

internal fun isBenignDisconnect(e: IOException): Boolean {
    if (e is java.net.SocketException) return true
    val m = e.message?.lowercase() ?: return false
    return "unexpected end" in m ||
        "connection reset" in m ||
        "connection closed" in m ||
        "broken pipe" in m ||
        "software caused connection abort" in m
}

/** 본문 복사가 끝났거나, 디스크/카운터가 이미 전량이면 close/RST 예외를 무시한다. */
internal fun shouldKeepDownload(
    e: IOException,
    received: Long,
    total: Long?,
    fileLength: Long,
    copyReturned: Boolean,
): Boolean {
    if (copyReturned) return true
    if (downloadReachedTotal(received, total) || (total != null && fileLength >= total)) return true
    return isBenignDisconnect(e) && fileLength > 0L && total == null
}

/** rename이 같은 마운트에서 실패하는 기기용 copy 폴백. ModelManager와 동일. */
internal fun promotePart(part: File, final: File) {
    if (final.exists()) final.delete()
    if (part.renameTo(final)) return
    part.copyTo(final, overwrite = true)
    part.delete()
    check(final.exists() && final.length() > 0L) { "다운로드 파일 이동 실패: $part" }
}

/**
 * HTTP 본문을 [output]에 복사한다. Content-Length만큼 받은 뒤의 read 예외와
 * 길이 미상일 때의 RST는 서버가 연결을 끊은 정상 종료로 본다.
 */
internal suspend fun copyHttpBody(
    input: InputStream,
    output: OutputStream,
    total: Long?,
    onProgress: (received: Long) -> Unit = {},
): Long {
    val buf = ByteArray(HTTP_COPY_BUF)
    var received = 0L
    while (true) {
        coroutineContext.ensureActive()
        val n = try {
            input.read(buf)
        } catch (e: IOException) {
            if (downloadReachedTotal(received, total) ||
                (total == null && received > 0 && isBenignDisconnect(e))
            ) {
                break
            }
            throw e
        }
        if (n < 0) break
        if (n == 0) continue
        output.write(buf, 0, n)
        received += n
        onProgress(received)
        if (downloadReachedTotal(received, total)) break
    }
    if (total != null && received < total) {
        throw IOException("다운로드가 중간에 끊겼습니다 ($received / $total)")
    }
    return received
}

