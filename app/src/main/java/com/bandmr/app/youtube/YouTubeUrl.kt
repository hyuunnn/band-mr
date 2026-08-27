package com.bandmr.app.youtube

/**
 * 유튜브 링크/ID 파싱과 오디오 스트림 선택. 안드로이드 의존성이 없는 순수 JVM이라
 * JVM 단위테스트로 검증한다 (YouTubeUrlTest 참조).
 */
object YouTubeUrl {

    /** 유튜브 영상 ID: 정확히 11자, [A-Za-z0-9_-] */
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /**
     * 다양한 형식의 입력에서 영상 ID를 추출한다.
     * 지원: watch?v=, youtu.be/, shorts/, live/, embed/, v/, music·m 서브도메인,
     * 스킴 생략(www.youtube.com/...), ID만 붙여넣기.
     *
     * @return 유효한 11자 영상 ID, 아니면 null
     */
    fun videoIdOf(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        // 공백이 섞여 붙여넣어진 경우 첫 토큰만 취급
        val firstToken = input.split(Regex("\\s+")).first()
        if (VIDEO_ID.matches(firstToken)) return firstToken

        // https:// 없이 도메인부터 시작하는 입력 허용
        var text = firstToken.removePrefix("//")
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            text = "https://$text"
        }
        if (text.startsWith("http://", ignoreCase = true)) {
            // 평문 http도 받아주되, 파서 결과는 동일하게 처리
            text = "https://${text.substringAfter("://")}"
        }

        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        when (host) {
            "youtube.com", "m.youtube.com", "music.youtube.com",
            "youtube-nocookie.com",
            -> return idFromPathOrQuery(uri.path.orEmpty(), uri.rawQuery)

            "youtu.be" -> {
                val seg = uri.path.orEmpty().trim('/').substringBefore('/')
                return if (VIDEO_ID.matches(seg)) seg else null
            }
        }
        return null
    }

    private fun idFromPathOrQuery(path: String, rawQuery: String?): String? {
        // watch?v=ID (첫 번째 v 값 사용; 대소문자 혼용 입력 허용)
        rawQuery?.split('&')?.forEach { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            if (k.equals("v", ignoreCase = true) && VIDEO_ID.matches(v)) return v
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size == 2 && segments[0] in setOf(
                "shorts", "live", "embed", "v"
            ) && VIDEO_ID.matches(segments[1])
        ) return segments[1]
        return null
    }
}

/**
 * 추출기의 AudioStream을 직접 테스트 환경으로 끌지 않고 검증하기 위한 최소 뷰 모델.
 * [YouTubeImporter]가 extractor 타입을 이 타입으로 매핑해 선택 로직을 재사용한다.
 */
data class AudioCandidate(
    /** 원본 데이터 URL. null/빈 값은 후보에서 제외된다 */
    val url: String?,
    /** MIME 타입 (예: audio/mp4, audio/webm) */
    val mimeType: String?,
    /** 평균 비트레이트 kbps. 알 수 없으면 0 이하 */
    val avgBitrateKbps: Int,
)

object AudioChooser {

    /**
     * 연습용 원본 소스 선택 기준:
     * 1) AAC(M4A) 컨테이너 우선 — MediaCodec/MediaMetadataRetriever 호환이 가장 안정적이고
     *    Opus 대비 실질 음질 차이는 연습 목적에서 미미하다.
     * 2) 같은 등급 내에서는 높은 비트레이트 우선.
     *
     * @return 선택된 후보, 전부 무효면 null
     */
    fun choose(candidates: List<AudioCandidate>): AudioCandidate? =
        candidates.filter { !it.url.isNullOrBlank() }
            .maxWithOrNull(
                compareBy<AudioCandidate> { mimeRank(it.mimeType) }
                    .thenBy { it.avgBitrateKbps },
            )

    private fun mimeRank(mime: String?): Int = when {
        mime == null -> -1
        mime.contains("mp4a") || mime.contains("aac") || mime.contains("mp4") || mime.contains("m4a") -> 1
        else -> 0
    }
}
