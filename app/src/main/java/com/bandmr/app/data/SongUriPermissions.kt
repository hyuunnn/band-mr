package com.bandmr.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.bandmr.app.separation.withStemMutation

/** 권한 획득과 행 등록을 삭제와 직렬화해, 같은 URI를 다시 추가하는 중에 권한이 회수되지 않게 한다. */
internal suspend fun registerLocalSong(
    song: Song,
    dao: SongDao,
    takePermission: () -> Unit,
    releasePermission: (String) -> Unit,
): Long = withStemMutation {
    takePermission()
    try {
        dao.insert(song)
    } catch (failure: Throwable) {
        try {
            if (dao.getAllOnce().none { it.uri == song.uri }) releasePermission(song.uri)
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

/** 이전 버전이나 등록 실패가 남긴 권한도 정리한다. 참조 조회와 반납 사이의 새 등록을 막는다. */
internal suspend fun cleanUpUnusedSongPermissions(
    dao: SongDao,
    persistedUris: List<String>,
    releasePermission: (String) -> Unit,
) = withStemMutation {
    val referenced = dao.getAllOnce().mapTo(HashSet()) { it.uri }
    persistedUris.filter { it !in referenced }.forEach(releasePermission)
}

/** OS가 이미 회수한 권한은 정상적인 정리 완료로 취급한다. */
internal fun releaseSongReadPermission(context: Context, uri: String) {
    try {
        context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (missing: SecurityException) {
        Log.w("SongPermissions", "파일 접근 권한이 이미 없습니다: $uri", missing)
    }
}
