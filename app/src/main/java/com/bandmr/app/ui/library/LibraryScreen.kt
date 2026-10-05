package com.bandmr.app.ui.library

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.bandmr.app.Locator
import com.bandmr.app.R
import com.bandmr.app.audio.MixCache
import com.bandmr.app.data.AppDesign
import com.bandmr.app.data.Song
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import com.bandmr.app.separation.Tier
import com.bandmr.app.ui.components.StatusBadge
import com.bandmr.app.ui.components.TrackArtwork
import com.bandmr.app.ui.theme.LocalAppDesign
import com.bandmr.app.youtube.ImportState
import com.bandmr.app.youtube.YouTubeImport
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Library"

@Composable
fun LibraryScreen(onOpenSong: (Long) -> Unit) {
    val songs by Locator.songDao.observeAll().collectAsState(initial = emptyList())
    val design = LocalAppDesign.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Song?>(null) }
    val importState by YouTubeImport.state.collectAsState()
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var separatedOnly by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val visibleSongs = remember(songs, query, separatedOnly) {
        songs.filter { (!separatedOnly || it.isSeparated) && it.title.contains(query.trim(), ignoreCase = true) }
    }

    fun revealAddedSong() {
        query = ""
        separatedOnly = false
        focusManager.clearFocus()
        scope.launch { listState.scrollToItem(0) }
    }

    // 다이얼로그를 닫고 기다렸어도 새 곡이 검색·분리 필터에 가려지지 않게 한다.
    LaunchedEffect(importState) {
        if (importState is ImportState.Done) {
            revealAddedSong()
            if (showLinkDialog) delay(600)
            showLinkDialog = false
            YouTubeImport.dismiss()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val newId = try {
                    Locator.context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    val (title, durationMs) = readMetadata(uri)
                    Locator.songDao.insert(
                        Song(title = title, uri = uri.toString(), durationMs = durationMs)
                    )
                } catch (ce: CancellationException) {
                    throw ce // 취소는 실패가 아니다 (YouTubeImport와 같은 규약)
                } catch (t: Throwable) {
                    // 등록이 실패하면 목록에 아무것도 안 생긴다 — 조용히 넘기면
                    // 사용자는 곡이 왜 없는지 알 수 없다(권한 획득·메타데이터 읽기 실패 등)
                    Log.w(TAG, "곡 추가 실패", t)
                    snackbar.showSnackbar("곡을 추가하지 못했습니다: ${t.message ?: "알 수 없는 오류"}")
                    return@launch
                }
                revealAddedSong()
                // 첫 재생이 바로 되도록 원본을 앱 내부 WAV 캐시로 미리 변환.
                // 캐시 실패는 재생 시점 prepareFailedSongId로 노출되므로 여기선 로그만 남긴다
                withContext(Dispatchers.IO) {
                    if (!MixCache.cacheFile(Locator.context, newId).exists()) {
                        runCatching { MixCache.prepare(Locator.context, newId, uri) }
                            .onFailure { Log.w(TAG, "곡 $newId 캐시 준비 실패", it) }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            linkUrl = ""
                            YouTubeImport.dismiss()
                            showLinkDialog = true
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(painterResource(R.drawable.ic_link), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("링크로 추가", Modifier.padding(start = 8.dp))
                    }
                    Button(
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text("곡 추가", Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (songs.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            TrackArtwork(seed = 0, size = 112.dp)
                            Spacer(Modifier.height(4.dp))
                            Text("첫 곡으로 시작해 볼까요", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                            Text(
                                "음악 파일이나 유튜브 링크를 추가하고\n연습할 악기의 소리를 조절해 보세요.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            StatusBadge("보컬부터 드럼, 기타까지", active = true)
                        }
                    }
                }
                item {
                    Text(
                        "곡을 추가한 뒤 바로 연습하거나, AI 분리로 악기별 볼륨을 더 세밀하게 조절할 수 있어요.",
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("연습할 곡 찾기") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "검색어 지우기")
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
                item {
                    val chipColors = if (
                        design == AppDesign.SNOW || design == AppDesign.INK || design == AppDesign.MOSS
                    ) {
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        FilterChipDefaults.filterChipColors()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !separatedOnly, onClick = { separatedOnly = false }, label = { Text("전체 ${songs.size}") }, colors = chipColors)
                        FilterChip(selected = separatedOnly, onClick = { separatedOnly = true }, label = { Text("AI 분리 완료 ${songs.count { it.isSeparated }}") }, colors = chipColors)
                    }
                }
                if (visibleSongs.isEmpty()) {
                    item {
                        Text(
                            if (query.isNotBlank()) "검색 결과가 없어요. 다른 곡 이름을 입력해 주세요."
                            else "아직 분리한 곡이 없어요. 곡을 열어 AI 분리를 시작해 보세요.",
                            Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(visibleSongs, key = { it.id }) { song ->
                    SongRow(song = song, onClick = { onOpenSong(song.id) }, onDelete = { pendingDelete = song })
                }
            }
        }
    }

    if (showLinkDialog) {
        val busy = YouTubeImport.isRunning()
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
    var menuOpen by remember { mutableStateOf(false) }
    val design = LocalAppDesign.current
    val openRow = design == AppDesign.MONO || design == AppDesign.BLUE || design == AppDesign.SNOW || design == AppDesign.MOSS
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (design == AppDesign.SNOW) 2.dp else 0.dp,
        border = if (openRow) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.padding(
                    start = if (design == AppDesign.MONO) 0.dp else 14.dp,
                    end = 4.dp,
                    top = if (design == AppDesign.BLUE) 18.dp else 14.dp,
                    bottom = if (design == AppDesign.BLUE) 18.dp else 14.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(
                    seed = song.id,
                    size = when (design) {
                        AppDesign.MONO, AppDesign.SNOW, AppDesign.INK -> 48.dp
                        AppDesign.BLUE -> 72.dp
                        else -> 56.dp
                    },
                    label = if (
                        design == AppDesign.SNOW || design == AppDesign.INK || design == AppDesign.MOSS
                    ) song.title else null,
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatDuration(song.durationMs) + if (song.isSeparated) " · AI ${Tier.fromId(song.separatedTier).chipLabel}" else " · 원본 오디오",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (song.isSeparated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "${song.title} 더보기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("곡 삭제", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            if (design == AppDesign.MONO) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
