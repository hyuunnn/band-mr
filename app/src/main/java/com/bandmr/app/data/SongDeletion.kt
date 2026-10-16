package com.bandmr.app.data

import androidx.core.net.toUri
import com.bandmr.app.Locator
import com.bandmr.app.audio.MixCache
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import com.bandmr.app.separation.withStemMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 사용자 삭제와 임포트 롤백이 같은 재생·분리 종료 경로를 사용한다. 호출은 메인 스레드에서 한다. */
internal suspend fun deleteSong(song: Song, keepSource: Boolean = false) {
    val context = Locator.context
    if (Locator.playerController.currentSongId() == song.id) {
        Locator.playerController.release()
    }
    val separation = SepBus.state.value
    if (separation is SepState.Running && separation.songId == song.id) {
        SeparationService.cancel(context)
    }
    withContext(Dispatchers.IO) {
        deleteSong(song, Locator.songDao, File(context.filesDir, "sources"), keepSource) {
            MixCache.delete(context, song.id)
        }
    }
}

/**
 * IO 디스패처에서 곡의 파일과 DB 행을 함께 삭제한다. 파일 삭제를 시작한 뒤에는 취소로
 * DB 행만 남지 않도록 스템 확정과 같은 보호 구간을 쓰며, 진행 중인 스템 승격도 기다린다.
 * 화면을 떠나도 삭제 요청이 실행되도록 호출자는 앱 수명의 코루틴에서 실행한다.
 */
internal suspend fun deleteSong(
    song: Song,
    dao: SongDao,
    sourcesDir: File,
    keepSource: Boolean = false,
    deleteMixCache: () -> Unit,
) {
    withStemMutation {
        // 캐시 준비 중 사용자가 연 곡은 분리가 먼저 끝났을 수 있으므로 락 안에서 다시 읽는다.
        val latest = dao.get(song.id) ?: song
        latest.stemsDir?.let { File(it).deleteRecursively() }
        deleteMixCache()
        // 다른 곡이 참조하지 않는 앱 내부 다운로드 원본만 정리한다.
        if (!keepSource) {
            val shared = dao.getAllOnce().any { it.id != song.id && it.uri == latest.uri }
            if (!shared && latest.uri.startsWith("file://")) {
                val sourceRoot = sourcesDir.canonicalFile
                latest.uri.toUri().path?.let { path ->
                    File(path).takeIf { it.canonicalFile.parentFile == sourceRoot }?.delete()
                }
            }
        }
        dao.delete(song)
    }
}
