package com.bandmr.app.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 순수 JVM 파싱·선택 로직 검증. 안드로이드 실행 전 로직 결함을 여기서 걷어낸다. */
class YouTubeUrlTest {

    private val ID = "dQw4w9WgXcQ"

    @Test
    fun `표준 watch 링크에서 ID 추출`() {
        assertEquals(ID, YouTubeUrl.videoIdOf("https://www.youtube.com/watch?v=$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://youtube.com/watch?v=$ID&t=42s"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://m.youtube.com/watch?v=$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://music.youtube.com/watch?v=$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://www.youtube-nocookie.com/watch?v=$ID"))
    }

    @Test
    fun `youtu be 단축 링크에서 ID 추출`() {
        assertEquals(ID, YouTubeUrl.videoIdOf("https://youtu.be/$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://youtu.be/$ID?t=10"))
        assertEquals(ID, YouTubeUrl.videoIdOf("http://youtu.be/$ID"))
    }

    @Test
    fun `shorts live embed 경로에서 ID 추출`() {
        assertEquals(ID, YouTubeUrl.videoIdOf("https://www.youtube.com/shorts/$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://www.youtube.com/live/$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://www.youtube.com/embed/$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("https://www.youtube.com/v/$ID"))
    }

    @Test
    fun `스킴 생략과 공백 입력 허용`() {
        assertEquals(ID, YouTubeUrl.videoIdOf("www.youtube.com/watch?v=$ID"))
        assertEquals(ID, YouTubeUrl.videoIdOf("//youtu.be/$ID")) // 프로토콜 상대 링크
        assertEquals(
            ID,
            YouTubeUrl.videoIdOf("  https://youtu.be/$ID   여름 연습곡 후보 "),
        )
        assertEquals(ID, YouTubeUrl.videoIdOf(ID))
    }

    @Test
    fun `대소문자 혼용 도메인과 쿼리 우선순위`() {
        assertEquals(ID, YouTubeUrl.videoIdOf("HTTPS://WWW.YOUTUBE.COM/WATCH?V=$ID"))
        // 유효하지 않은 첫 v 뒤에 유효한 값이 와도 첫 v는 버리고 탐색 지속
        assertNull(YouTubeUrl.videoIdOf("https://www.youtube.com/watch?list=abc&v=zzzzz"))
    }

    @Test
    fun `잘못된 ID는 거부`() {
        // 10자 / 12자
        assertNull(YouTubeUrl.videoIdOf("https://www.youtube.com/watch?v=abcdefghij"))
        assertNull(YouTubeUrl.videoIdOf("https://www.youtube.com/watch?v=abcdefghijkl"))
        // 경로 세그먼트 길이 미달
        assertNull(YouTubeUrl.videoIdOf("https://www.youtube.com/shorts/abc"))
        // 특수문자 포함
        assertNull(YouTubeUrl.videoIdOf("dQw4w9WgXc!"))
    }

    @Test
    fun `유튜브 외 URL은 거부`() {
        assertNull(YouTubeUrl.videoIdOf("https://vimeo.com/12345678901"))
        assertNull(YouTubeUrl.videoIdOf("https://example.com/watch?v=$ID"))
        assertNull(YouTubeUrl.videoIdOf("javascript:alert(1)"))
        assertNull(YouTubeUrl.videoIdOf(""))
        assertNull(YouTubeUrl.videoIdOf("hello world"))
        assertNull(YouTubeUrl.videoIdOf("https://www.youtube.com/watch")) // v 파라미터 없음
        assertNull(YouTubeUrl.videoIdOf("https://www.youtube.com/channel/UC1234567890"))
    }

    @Test
    fun `AAC 컨테이너를 Opus보다 우선 선택`() {
        // opus가 더 높은 비트레이트여도 MediaCodec/MMR 호환성 우선으로 m4a 선택
        val chosen = AudioChooser.choose(
            listOf(
                AudioCandidate("u-opus", "audio/webm", 160),
                AudioCandidate("u-m4a", "audio/mp4; codecs=\"mp4a.40.2\"", 129),
            ),
        )
        assertEquals("u-m4a", chosen?.url)
    }

    @Test
    fun `같은 등급 내에서는 더 높은 비트레이트 선택`() {
        val chosen = AudioChooser.choose(
            listOf(
                AudioCandidate("a", "audio/mp4", 128),
                AudioCandidate("b", "audio/mp4", 256),
                AudioCandidate("c", "audio/webm", 128),
            ),
        )
        assertEquals("b", chosen?.url)
    }

    @Test
    fun `매니페스트 전용 스트림은 진행형 다운로드보다 후순위`() {
        val chosen = AudioChooser.choose(
            listOf(
                // DASH/HLS 매니페스트(단일 파일 다운로드 불가) — MIME·비트레이트가 더 좋아도 밀림
                AudioCandidate("dash", "audio/mp4", 256, progressiveHttp = false),
                AudioCandidate("prog-opus", "audio/webm", 128),
            ),
        )
        assertEquals("prog-opus", chosen?.url)
    }

    @Test
    fun `URL 없는 후보와 빈 목록은 안전하게 처리`() {
        val chosen = AudioChooser.choose(
            listOf(AudioCandidate(null, "audio/mp4", 256), AudioCandidate("", "audio/mp4", 128)),
        )
        assertNull(chosen)
        assertNull(AudioChooser.choose(emptyList()))
    }

    @Test
    fun `MIME 미상 후보는 최하 등급으로 폴백`() {
        val chosen = AudioChooser.choose(
            listOf(
                AudioCandidate("unknown", "audio/x-custom", 320),
                AudioCandidate("opus", "audio/webm", 160),
            ),
        )
        // webm(opus)과 미상 MIME 모두 rank 0 → 비트레이트 우선
        assertEquals("unknown", chosen?.url)
    }
}
