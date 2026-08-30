package com.bandmr.app.ui.library

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bandmr.app.Locator
import com.bandmr.app.R
import com.bandmr.app.audio.MixCache
import com.bandmr.app.data.Song
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import com.bandmr.app.youtube.ImportState
import com.bandmr.app.youtube.YouTubeImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Library"

@Composable
fun LibraryScreen(onOpenSong: (Long) -> Unit) {
    val songs by Locator.songDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Song?>(null) }
    val importState by YouTubeImport.state.collectAsState()
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }

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
                    // 첫 재생이 바로 되도록 원본을 앱 내부 WAV 캐시로 미리 변환.
                    // 실패는 재생 시점 prepareFailedSongId로 노출되지만, 원인 추적을 위해 로그를 남긴다
                    withContext(Dispatchers.IO) {
                        if (!MixCache.cacheFile(Locator.context, newId).exists()) {
                            runCatching { MixCache.prepare(Locator.context, newId, uri) }
                                .onFailure { Log.w(TAG, "곡 $newId 캐시 준비 실패", it) }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        linkUrl = ""
                        YouTubeImport.dismiss() // 이전 성공/실패 메시지 잔존 방지
                        showLinkDialog = true
                    },
                    icon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) },
                    text = { Text("링크로 추가") },
                )
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("audio/*")) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("곡 추가") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (songs.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(painterResource(R.drawable.ic_music_note), contentDescription = null)
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

    if (showLinkDialog) {
        val busy = YouTubeImport.isRunning()
        LaunchedEffect(importState) {
            if (importState is ImportState.Done) {
                delay(600)
                showLinkDialog = false
                YouTubeImport.dismiss()
            }
        }
        AlertDialog(
            onDismissRequest = {
                // 진행 중이어도 다이얼로그만 닫으면 백그라운드(appScope)에서 계속 진행된다
                showLinkDialog = false
                YouTubeImport.dismiss()
            },
            title = { Text("유튜브 링크로 곡 추가") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = linkUrl,
                        onValueChange = { linkUrl = it },
                        singleLine = true,
                        enabled = !busy,
                        placeholder = { Text("https://youtu.be/… 또는 watch?v=…") },
                    )
                    when (val st = importState) {
                        is ImportState.Resolving ->
                            StatusRow(text = "영상 정보를 가져오는 중…")
                        is ImportState.Downloading -> {
                            val p = st.progress
                            if (p != null) {
                                LinearProgressIndicator(
                                    progress = { p },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            Text(
                                buildString {
                                    append(st.title)
                                    val mb = st.receivedBytes / (1024 * 1024)
                                    // progress와 totalBytes는 항상 동행한다(불명 크기면 둘 다 null)
                                    if (p != null) {
                                        append(" · ${(p * 100).toInt()}%")
                                    } else if (mb > 0) {
                                        append(" · $mb MB")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is ImportState.PreparingCache ->
                            StatusRow(text = "'${st.title}' 재생 캐시 준비 중…")
                        is ImportState.Failed -> Text(
                            st.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ImportState.Idle -> Unit
                        is ImportState.Done -> Unit
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = linkUrl.isNotBlank() && !busy,
                    onClick = { YouTubeImport.start(linkUrl) },
                ) {
                    Text(if (busy) "진행 중…" else "추가")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) {
                        TextButton(onClick = {
                            YouTubeImport.cancel()
                            showLinkDialog = false
                        }) { Text("중단") }
                    }
                    TextButton(onClick = {
                        showLinkDialog = false
                        YouTubeImport.dismiss()
                    }) { Text("닫기") }
                }
            },
        )
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
                        // 분리 진행 중이면 취소 (완료 후 스템이 고아로 남는 것 방지)
                        val sep = SepBus.state.value
                        if (sep is SepState.Running && sep.songId == song.id) {
                            SeparationService.cancel(Locator.context)
                        }
                        song.stemsDir?.let { withContext(Dispatchers.IO) { File(it).deleteRecursively() } }
                        MixCache.delete(Locator.context, song.id)
                        // 다른 곡이 참조하지 않는 파일 소스(유튜브 다운로드 원본 등) 정리.
                        // files/sources 아래 경로만 허용해 의도치 않은 삭제를 차단한다
                        val shared = Locator.songDao.getAllOnce()
                            .any { it.id != song.id && it.uri == song.uri }
                        if (!shared && song.uri.startsWith("file://")) {
                            val sourcesDir =
                                File(Locator.context.filesDir, "sources").canonicalFile
                            song.uri.toUri().path?.let { p ->
                                File(p).takeIf { it.canonicalFile.parentFile == sourcesDir }
                                    ?.delete()
                            }
                        }
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
private fun StatusRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_music_note), contentDescription = null)
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
