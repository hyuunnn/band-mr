package com.bandmr.app.youtube

import com.bandmr.app.data.Song
import com.bandmr.app.data.SongDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 영상 ID까지의 원본 URI 접두사로 기존 곡을 찾아 포맷 확장자가 바뀌어도 재사용한다.
 * 새 곡의 캐시 준비가 실패·취소되면 이번 등록과 캐시만 되돌린다. 완성 다운로드는 재시도에
 * 쓰도록 보존한다. insert의 반환 직전 취소로 ID를 잃지 않게 DB 등록만 짧게 보호한다.
 */
internal suspend fun importYouTubeSong(
    dao: SongDao,
    sourceUriPrefix: String,
    downloadSong: suspend () -> Song,
    prepareCache: suspend (Song) -> Unit,
    rollback: suspend (Song) -> Unit,
): Song {
    coroutineContext.ensureActive()
    val existing = dao.getAllOnce().firstOrNull { it.uri.startsWith(sourceUriPrefix) }
    if (existing != null) {
        prepareCache(existing)
        coroutineContext.ensureActive()
        return existing
    }

    val downloaded = downloadSong()
    var inserted: Song? = null
    try {
        coroutineContext.ensureActive()
        withContext(NonCancellable) {
            inserted = downloaded.copy(id = dao.insert(downloaded))
        }
        coroutineContext.ensureActive()
        val song = requireNotNull(inserted)
        prepareCache(song)
        coroutineContext.ensureActive()
        return song
    } catch (failure: Throwable) {
        inserted?.let { song ->
            withContext(NonCancellable) {
                try {
                    rollback(song)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
        }
        throw failure
    }
}
