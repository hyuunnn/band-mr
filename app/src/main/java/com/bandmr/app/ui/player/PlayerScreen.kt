package com.bandmr.app.ui.player

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bandmr.app.Locator
import com.bandmr.app.R
import com.bandmr.app.audio.MixCache
import com.bandmr.app.audio.PlaybackLoop
import com.bandmr.app.audio.PlaybackSkip
import com.bandmr.app.audio.PlaybackSpeed
import com.bandmr.app.audio.PlayerController
import com.bandmr.app.audio.WaveformPeaks
import com.bandmr.app.data.AppDesign
import com.bandmr.app.data.Stem
import com.bandmr.app.export.Exporter
import com.bandmr.app.playback.PlaybackService
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import com.bandmr.app.separation.StemLayout
import com.bandmr.app.separation.Tier
import com.bandmr.app.ui.components.DesignBackdrop
import com.bandmr.app.ui.components.PlayerHeading
import com.bandmr.app.ui.components.SectionHeading
import com.bandmr.app.ui.components.StatusBadge
import com.bandmr.app.ui.components.StudioPanel
import com.bandmr.app.ui.theme.LocalAppDesign
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlayerScreen(songId: Long) {
    val song by Locator.songDao.observe(songId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val ctrl = remember { Locator.playerController }

    val aiOn by Locator.settings.aiEnabled.collectAsState(initial = false)
    val selectedTierId by Locator.settings.modelTier.collectAsState(initial = Tier.S6_BALANCED.id)
    val sepState by SepBus.state.collectAsState()

    var stemGainsPacked by remember { mutableLongStateOf(Stem.DEFAULT_PACKED) }
    var semitones by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(PlaybackSpeed.DEFAULT) }
    var vocalStrength by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosMs by remember { mutableFloatStateOf(0f) }
    var lastScrubSeekAt by remember { mutableLongStateOf(0L) }
    var posMs by remember { mutableLongStateOf(0L) }
    var loopStartMs by remember { mutableStateOf<Long?>(null) }
    var loopEndMs by remember { mutableStateOf<Long?>(null) }
    var waveformPeaks by remember(songId) { mutableStateOf<FloatArray?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable(songId) { mutableIntStateOf(0) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 권한 여부와 무관하게 진행 (거부 시 알림만 숨김)
        SeparationService.start(Locator.context, songId)
    }
    val playbackNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 허용 직후 알림을 다시 게시해 shade에 보이게 한다 (서비스는 이미 기동 상태일 수 있다)
        PlaybackService.start(Locator.context)
    }

    val preparingSongId by ctrl.preparingSongId.collectAsState()
    val prepareFailedSongId by ctrl.prepareFailedSongId.collectAsState()
    // 알림 '종료'로 엔진이 해제되면 값이 바뀌어 아래 로드 이펙트가 다시 돈다
    val releaseEpoch by ctrl.releaseEpoch.collectAsState()

    LaunchedEffect(song?.id, song?.separatedTier, aiOn, releaseEpoch) {
        val s = song ?: return@LaunchedEffect
        stemGainsPacked = s.stemGainsPacked
        semitones = s.semitones
        speed = PlaybackSpeed.snap(s.speed)
        loopStartMs = s.loopStartMs
        loopEndMs = s.loopEndMs
        ctrl.setLoop(s.loopStartMs, s.loopEndMs, apply = false)
        ctrl.ensureLoaded(s, aiOn, s.stemGainsPacked, s.semitones, speed)
    }

    // 저장된 보컬 제거 강도 로드 후 컨트롤러에 반영
    LaunchedEffect(Unit) {
        vocalStrength = Locator.settings.vocalStrength.first()
        ctrl.setVocalStrength(vocalStrength)
    }

    LaunchedEffect(songId, prepareFailedSongId) {
        val file = MixCache.cacheFile(Locator.context, songId)
        if (!file.exists()) {
            if (prepareFailedSongId == songId) return@LaunchedEffect
            // 프리캐시·재생 준비 모두 MixCache.prepare → rename 뒤에만 깨어난다
            MixCache.awaitReady(Locator.context, songId)
        }
        if (!file.exists()) return@LaunchedEffect
        waveformPeaks = withContext(Dispatchers.IO) {
            runCatching {
                WaveformPeaks.fromWavCached(file, MixCache.peaksFile(Locator.context, songId))
            }.getOrNull()
        }
    }

    val playing by ctrl.isPlaying.collectAsState()

    // 알림 권한이 없으면 재생 시작 때 요청한다. FGS 기동 자체는 PlayerController가
    // 재생 의도 시점에 한다 — 여기서 띄우면 준비 완료 자동 재생이 화면 이탈 뒤에 일어날 때
    // 백그라운드 startForegroundService로 죽거나, 화면이 dispose돼 아예 안 뜬다
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        val granted = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            Locator.context, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
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
    val selectedTier = Tier.fromId(selectedTierId)
    val separatedTier = if (separated) Tier.fromId(s.separatedTier) else null
    val mixerStems = separatedTier?.displayStems ?: Stem.entries
    val fourStemMixer = separatedTier?.layout == StemLayout.FOUR
    val runningSep = sepState as? SepState.Running
    val running = runningSep?.songId == songId
    val otherRunning = runningSep != null && runningSep.songId != songId
    val sepProgress = runningSep
    val loadedDurationMs by ctrl.durationMs.collectAsState()
    var confirmReseparation by remember(songId, selectedTier, separatedTier, aiOn, running, otherRunning) {
        mutableStateOf(false)
    }

    fun startSeparation() {
        if (running || otherRunning) return
        if (separated) ctrl.release()
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SeparationService.start(Locator.context, songId)
        }
    }

    fun persistStemLevels(packed: Long = stemGainsPacked) {
        scope.launch {
            Locator.songDao.updateStemLevels(songId, packed, Stem.muteMaskFromPacked(packed))
        }
    }

    fun applyStemLevels(packed: Long, persist: Boolean = true) {
        stemGainsPacked = packed
        ctrl.setStemLevels(packed)
        if (persist) persistStemLevels(packed)
    }

    Box(Modifier.fillMaxSize()) {
        DesignBackdrop(seed = s.id)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
        PlayerHeading(
            songId = s.id,
            title = s.title,
            subtitle = formatTime(s.durationMs) + if (aiOn && separated) " · AI ${separatedTier?.chipLabel}" else " · 원본 오디오",
        )

        if (preparingSongId == songId && !separated) {
            Text(
                "재생을 준비하고 있어요. 잠시만 기다려 주세요.",
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
            onDrag = { v ->
                dragPosMs = v
                val now = SystemClock.uptimeMillis()
                if (now - lastScrubSeekAt >= SCRUB_SEEK_INTERVAL_MS) {
                    lastScrubSeekAt = now
                    ctrl.seekTo(v.toLong())
                }
            },
            onDragEnd = {
                ctrl.seekTo(dragPosMs.toLong())
                posMs = ctrl.positionMs()
                lastScrubSeekAt = 0L
                dragging = false
            },
            onSkip = { delta ->
                dragging = false
                ctrl.skipBy(delta)
                posMs = ctrl.positionMs()
            },
            loopStartMs = loopStartMs,
            loopEndMs = loopEndMs,
            waveformPeaks = waveformPeaks,
            onSetLoopPoint = { isStart ->
                val mark = if (dragging) dragPosMs.toLong() else posMs
                val duration = if (loadedDurationMs > 0) loadedDurationMs else s.durationMs
                val (start, end) = PlaybackLoop.applyPoint(
                    loopStartMs,
                    loopEndMs,
                    PlaybackSkip.clamp(mark, duration),
                    isStart,
                )
                loopStartMs = start
                loopEndMs = end
                dragging = false
                ctrl.setLoop(start, end)
                posMs = ctrl.positionMs()
                scope.launch {
                    Locator.songDao.updateLoop(songId, start, end)
                }
            },
            onClearLoop = {
                loopStartMs = null
                loopEndMs = null
                ctrl.setLoop(null, null)
                scope.launch {
                    Locator.songDao.updateLoop(songId, null, null)
                }
            },
        )

        PlayerTabs(selected = selectedTab, exporting = exporting, onSelect = { selectedTab = it })

        if (selectedTab == 0) {
            ModeCard(
                aiOn = aiOn,
                separated = separated,
                separatedLabel = separatedTier?.label,
                selectedLabel = selectedTier.label,
                canReseparate = separated && selectedTier.id != s.separatedTier,
                running = running,
                otherRunning = otherRunning,
                stage = sepProgress?.stage,
                progress = sepProgress?.progress,
                error = (sepState as? SepState.Error)?.takeIf { it.songId == songId }?.message,
                onToggleAi = { enabled -> scope.launch { Locator.settings.setAiEnabled(enabled) } },
                onStartSeparation = {
                    if (separated) confirmReseparation = true else startSeparation()
                },
                onCancelSeparation = { SeparationService.cancel(Locator.context) },
            )

            StemCard(
                separated = separated && aiOn,
                stems = mixerStems,
                fourStem = fourStemMixer,
                stemGainsPacked = stemGainsPacked,
                vocalStrength = vocalStrength,
                onVocalStrengthChange = { v ->
                    vocalStrength = v
                    ctrl.setVocalStrength(v) // 재생 중 즉시 반영
                },
                onVocalStrengthDone = {
                    scope.launch { Locator.settings.setVocalStrength(vocalStrength) }
                },
                onToggle = { stem, checked ->
                    applyStemLevels(
                        Stem.withPercent(stemGainsPacked, stem, if (checked) 0 else Stem.GAIN_FULL),
                    )
                },
                onLevel = { stem, percent ->
                    if (percent != Stem.percentOf(stemGainsPacked, stem)) {
                        applyStemLevels(Stem.withPercent(stemGainsPacked, stem, percent), persist = false)
                    }
                },
                onLevelDone = { persistStemLevels() },
                onResetLevels = { applyStemLevels(Stem.DEFAULT_PACKED) },
            )
        }
        if (selectedTab == 1) {
            PitchCard(
                semitones = semitones,
                onChange = { v ->
                    if (v != semitones) {
                        semitones = v
                        ctrl.setSemitones(v)
                        scope.launch {
                            Locator.songDao.updateSemitones(songId, v)
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
                            Locator.songDao.updateSpeed(songId, snapped)
                        }
                    }
                },
            )
        }

        // Keep the launchers and export scope composed while switching tabs.
        ExportCard(
            visible = selectedTab == 2,
            song = s,
            aiOn = aiOn,
            separated = separated,
            stemGainsPacked = stemGainsPacked,
            semitones = semitones,
            vocalStrength = vocalStrength,
            exporting = exporting,
            exportMsg = exportMsg,
            setExporting = { exporting = it },
            setExportMsg = { exportMsg = it },
        )
        }
    }

    if (confirmReseparation && separatedTier != null) {
        ConfirmReseparationDialog(
            currentLabel = separatedTier.label,
            selectedLabel = selectedTier.label,
            onDismiss = { confirmReseparation = false },
            onConfirm = {
                confirmReseparation = false
                startSeparation()
            },
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
    loopStartMs: Long?,
    loopEndMs: Long?,
    waveformPeaks: FloatArray?,
    onSetLoopPoint: (isStart: Boolean) -> Unit,
    onClearLoop: () -> Unit,
) {
    val isPlaying by ctrl.isPlaying.collectAsState()
    val design = LocalAppDesign.current
    StudioPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("플레이어", style = MaterialTheme.typography.labelLarge)
            StatusBadge(if (isPlaying) "재생 중" else "일시정지", active = isPlaying)
        }
        val peaks = waveformPeaks
        if (peaks != null && peaks.isNotEmpty()) {
            WaveformBar(
                peaks = peaks,
                durationMs = durationMs,
                posMs = posMs,
                dragging = dragging,
                dragPosMs = dragPosMs,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
                onDraggingChange = onDraggingChange,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onSeek = { ctrl.seekTo(it) },
            )
        } else {
            Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.semantics { contentDescription = "재생 위치" },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime(if (dragging) dragPosMs.toLong() else posMs),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 264.dp
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compact) {
                    Column {
                        SkipControl(R.drawable.ic_replay_10, "10초 뒤로") { onSkip(-PlaybackSkip.LARGE_MS) }
                        SkipControl(R.drawable.ic_replay_5, "5초 뒤로") { onSkip(-PlaybackSkip.SMALL_MS) }
                    }
                } else {
                    SkipControl(R.drawable.ic_replay_10, "10초 뒤로") { onSkip(-PlaybackSkip.LARGE_MS) }
                    SkipControl(R.drawable.ic_replay_5, "5초 뒤로") { onSkip(-PlaybackSkip.SMALL_MS) }
                }
                FilledIconButton(
                    onClick = { ctrl.playPause() },
                    modifier = Modifier.size(72.dp),
                    shape = when (design) {
                        AppDesign.AMP -> MaterialTheme.shapes.small
                        AppDesign.AURORA -> MaterialTheme.shapes.large
                        else -> CircleShape
                    },
                ) {
                    Icon(
                        painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (isPlaying) "일시정지" else "재생",
                        modifier = Modifier.size(32.dp),
                    )
                }
                if (compact) {
                    Column {
                        SkipControl(R.drawable.ic_forward_10, "10초 앞으로") { onSkip(PlaybackSkip.LARGE_MS) }
                        SkipControl(R.drawable.ic_forward_5, "5초 앞으로") { onSkip(PlaybackSkip.SMALL_MS) }
                    }
                } else {
                    SkipControl(R.drawable.ic_forward_5, "5초 앞으로") { onSkip(PlaybackSkip.SMALL_MS) }
                    SkipControl(R.drawable.ic_forward_10, "10초 앞으로") { onSkip(PlaybackSkip.LARGE_MS) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val start = loopStartMs
        val end = loopEndMs
        val armed = PlaybackLoop.isArmed(start, end)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("구간 반복", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onClearLoop, enabled = start != null || end != null) { Text("해제") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LoopPointButton("A", start, "시작 지점", Modifier.weight(1f)) { onSetLoopPoint(true) }
            LoopPointButton("B", end, "끝 지점", Modifier.weight(1f)) { onSetLoopPoint(false) }
        }
        Text(
            when {
                armed && start != null && end != null -> "${formatTime(start)} – ${formatTime(end)} 구간을 반복해요"
                start != null && end != null -> "구간은 ${PlaybackLoop.MIN_GAP_MS / 1000.0}초 이상이어야 해요"
                start != null -> "끝 지점을 지정하면 반복이 시작돼요"
                end != null -> "시작 지점을 지정하면 반복이 시작돼요"
                else -> "반복할 위치에서 A와 B를 눌러 주세요"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (start != null && end != null && !armed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkipControl(@androidx.annotation.DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

@Composable
private fun LoopPointButton(label: String, timeMs: Long?, hint: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        color = if (timeMs != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (timeMs != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(timeMs?.let { formatTime(it) } ?: hint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PlayerTabs(selected: Int, exporting: Boolean, onSelect: (Int) -> Unit) {
    val design = LocalAppDesign.current
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (design == AppDesign.MONO) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(if (design == AppDesign.MONO) 0.dp else 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("믹서", "연습 도구", if (exporting) "저장 중…" else "저장").forEachIndexed { index, label ->
            val active = selected == index
            Column(
                Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                    .background(if (active && design != AppDesign.MONO) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        active && design != AppDesign.MONO -> MaterialTheme.colorScheme.onPrimary
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (design == AppDesign.MONO) {
                    Box(Modifier.fillMaxWidth().height(3.dp), contentAlignment = Alignment.BottomCenter) {
                        HorizontalDivider(
                            thickness = if (active) 3.dp else 1.dp,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 시크 리셋(DSP/시프터)이 너무 잦지 않게 드래그 중 시크 간격 */
private const val SCRUB_SEEK_INTERVAL_MS = 100L

internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun ModeCard(
    aiOn: Boolean,
    separated: Boolean,
    separatedLabel: String?,
    selectedLabel: String,
    canReseparate: Boolean,
    running: Boolean,
    otherRunning: Boolean,
    stage: String?,
    progress: Float?,
    error: String?,
    onToggleAi: (Boolean) -> Unit,
    onStartSeparation: () -> Unit,
    onCancelSeparation: () -> Unit,
) {
    StudioPanel(highlighted = aiOn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("AI 악기 분리", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (aiOn) "악기별 소리를 더 정교하게 조절해요" else "켜면 악기별 볼륨을 조절할 수 있어요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = aiOn, onCheckedChange = onToggleAi, enabled = !running, modifier = Modifier.semantics { contentDescription = "AI 악기 분리" })
        }
        if (aiOn && running) {
            LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stage ?: "분리를 준비하고 있어요…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancelSeparation) { Text("취소") }
            }
        } else if (aiOn && !separated) {
            Text("$selectedLabel · 처음 한 번만 분리하면 돼요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onStartSeparation, enabled = !otherRunning, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                Text(if (otherRunning) "다른 곡 분리 중…" else "이 곡 분리하기")
            }
        } else if (aiOn && separated) {
            StatusBadge("분리 완료 · ${separatedLabel.orEmpty()}", active = true)
            if (canReseparate) {
                OutlinedButton(
                    onClick = onStartSeparation,
                    enabled = !otherRunning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(if (otherRunning) "다른 곡 분리 중…" else "$selectedLabel 모델로 다시 분리")
                }
                Text(
                    "분리가 완료되면 기존 분리 결과가 교체됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (aiOn) error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConfirmReseparationDialog(
    currentLabel: String,
    selectedLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("다시 분리할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("현재 모델: $currentLabel\n새 모델: $selectedLabel")
                Text(
                    "분리가 완료되면 기존 분리 결과가 새 결과로 교체되며, 이전 결과로 되돌릴 수 없습니다.",
                )
                Text("원본 음원은 유지됩니다.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("다시 분리") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun StemCard(
    separated: Boolean,
    stems: List<Stem>,
    fourStem: Boolean,
    stemGainsPacked: Long,
    vocalStrength: Float,
    onVocalStrengthChange: (Float) -> Unit,
    onVocalStrengthDone: () -> Unit,
    onToggle: (Stem, Boolean) -> Unit,
    onLevel: (Stem, Int) -> Unit,
    onLevelDone: () -> Unit,
    onResetLevels: () -> Unit,
) {
    StudioPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(
                if (separated) "나만의 믹스" else "제거할 소리",
                if (separated) "0%는 음소거, 100%는 원래 볼륨이에요" else "선택한 소리를 실시간으로 줄여요",
                Modifier.weight(1f),
            )
            if (separated) TextButton(onClick = onResetLevels) { Text("초기화") }
        }
        if (separated) {
            stems.forEachIndexed { index, stem ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                val percent = Stem.percentOf(stemGainsPacked, stem)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StemIcon(stem, active = percent > 0)
                        Text(Stem.labelFor(stem, fourStem), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusBadge(
                            if (percent == 0) "음소거" else "$percent%",
                            active = percent > 0,
                        )
                    }
                    Slider(
                        value = percent.toFloat(),
                        onValueChange = { onLevel(stem, it.toInt()) },
                        onValueChangeFinished = onLevelDone,
                        valueRange = 0f..Stem.GAIN_FULL.toFloat(),
                        modifier = Modifier.semantics { contentDescription = "${stem.label} 볼륨" },
                    )
                }
            }
        } else {
            val muteMask = Stem.muteMaskFromPacked(stemGainsPacked)
            Stem.entries.forEachIndexed { index, stem ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                val enabled = !stem.aiOnly
                val checked = muteMask and stem.bit != 0
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle(stem, it) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StemIcon(stem, active = checked && enabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stem.label, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stem.dspHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                }
                if (stem == Stem.VOCAL && checked) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("보컬 제거 강도", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                Text("${(vocalStrength * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(
                                value = vocalStrength,
                                onValueChange = onVocalStrengthChange,
                                onValueChangeFinished = onVocalStrengthDone,
                                valueRange = 0f..1f,
                                modifier = Modifier.semantics { contentDescription = "보컬 제거 강도" },
                            )
                            Text("낮게 설정할수록 반주 손상이 적어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("빠른 제거는 주변 악기 소리에도 영향을 줄 수 있어요. 정교한 조절이 필요하면 AI 분리를 사용해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StemIcon(stem: Stem, active: Boolean) {
    val icon = when (stem) {
        Stem.VOCAL -> R.drawable.ic_stem_vocal
        Stem.DRUMS -> R.drawable.ic_stem_drums
        Stem.BASS -> R.drawable.ic_stem_bass
        Stem.GUITAR -> R.drawable.ic_stem_guitar
        Stem.PIANO -> R.drawable.ic_stem_piano
        Stem.OTHER -> R.drawable.ic_stem_other
    }
    Surface(
        modifier = Modifier.size(38.dp),
        shape = MaterialTheme.shapes.small,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun PitchCard(semitones: Int, onChange: (Int) -> Unit) {
    StudioPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading("키 조절", "나에게 편한 음역으로 연습해요", Modifier.weight(1f))
            TextButton(onClick = { onChange(0) }, enabled = semitones != 0) { Text("초기화") }
        }
        Text(
            if (semitones == 0) "원곡 키" else "${if (semitones > 0) "+" else ""}$semitones 반음",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdjustButton("−", "키 한 반음 낮추기", semitones > -12) { onChange((semitones - 1).coerceIn(-12, 12)) }
            Slider(
                value = semitones.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = -12f..12f,
                steps = 23,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "키 조절" },
                colors = SliderDefaults.colors(inactiveTickColor = MaterialTheme.colorScheme.outlineVariant),
            )
            AdjustButton("+", "키 한 반음 높이기", semitones < 12) { onChange((semitones + 1).coerceIn(-12, 12)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−12 반음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("+12 반음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeedCard(speed: Float, onChange: (Float) -> Unit) {
    StudioPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading("재생 속도", "어려운 구간은 천천히 익혀 보세요", Modifier.weight(1f))
            TextButton(onClick = { onChange(PlaybackSpeed.DEFAULT) }, enabled = speed != PlaybackSpeed.DEFAULT) { Text("초기화") }
        }
        Text(PlaybackSpeed.formatLabel(speed), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdjustButton("−", "재생 속도 낮추기", speed > PlaybackSpeed.MIN) { onChange(PlaybackSpeed.step(speed, -1)) }
            Slider(
                value = speed,
                onValueChange = onChange,
                valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                steps = PlaybackSpeed.sliderSteps,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "재생 속도" },
                colors = SliderDefaults.colors(inactiveTickColor = MaterialTheme.colorScheme.outlineVariant),
            )
            AdjustButton("+", "재생 속도 높이기", speed < PlaybackSpeed.MAX) { onChange(PlaybackSpeed.step(speed, 1)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onChange(PlaybackSpeed.MIN) }) { Text("0.25×") }
            TextButton(onClick = { onChange(PlaybackSpeed.DEFAULT) }) { Text("1× 원속도") }
            TextButton(onClick = { onChange(PlaybackSpeed.MAX) }) { Text("2×") }
        }
        Text("음정은 유지돼요. 저장할 때는 원래 속도로 내보내요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AdjustButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ExportCard(
    visible: Boolean,
    song: com.bandmr.app.data.Song,
    aiOn: Boolean,
    separated: Boolean,
    stemGainsPacked: Long,
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
                    exporter.exportMix(song, stemGainsPacked, semitones, aiOn, uri, vocalStrength)
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

    if (!visible) return

    StudioPanel {
        SectionHeading("연습한 사운드 그대로", "악기 볼륨과 키 조절을 반영한 WAV 파일로 저장해요")
        Button(
            onClick = { mixLauncher.launch("${Exporter.safeName(song.title)}_edited.wav") },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(20.dp))
            Text("현재 믹스 저장", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = { stemsLauncher.launch(null) },
            enabled = !exporting && separated,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.small,
        ) { Text("악기별 파일 저장") }
        if (!separated) {
            Text("악기별 파일은 AI 분리 후 저장할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("전체 곡을 원래 속도로 저장해요. 구간 반복과 연습용 재생 속도는 저장 파일에 적용되지 않아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
        exportMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
