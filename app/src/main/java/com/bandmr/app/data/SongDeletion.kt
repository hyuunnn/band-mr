package com.bandmr.app.data

import androidx.core.net.toUri
import com.bandmr.app.separation.withStemMutation
import java.io.File

/**
 * IO 디스패처에서 곡의 파일과 DB 행을 함께 삭제한다. 파일 삭제를 시작한 뒤에는 취소로
 * DB 행만 남지 않도록 스템 확정과 같은 보호 구간을 쓰며, 진행 중인 스템 승격도 기다린다.
 * 화면을 떠나도 삭제 요청이 실행되도록 호출자는 앱 수명의 코루틴에서 실행한다.
 */
internal suspend fun deleteSong(
    song: Song,
    dao: SongDao,
    sourcesDir: File,
    deleteMixCache: () -> Unit,
) {
    withStemMutation {
        song.stemsDir?.let { File(it).deleteRecursively() }
        deleteMixCache()
        // 다른 곡이 참조하지 않는 앱 내부 다운로드 원본만 정리한다.
        val shared = dao.getAllOnce().any { it.id != song.id && it.uri == song.uri }
        if (!shared && song.uri.startsWith("file://")) {
            val sourceRoot = sourcesDir.canonicalFile
            song.uri.toUri().path?.let { path ->
                File(path).takeIf { it.canonicalFile.parentFile == sourceRoot }?.delete()
            }
        }
        dao.delete(song)
    }
}
