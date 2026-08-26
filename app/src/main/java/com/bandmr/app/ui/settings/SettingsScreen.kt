package com.bandmr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import com.bandmr.app.separation.ModelState
import com.bandmr.app.separation.Tier
import kotlinx.coroutines.launch

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
                "· AI OFF: 재생 중 실시간 신호처리(위상 상쇄/필터)로 제거 — 즉시 동작, 절전\n" +
                "· AI ON: 곡당 1회 사전 분리 후 캐시 사용 — 정확하지만 처리에 시간이 걸림\n" +
                "· 품질 우선 모델은 RAM 4GB 이상 기기를 권장합니다.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
