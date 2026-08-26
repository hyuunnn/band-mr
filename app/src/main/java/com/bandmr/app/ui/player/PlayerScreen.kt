package com.bandmr.app.ui.player

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bandmr.app.Locator
import com.bandmr.app.audio.PlayerController
import com.bandmr.app.data.Stem
import com.bandmr.app.export.Exporter
import com.bandmr.app.playback.PlaybackService
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(songId: Long) {
    val song by Locator.songDao.observe(songId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val ctrl = remember { Locator.playerController }

    val aiOn by Locator.settings.aiEnabled.collectAsState(initial = false)
    val sepState by SepBus.state.collectAsState()

    var muteMask by remember { mutableStateOf(0) }
    var semitones by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosMs by remember { mutableFloatStateOf(0f) }
    var posMs by remember { mutableStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 권한 여부와 무관하게 진행 (거부 시 알림만 숨김)
        SeparationService.start(Locator.context, songId)
    }
    val playbackNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        PlaybackService.start(Locator.context)
    }

    LaunchedEffect(song?.id, song?.separatedTier, aiOn) {
        val s = song ?: return@LaunchedEffect
        muteMask = s.muteMask
        semitones = s.semitones
        ctrl.ensureLoaded(s, aiOn, s.muteMask, s.semitones)
    }

    val playing by ctrl.isPlaying.collectAsState()

    // 백그라운드 재생: 재생 시작 시 포그라운드 서비스 기동 (알림 권한은 있으면 좋음)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val granted = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            Locator.context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            PlaybackService.start(Locator.context)
        } else {
            playbackNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            posMs = ctrl.positionMs()
            delay(200)
        }
    }

    val s = song ?: return
    val separated = s.isSeparated
    val running = sepState is SepState.Running && (sepState as SepState.Running).songId == songId
    val sepProgress = (sepState as? SepState.Running)
    val loadedDurationMs by ctrl.durationMs.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(s.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)

        TransportCard(
            ctrl = ctrl,
            durationMs = if (loadedDurationMs > 0) loadedDurationMs else s.durationMs,
            posMs = posMs,
            dragging = dragging,
            dragPosMs = dragPosMs,
            onDraggingChange = { dragging = it },
            onDrag = { dragPosMs = it },
            onDragEnd = { ctrl.seekTo(dragPosMs.toLong()); dragging = false },
        )

        ModeCard(
            aiOn = aiOn,
            separated = separated,
            running = running,
            stage = sepProgress?.stage,
            progress = sepProgress?.progress,
            error = (sepState as? SepState.Error)?.message,
            onToggleAi = { enabled -> scope.launch { Locator.settings.setAiEnabled(enabled) } },
            onStartSeparation = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    SeparationService.start(Locator.context, songId)
                }
            },
            onCancelSeparation = { SeparationService.cancel(Locator.context) },
        )

        StemCard(
            separated = separated && aiOn,
            muteMask = muteMask,
            onToggle = { stem, checked ->
                val newMask = if (checked) muteMask or stem.bit else muteMask and stem.bit.inv()
                muteMask = newMask
                ctrl.setMuteMask(newMask)
                scope.launch {
                    Locator.songDao.get(songId)?.let {
                        Locator.songDao.update(it.copy(muteMask = newMask))
                    }
                }
            },
        )

        PitchCard(
            semitones = semitones,
            onChange = { v ->
                semitones = v
                ctrl.setSemitones(v)
                scope.launch {
                    Locator.songDao.get(songId)?.let {
                        Locator.songDao.update(it.copy(semitones = v))
                    }
                }
            },
        )

        ExportCard(
            song = s,
            aiOn = aiOn,
            separated = separated,
            muteMask = muteMask,
            semitones = semitones,
            exporting = exporting,
            exportMsg = exportMsg,
            setExporting = { exporting = it },
            setExportMsg = { exportMsg = it },
        )
    }
}

