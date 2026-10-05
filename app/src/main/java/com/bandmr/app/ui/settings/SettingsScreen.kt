package com.bandmr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bandmr.app.Locator
import com.bandmr.app.R
import com.bandmr.app.audio.MixCache
import com.bandmr.app.io.CacheStorage
import com.bandmr.app.separation.ModelFamily
import com.bandmr.app.separation.ModelState
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import com.bandmr.app.separation.StemFiles
import com.bandmr.app.separation.Tier
import com.bandmr.app.ui.components.DesignBackdrop
import com.bandmr.app.ui.components.SectionHeading
import com.bandmr.app.ui.components.StatusBadge
import com.bandmr.app.ui.components.StudioPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val currentTier by Locator.settings.modelTier.collectAsState(initial = Tier.S6_BALANCED.id)
    val modelStates by Locator.modelManager.states.collectAsState()
    var busyTier by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Tier?>(null) }

    Box(Modifier.fillMaxSize()) {
        DesignBackdrop()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        Text("나에게 맞는 사운드", style = MaterialTheme.typography.headlineMedium)
        Text(
            "분리 모델을 선택하고 저장공간을 관리하세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StudioPanel {
            StatusBadge("기기에서 처리하는 AI", active = true)
            SectionHeading("한 번 받으면, 오프라인에서도", "원하는 모델을 다운로드한 뒤 선택해 주세요. 다음에 분리할 곡부터 적용돼요.")
        }

        ModelFamily.entries.forEach { family ->
            SectionHeading(family.label, family.description, Modifier.padding(top = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.selectableGroup()) {
                Tier.entries.filter { it.family == family }.forEach { tier ->
                    ModelTierCard(
                        tier = tier,
                        state = modelStates[tier],
                        selected = currentTier == tier.id,
                        busy = busyTier != null,
                        onSelect = {
                            if (Locator.modelManager.isDownloaded(tier) || modelStates[tier] is ModelState.Ready) {
                                scope.launch { Locator.settings.setModelTier(tier.id) }
                            }
                        },
                        onDownload = {
                            busyTier = tier.id
                            // 화면을 벗어나도 다운로드가 중단되지 않도록 앱 스코프에서 실행
                            Locator.appScope.launch {
                                runCatching { Locator.modelManager.download(tier) }
                                    .onSuccess { Locator.settings.setModelTier(tier.id) }
                                busyTier = null
                            }
                        },
                        onDelete = { pendingDelete = tier },
                    )
                }
            }
        }

        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("어떤 모드를 사용할까요?", style = MaterialTheme.typography.titleSmall)
                Text("빠른 제거는 바로 재생하며 소리를 줄여요. AI 분리는 처음에 시간이 걸리지만 악기별 볼륨을 세밀하게 조절할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("품질 우선·SCNet 모델은 메모리 4GB 이상 기기를 권장해요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        StorageSection()
        }
    }

    pendingDelete?.let { tier ->
        ConfirmModelDeleteDialog(
            tier = tier,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                Locator.modelManager.delete(tier)
            },
        )
    }
}

