package com.bandmr.app.youtube

import com.bandmr.app.data.Song
import com.bandmr.app.data.SongDao
import com.bandmr.app.separation.withStemMutation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * 같은 영상을 다시 추가해도 설정을 따로 둘 수 있게 새 곡으로 등록한다. 원본 재사용과
 * 누락 시 재다운로드는 downloadSong에서 처리한다. 캐시 준비가 실패·취소되면 이번 등록과
 * 캐시만 되돌리고 완성 다운로드는 재시도에 쓰도록 보존한다. insert의 반환 직전 취소로
 * ID를 잃지 않게 DB 등록만 짧게 보호한다. 원본 재사용 결정과 등록 사이에 마지막 기존
 * 곡이 삭제될 수 있으므로, 삭제와 같은 잠금 안에서 파일을 재확인하고 행을 등록한다.
 * 그 사이 원본이 사라졌으면 잠금을 놓고 다시 다운로드한다.
 */
internal suspend fun importYouTubeSong(
    dao: SongDao,
    downloadSong: suspend () -> Song,
    prepareCache: suspend (Song) -> Unit,
    rollback: suspend (Song) -> Unit,
): Song {
    coroutineContext.ensureActive()
    var inserted: Song? = null
    try {
        while (inserted == null) {
            coroutineContext.ensureActive()
            val downloaded = downloadSong()
            coroutineContext.ensureActive()
            withStemMutation {
                if (File(URI(downloaded.uri)).isFile) {
                    inserted = downloaded.copy(id = dao.insert(downloaded))
                }
            }
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
