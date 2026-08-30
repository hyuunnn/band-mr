package com.bandmr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import com.bandmr.app.Locator
import com.bandmr.app.audio.MixCache
import com.bandmr.app.io.CacheStorage
import com.bandmr.app.separation.ModelState
import com.bandmr.app.separation.SepBus
import com.bandmr.app.separation.SepState
import com.bandmr.app.separation.SeparationService
import com.bandmr.app.separation.StemFiles
import com.bandmr.app.separation.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val currentTier by Locator.settings.modelTier.collectAsState(initial = Tier.BALANCED.id)
    val modelStates by Locator.modelManager.states.collectAsState()
    var busyTier by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 분리 모델", style = MaterialTheme.typography.titleLarge)
        Text(
            "AI를 켠 곡을 분리할 때 사용할 모델입니다. " +
                "한 번만 다운로드하면 오프라인에서도 사용할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        Tier.entries.forEach { tier ->
            val state = modelStates[tier]
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentTier == tier.id,
                        onClick = {
                            if (Locator.modelManager.isDownloaded(tier) || state is ModelState.Ready) {
                                scope.launch { Locator.settings.setModelTier(tier.id) }
                            }
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${tier.label} (약 ${tier.approxSizeMb}MB)", style = MaterialTheme.typography.titleSmall)
                        Text(tier.description, style = MaterialTheme.typography.bodySmall)
                        when (state) {
                            is ModelState.Downloading -> {
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                            is ModelState.Failed -> Text(
                                "다운로드 실패: ${state.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            ModelState.Ready -> Text(
                                "다운로드됨",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            else -> {}
                        }
                    }
                    when {
                        state is ModelState.Downloading -> {}
                        state is ModelState.Ready -> OutlinedButton(onClick = {
                            Locator.modelManager.delete(tier)
                        }) { Text("삭제") }
                        else -> Button(
                            enabled = busyTier == null,
                            onClick = {
                                busyTier = tier.id
                                // 화면을 벗어나도 다운로드가 중단되지 않도록 앱 스코프에서 실행
                                Locator.appScope.launch {
                                    runCatching { Locator.modelManager.download(tier) }
                                    busyTier = null
                                }
                            },
                        ) { Text(if (state is ModelState.Failed) "재시도" else "받기") }
                    }
                }
            }
        }

        Text(
            "참고\n" +
                "· AI OFF: 재생 중 실시간 신호처리(중앙 마스킹/필터)로 제거 — 즉시 동작, 절전\n" +
                "· AI ON: 곡당 1회 사전 분리 후 캐시 사용 — 정확하지만 처리에 시간이 걸림\n" +
                "· 품질 우선 모델은 RAM 4GB 이상 기기를 권장합니다.",
            style = MaterialTheme.typography.bodySmall,
        )

        StorageSection()
    }
}

/**
 * 저장공간 사용량과 비우기.
 *
 * 파이프라인이 44.1kHz 스테레오 PCM16 고정이라 4분 곡 하나가 원본 캐시 약 40MB,
 * 스템 6개 약 242MB를 쓴다. 곡을 지우지 않으면 아무도 정리하지 않으므로
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

    Text("저장공간", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
    Text(
        "원본 캐시와 분리된 스템은 무압축 WAV(44.1kHz 스테레오)로 저장됩니다. " +
            "4분 곡 기준 원본 약 40MB, 스템 6개 약 242MB입니다.",
        style = MaterialTheme.typography.bodySmall,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageRow("원본 캐시", usage?.mixCache)
            StorageRow("분리된 스템", usage?.stems)
            StorageRow("합계", usage?.total, emphasize = true)

            lastFreed?.let {
                Text(
                    "${it}를 비웠습니다.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy && (usage?.mixCache ?: 0L) > 0L,
                    onClick = { runCleanup { clearMixCache() } },
                ) { Text("원본 캐시 비우기") }

                OutlinedButton(
                    enabled = !busy && (usage?.stems ?: 0L) > 0L,
                    onClick = { confirmStems = true },
                ) { Text("분리 결과 삭제") }
            }

            Text(
                "원본 캐시는 다시 재생할 때 자동으로 만들어집니다(앱을 다시 켤 때 미리 만들어 두기도 합니다). " +
                    "분리 결과는 삭제하면 곡마다 AI 분리를 처음부터 다시 해야 합니다.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
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
private fun StorageRow(label: String, bytes: Long?, emphasize: Boolean = false) {
    val style = if (emphasize) MaterialTheme.typography.titleSmall
    else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = style)
        Text(bytes?.let { CacheStorage.formatBytes(it) } ?: "계산 중…", style = style)
    }
}