@Composable
private fun ConfirmModelDeleteDialog(tier: Tier, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("모델을 삭제할까요?") },
        text = {
            Text(
                "'${tier.label}' 모델을 삭제합니다.\n\n" +
                    "이 모델로 다시 분리하려면 약 ${tier.approxSizeMb} MB를 다시 다운로드해야 합니다. " +
                    "원본 음원과 이미 분리한 결과는 유지됩니다.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("삭제") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun ModelTierCard(
    tier: Tier,
    state: ModelState?,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val ready = state is ModelState.Ready
    StudioPanel(highlighted = selected) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, enabled = ready, role = Role.RadioButton, onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = ready)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tier.cardTitle, style = MaterialTheme.typography.titleMedium)
                Text("약 ${tier.approxSizeMb} MB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) StatusBadge(if (ready) "사용 중" else "선택됨", active = true)
        }
        Text(
            tier.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            is ModelState.Downloading -> {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("다운로드 중 · ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            ModelState.Ready -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("다운로드 완료", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "${tier.label} 모델 삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
            else -> {
                if (state is ModelState.Failed) Text("다운로드 실패: ${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    enabled = !busy,
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (state is ModelState.Failed) "다시 다운로드" else "모델 다운로드", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * 저장공간 사용량과 비우기.
 *
 * 파이프라인이 44.1kHz 스테레오 PCM16 고정이라 4분 곡 하나가 원본 캐시 약 40MB,
 * 스템 4~6개 약 161~242MB를 쓴다. 곡을 지우지 않으면 아무도 정리하지 않으므로
 * (`cleanUpOrphans`는 DB에서 사라진 곡만 본다) 사용자가 직접 비울 수단이 필요하다.
 */
@Composable
private fun StorageSection() {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmStems by remember { mutableStateOf(false) }
    var lastFreed by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) { usage = withContext(Dispatchers.IO) { readUsage() } }

    /** 정리 실행 → 회수량 표시 → 사용량 재조회. 두 버튼이 같은 절차를 쓴다 */
    fun runCleanup(clear: suspend () -> Long) {
        busy = true
        scope.launch {
            lastFreed = CacheStorage.formatBytes(clear())
            refreshKey++
            busy = false
        }
    }

    SectionHeading("저장공간", "연습할 곡은 남겨 두고, 임시 파일만 정리하세요.", Modifier.padding(top = 20.dp))

    StudioPanel {
        Text("정리할 수 있는 공간", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(usage?.total?.let { CacheStorage.formatBytes(it) } ?: "계산 중…", style = MaterialTheme.typography.headlineLarge)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StorageRow("재생 캐시", usage?.mixCache)
        StorageRow("AI 분리 파일", usage?.stems)
        lastFreed?.let {
            StatusBadge("${it}를 비웠어요", active = true)
        }
        OutlinedButton(
            enabled = !busy && (usage?.mixCache ?: 0L) > 0L,
            onClick = { runCleanup { clearMixCache() } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small,
        ) { Text("재생 캐시 비우기") }
        TextButton(
            enabled = !busy && (usage?.stems ?: 0L) > 0L,
            onClick = { confirmStems = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("AI 분리 파일 삭제") }
        Text(
            "재생 캐시를 비우면 재생이 멈추며, 다음 재생 때 캐시를 다시 만드는 데 시간이 걸릴 수 있어요. " +
                "AI 분리 파일을 지우면 곡을 다시 분리해야 해요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmStems) {
        ConfirmStemDeleteDialog(
            onDismiss = { confirmStems = false },
            onConfirm = {
                confirmStems = false
                runCleanup { deleteAllStems() }
            },
        )
    }
}

/** 분리 결과 삭제 확인. 곡당 수 분이 드는 작업을 되돌리므로 되묻는다 */
@Composable
private fun ConfirmStemDeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("분리 결과 삭제") },
        text = {
            Text(
                "모든 곡의 AI 분리 스템을 삭제합니다. " +
                    "다시 쓰려면 곡마다 분리를 처음부터 해야 하며, 곡당 수 분이 걸립니다. 계속할까요?",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 원본 캐시 WAV·파형을 버린다. @return 회수한 바이트 */
private suspend fun clearMixCache(): Long {
    // 재생 중인 엔진이 이 WAV를 열고 있다. 지우고 계속 재생하면 화면에는 캐시가 없는데
    // 소리는 나는 상태가 되므로 종료 경로(release)를 지난다.
    Locator.playerController.release()
    return withContext(Dispatchers.IO) {
        CacheStorage.clearFiles(MixCache.dir(Locator.context))
    }
}

/** 모든 곡의 스템을 버리고 DB의 분리 표시도 내린다. @return 회수한 바이트 */
private suspend fun deleteAllStems(): Long {
    // 진행 중인 분리를 먼저 취소한다. 취소는 세그먼트 경계에서만 판정되므로 그 사이 완료된
    // 분리가 승격될 수 있다 → .part 디렉터리까지 함께 지워 "DB는 미분리인데 스템만 남은"
    // 고아를 만들지 않는다(승격이 실패로 끝난다)
    if (SepBus.state.value is SepState.Running) {
        SeparationService.cancel(Locator.context)
    }
    Locator.playerController.release()
    val freed = withContext(Dispatchers.IO) {
        CacheStorage.clearSubdirectories(StemFiles.dir(Locator.context), includeInFlight = true)
    }
    // 파일이 사라졌으므로 DB의 분리 표시도 함께 내린다(한 문장 UPDATE).
    // 안 내리면 AI ON이 스템 없는 곡을 열려다 실패한다
    Locator.songDao.clearAllSeparation()
    return freed
}

/**
 * 화면에 보이는 용량. **실제로 비울 수 있는 양**만 센다 — 쓰는 중인 `.part`/`.tmp`를 포함하면
 * "용량은 남았는데 버튼을 눌러도 0B"가 된다(정리가 그것들을 건너뛰므로).
 */
private data class StorageUsage(val mixCache: Long, val stems: Long) {
    val total: Long get() = mixCache + stems
}

private fun readUsage(): StorageUsage = StorageUsage(
    mixCache = CacheStorage.clearableFileSize(MixCache.dir(Locator.context)),
    stems = CacheStorage.clearableSubdirectorySize(
        StemFiles.dir(Locator.context),
        includeInFlight = true,
    ),
)

@Composable
private fun StorageRow(label: String, bytes: Long?) {
    val style = MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = style)
        Text(bytes?.let { CacheStorage.formatBytes(it) } ?: "계산 중…", style = style)
    }
}
