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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bandmr.app.Locator
import com.bandmr.app.audio.PlaybackSkip
import com.bandmr.app.audio.PlaybackSpeed
import com.bandmr.app.audio.PlayerController
import com.bandmr.app.data.Stem
import com.bandmr.app.export.Exporter
import com.bandmr.app.playback.PlaybackService
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(songId: Long) {
    val song by Locator.songDao.observe(songId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val ctrl = remember { Locator.playerController }

    val aiOn by Locator.settings.aiEnabled.collectAsState(initial = false)
    val sepState by SepBus.state.collectAsState()

    var muteMask by remember { mutableIntStateOf(0) }
    var semitones by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(PlaybackSpeed.DEFAULT) }
    var vocalStrength by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosMs by remember { mutableFloatStateOf(0f) }
    var posMs by remember { mutableLongStateOf(0L) }
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

    val preparingSongId by ctrl.preparingSongId.collectAsState()
    val prepareFailedSongId by ctrl.prepareFailedSongId.collectAsState()

    LaunchedEffect(song?.id, song?.separatedTier, aiOn) {
        val s = song ?: return@LaunchedEffect
        muteMask = s.muteMask
        semitones = s.semitones
        speed = PlaybackSpeed.snap(s.speed)
        ctrl.ensureLoaded(s, aiOn, s.muteMask, s.semitones, speed)
    }

    // 저장된 보컬 제거 강도 로드 후 컨트롤러에 반영
    LaunchedEffect(Unit) {
        vocalStrength = Locator.settings.vocalStrength.first()
        ctrl.setVocalStrength(vocalStrength)
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

        if (preparingSongId == songId && !separated) {
            Text(
                "원본을 기기에 맞게 준비하는 중… (수 초 소요)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (prepareFailedSongId == songId) {
            Text(
                "원본 준비에 실패했습니다. 재생 버튼을 누르면 다시 시도합니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TransportCard(
            ctrl = ctrl,
            durationMs = if (loadedDurationMs > 0) loadedDurationMs else s.durationMs,
            posMs = posMs,
            dragging = dragging,
            dragPosMs = dragPosMs,
            onDraggingChange = { dragging = it },
            onDrag = { dragPosMs = it },
            onDragEnd = { ctrl.seekTo(dragPosMs.toLong()); dragging = false },
            onSkip = { delta ->
                dragging = false
                ctrl.skipBy(delta)
                posMs = ctrl.positionMs()
            },
        )

        ModeCard(
            aiOn = aiOn,
            separated = separated,
            running = running,
            stage = sepProgress?.stage,
            progress = sepProgress?.progress,
            error = (sepState as? SepState.Error)?.takeIf { it.songId == songId }?.message,
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
            vocalStrength = vocalStrength,
            onVocalStrengthChange = { v ->
                vocalStrength = v
                ctrl.setVocalStrength(v) // 재생 중 즉시 반영
            },
            onVocalStrengthDone = {
                scope.launch { Locator.settings.setVocalStrength(vocalStrength) }
            },
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

        SpeedCard(
            speed = speed,
            onChange = { v ->
                val snapped = PlaybackSpeed.snap(v)
                // 슬라이더 드래그는 이벤트가 잦으므로 스냅 값이 실제로 바뀔 때만 반영/저장
                if (snapped != speed) {
                    speed = snapped
                    ctrl.setSpeed(snapped)
                    scope.launch {
                        Locator.songDao.get(songId)?.let {
                            Locator.songDao.update(it.copy(speed = snapped))
                        }
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
            vocalStrength = vocalStrength,
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
    onSkip: (Long) -> Unit,
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(posMs), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSkip(-PlaybackSkip.LARGE_MS) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "10초 뒤로")
                }
                IconButton(onClick = { onSkip(-PlaybackSkip.SMALL_MS) }) {
                    Icon(Icons.Filled.Replay5, contentDescription = "5초 뒤로")
                }
                FilledIconButton(onClick = { ctrl.playPause() }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "재생/일시정지",
                    )
                }
                IconButton(onClick = { onSkip(PlaybackSkip.SMALL_MS) }) {
                    Icon(Icons.Filled.Forward5, contentDescription = "5초 앞으로")
                }
                IconButton(onClick = { onSkip(PlaybackSkip.LARGE_MS) }) {
                    Icon(Icons.Filled.Forward10, contentDescription = "10초 앞으로")
                }
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
    vocalStrength: Float,
    onVocalStrengthChange: (Float) -> Unit,
    onVocalStrengthDone: () -> Unit,
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
                // AI 분리 전용 스템(피아노 등)은 AI OFF에서 비활성화
                val enabled = separated || !stem.aiOnly
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = muteMask and stem.bit != 0,
                        onCheckedChange = { onToggle(stem, it) },
                        enabled = enabled,
                    )
                    Column {
                        Text(
                            stem.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!separated) {
                            Text(stem.dspHint, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                // AI OFF에서 보컬 뮤트 중일 때만 강도 조절 노출 (재생 중 실시간 반영)
                if (stem == Stem.VOCAL && !separated && muteMask and stem.bit != 0) {
                    Column(Modifier.padding(start = 48.dp, end = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "제거 강도",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${(vocalStrength * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Slider(
                            value = vocalStrength,
                            onValueChange = onVocalStrengthChange,
                            onValueChangeFinished = onVocalStrengthDone,
                            valueRange = 0f..1f,
                        )
                        Text(
                            "낮음 = 반주 손상 적음 · 높음 = 보컬 최대 제거",
                            style = MaterialTheme.typography.labelSmall,
                        )
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
                OutlinedButton(onClick = { onChange((semitones - 1).coerceIn(-12, 12)) }) {
                    Text("-1")
                }
                Slider(
                    value = semitones.toFloat(),
                    onValueChange = { onChange(it.toInt()) },
                    valueRange = -12f..12f,
                    steps = 23,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                OutlinedButton(onClick = { onChange((semitones + 1).coerceIn(-12, 12)) }) {
                    Text("+1")
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(speed: Float, onChange: (Float) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("속도 조절", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(PlaybackSpeed.DEFAULT) }) { Text("초기화") }
            }
            Text(
                PlaybackSpeed.formatLabel(speed),
                style = MaterialTheme.typography.headlineSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onChange(PlaybackSpeed.step(speed, -1)) }) {
                    Text("−")
                }
                Slider(
                    value = speed,
                    onValueChange = onChange,
                    valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                    steps = PlaybackSpeed.sliderSteps,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                OutlinedButton(onClick = { onChange(PlaybackSpeed.step(speed, 1)) }) {
                    Text("+")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { onChange(PlaybackSpeed.MIN) }) {
                    Text("0.25×", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onChange(PlaybackSpeed.DEFAULT) }) {
                    Text("1×", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onChange(PlaybackSpeed.MAX) }) {
                    Text("2×", style = MaterialTheme.typography.labelSmall)
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
    vocalStrength: Float,
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
                    exporter.exportMix(song, muteMask, semitones, aiOn, uri, vocalStrength)
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
