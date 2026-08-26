package com.bandmr.app.ui.library

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bandmr.app.Locator
import com.bandmr.app.audio.MixCache
import com.bandmr.app.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LibraryScreen(onOpenSong: (Long) -> Unit) {
    val songs by Locator.songDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Song?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    Locator.context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val (title, durationMs) = readMetadata(uri)
                    val newId = Locator.songDao.insert(
                        Song(title = title, uri = uri.toString(), durationMs = durationMs)
                    )
                    // 첫 재생이 바로 되도록 원본을 앱 내부 WAV 캐시로 미리 변환
                    withContext(Dispatchers.IO) {
                        if (!MixCache.cacheFile(Locator.context, newId).exists()) {
                            runCatching { MixCache.prepare(Locator.context, newId, uri) }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("곡 추가") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (songs.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                    Text("하단 버튼으로 연습할 곡을 추가하세요", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            onClick = { onOpenSong(song.id) },
                            onDelete = { pendingDelete = song },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("곡 삭제") },
            text = { Text("'${song.title}'을(를) 목록과 분리 캐시에서 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // 재생 중인 곡이면 먼저 정지·해제 (삭제된 파일 재생 방지)
                        if (Locator.playerController.currentSongId() == song.id) {
                            Locator.playerController.release()
                        }
                        song.stemsDir?.let { withContext(Dispatchers.IO) { File(it).deleteRecursively() } }
                        MixCache.delete(Locator.context, song.id)
                        Locator.songDao.delete(song)
                    }
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = formatDuration(song.durationMs) +
                        if (song.separatedTier != null) " · AI 분리됨" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "삭제")
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private suspend fun readMetadata(uri: Uri): Pair<String, Long> =
    withContext(Dispatchers.IO) {
        var title: String? = null
        var duration = 0L
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(Locator.context, uri)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }
        val fallbackName = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "제목 없음"
        (title?.takeIf { it.isNotBlank() } ?: fallbackName) to duration
    }

/** 제목 태그가 없는 파일(예: yt-dlp 변환본)을 위해 표시용 파일명을 조회한다 */
private fun queryDisplayName(uri: Uri): String? =
    runCatching {
        Locator.context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()
