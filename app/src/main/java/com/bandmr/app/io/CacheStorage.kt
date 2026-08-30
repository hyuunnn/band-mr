package com.bandmr.app.io

import java.io.File

/**
 * 캐시 저장공간 집계·정리. 순수 파일 연산이라 [File] 루트만 받는다(Context 의존 없음 → JVM 테스트 가능).
 *
 * 왜 필요한가: 파이프라인이 44.1kHz 스테레오 PCM16 고정이라 초당 176,400바이트다.
 * 4분 곡 하나가 원본 캐시 약 40MiB, 스템 6개 약 242MiB를 차지한다. `cleanUpOrphans`는 DB에서
 * 사라진 곡의 것만 지우므로 목록에 남아 있는 곡의 캐시는 영구히 쌓이고, 그때까지 사용자가
 * 용량을 확인하거나 비울 방법이 없었다 — 그 진입점이다.
 */
object CacheStorage {

    /**
     * 쓰는 중일 수 있는 임시 산출물. 정리 대상에서 제외한다 —
     * MixCache의 `.wav.part`, 파형 캐시의 `.peaks.tmp`, 모델의 `.tmp`가 여기 걸린다.
     */
    private fun isInFlight(file: File): Boolean =
        file.name.endsWith(".part") || file.name.endsWith(".tmp")

    // 정리 대상 선정은 아래 두 함수에만 있다. 크기 집계와 실제 삭제가 이걸 공유해야
    // "표시는 남았는데 눌러도 0B"가 구조적으로 불가능해진다 — 술어를 양쪽에 따로 쓰던
    // 것이 바로 직전 버그의 원인이었다(한쪽만 `.part`를 걸렀다).

    private fun clearableFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && !isInFlight(it) } ?: emptyList()

    private fun clearableSubdirs(dir: File, includeInFlight: Boolean): List<File> =
        dir.listFiles()?.filter { it.isDirectory && (includeInFlight || !isInFlight(it)) }
            ?: emptyList()

    /**
     * [dir] 아래 모든 파일 크기 합(쓰는 중인 것 포함). 디렉터리가 없으면 0.
     *
     * 화면 표시·버튼 활성 기준으로 쓰면 안 되므로 공개하지 않는다 — 정리는 임시 산출물을
     * 건너뛰기 때문에 "용량은 남았는데 눌러도 0B"가 된다. 그쪽은 [clearableFileSize] /
     * [clearableSubdirectorySize]를 쓸 것.
     */
    internal fun dirSize(dir: File): Long =
        if (!dir.isDirectory) 0L
        else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** [clearFiles]가 실제로 지울 바이트 수. 표시·활성 판정용 */
    fun clearableFileSize(dir: File): Long =
        clearableFiles(dir).sumOf { it.length() }

    /** [clearSubdirectories]가 실제로 지울 바이트 수. [includeInFlight]는 그쪽과 같은 의미다 */
    fun clearableSubdirectorySize(dir: File, includeInFlight: Boolean = false): Long =
        clearableSubdirs(dir, includeInFlight).sumOf { dirSize(it) }

    /**
     * [dir] **바로 아래의 파일**만 지운다(하위 디렉터리는 건드리지 않는다).
     * MixCache처럼 파일이 평평하게 놓인 디렉터리용.
     * @return 실제로 지운 바이트 수 — [clearableFileSize]와 같아야 한다
     */
    fun clearFiles(dir: File): Long = clearableFiles(dir).sumOf { f ->
        val size = f.length() // 삭제 전에 읽어야 한다
        if (f.delete()) size else 0L
    }

    /**
     * [dir] 바로 아래의 **하위 디렉터리**를 통째로 지운다. 스템처럼 곡별 폴더가 놓인 구조용.
     *
     * [includeInFlight]가 false면 `.part` 디렉터리(분리 진행 중 산출물)를 건너뛴다.
     * "분리 결과 전체 삭제"처럼 호출부가 먼저 분리를 취소했다면 true로 넘길 것 — 남겨두면
     * 취소가 세그먼트 경계에서만 판정되는 탓에 방금 끝난 분리가 승격되어, DB는 미분리인데
     * 스템 디렉터리만 살아 있는 고아가 된다(유효한 songId라 `cleanUpOrphans`도 못 지운다).
     * @return 실제로 지운 바이트 수 — [clearableSubdirectorySize]와 같아야 한다
     */
    fun clearSubdirectories(dir: File, includeInFlight: Boolean = false): Long =
        clearableSubdirs(dir, includeInFlight).sumOf { d ->
            val size = dirSize(d) // 삭제 전에 읽어야 한다
            if (d.deleteRecursively()) size else 0L
        }

    /** 사람이 읽는 크기. 소수 첫째 자리까지 (예: `1.5GB`, `242.0MB`, `0B`) */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0B"
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        // 로케일 무관 — 소수점 문자가 바뀌면 표시가 깨진다
        return if (unit == 0) "${bytes}B"
        else String.format(java.util.Locale.US, "%.1f%s", value, units[unit])
    }
}
