package com.bandmr.app.youtube

import com.bandmr.app.data.Song
import com.bandmr.app.data.SongDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 같은 영상을 다시 추가해도 설정을 따로 둘 수 있게 새 곡으로 등록한다. 원본 재사용과
 * 누락 시 재다운로드는 downloadSong에서 처리한다. 캐시 준비가 실패·취소되면 이번 등록과
 * 캐시만 되돌리고 완성 다운로드는 재시도에 쓰도록 보존한다. insert의 반환 직전 취소로
 * ID를 잃지 않게 DB 등록만 짧게 보호한다.
 */
internal suspend fun importYouTubeSong(
    dao: SongDao,
    downloadSong: suspend () -> Song,
    prepareCache: suspend (Song) -> Unit,
    rollback: suspend (Song) -> Unit,
): Song {
    coroutineContext.ensureActive()
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