@Composable
private fun TransportCard(
    ctrl: PlayerController,
    durationMs: Long,
    posMs: Long,
    dragging: Boolean,
    dragPosMs: Float,
    onDraggingChange: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val isPlaying by ctrl.isPlaying.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Slider(
                value = when {
                    dragging -> dragPosMs
                    durationMs > 0 -> posMs.toFloat().coerceIn(0f, durationMs.toFloat())
                    else -> 0f
                },
                onValueChange = {
                    if (!dragging) onDraggingChange(true)
                    onDrag(it)
                },
                onValueChangeFinished = onDragEnd,
                valueRange = 0f..maxOf(1f, durationMs.toFloat()),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(posMs), style = MaterialTheme.typography.labelMedium)
                FilledIconButton(
                    onClick = { ctrl.playPause() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "재생/일시정지",
                    )
                }
                Text(
                    formatTime(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun ModeCard(
    aiOn: Boolean,
    separated: Boolean,
    running: Boolean,
    stage: String?,
    progress: Float?,
    error: String?,
    onToggleAi: (Boolean) -> Unit,
    onStartSeparation: () -> Unit,
    onCancelSeparation: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI 고음질 분리", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (aiOn) "온디바이스 AI로 정확하게 분리 (배터리 많이 사용)"
                        else "실시간 신호처리만 사용 · 절전 모드",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = aiOn, onCheckedChange = onToggleAi, enabled = !running)
            }

            if (aiOn && !separated) {
                if (running) {
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stage ?: "준비 중…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCancelSeparation) { Text("취소") }
                } else {
                    Button(onClick = onStartSeparation) { Text("이 곡 분리하기") }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (aiOn && separated) {
                Text("✓ 분리 완료 — 체크한 스템이 정확히 제거됩니다", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StemCard(
    separated: Boolean,
    muteMask: Int,
    onToggle: (Stem, Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("제거할 소리", style = MaterialTheme.typography.titleMedium)
            Text(
                if (separated) "AI 분리 결과에서 정확히 제거됩니다"
                else "체크하면 해당 소리가 제거됩니다 (실시간 근사 처리)",
                style = MaterialTheme.typography.bodySmall,
            )
            Stem.entries.forEach { stem ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = muteMask and stem.bit != 0,
                        onCheckedChange = { onToggle(stem, it) },
                    )
                    Column {
                        Text(stem.label, style = MaterialTheme.typography.bodyLarge)
                        if (!separated) {
                            Text(stem.dspHint, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PitchCard(semitones: Int, onChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("키 조절", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(0) }) { Text("초기화") }
            }
            Text(
                if (semitones == 0) "원곡 키" else "${if (semitones > 0) "+" else ""}$semitones 반음",
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onChange((semitones - 12).coerceIn(-12, 12)) }) {
                    Text("-1옥")
                }
                Slider(
                    value = semitones.toFloat(),
                    onValueChange = { onChange(it.toInt()) },
                    valueRange = -12f..12f,
                    steps = 23,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                OutlinedButton(onClick = { onChange((semitones + 12).coerceIn(-12, 12)) }) {
                    Text("+1옥")
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    song: com.bandmr.app.data.Song,
    aiOn: Boolean,
    separated: Boolean,
    muteMask: Int,
    semitones: Int,
    exporting: Boolean,
    exportMsg: String?,
    setExporting: (Boolean) -> Unit,
    setExportMsg: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val exporter = remember { Locator.exporter }

    val mixLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        if (uri != null) {
            setExporting(true)
            setExportMsg(null)
            scope.launch {
                runCatching {
                    exporter.exportMix(song, muteMask, semitones, aiOn, uri)
                }.onSuccess { setExportMsg("저장 완료") }
                    .onFailure { setExportMsg("실패: ${it.message}") }
                setExporting(false)
            }
        }
    }

    val stemsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            setExporting(true)
            setExportMsg(null)
            scope.launch {
                runCatching {
                    val n = exporter.exportStems(song, uri)
                    "스템 ${n}개 저장 완료"
                }.onSuccess { setExportMsg(it) }
                    .onFailure { setExportMsg("실패: ${it.message}") }
                setExporting(false)
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("내보내기", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { mixLauncher.launch("${Exporter.safeName(song.title)}_edited.wav") },
                    enabled = !exporting,
                ) { Text("현재 설정으로 저장") }
                OutlinedButton(
                    onClick = { stemsLauncher.launch(null) },
                    enabled = !exporting && separated,
                ) { Text("스템 개별 저장") }
            }
            if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            exportMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
