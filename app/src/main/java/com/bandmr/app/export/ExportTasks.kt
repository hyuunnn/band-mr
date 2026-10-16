package com.bandmr.app.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExportStatus(val running: Boolean = false, val message: String? = null)

/**
 * 내보내기와 곡별 결과를 앱 수명에 둔다. 화면 재진입·구성 변경에도 진행 상태를 다시 읽고,
 * 같은 곡의 저장 버튼을 중복 실행하지 않는다. 전달받은 설정은 시작 시점의 값으로 고정된다.
 */
class ExportTasks(private val scope: CoroutineScope) {
    private val mutableStates = MutableStateFlow<Map<Long, ExportStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()

    @Synchronized
    fun start(songId: Long, export: suspend () -> String): Boolean {
        if (mutableStates.value[songId]?.running == true) return false
        mutableStates.update { it + (songId to ExportStatus(running = true)) }
        scope.launch {
            try {
                val message = export()
                mutableStates.update { it + (songId to ExportStatus(message = message)) }
            } catch (cancelled: CancellationException) {
                mutableStates.update {
                    it + (songId to ExportStatus(message = "저장 중단${cleanupMessage(cancelled)}"))
                }
                throw cancelled
            } catch (failure: Exception) {
                mutableStates.update {
                    it + (songId to ExportStatus(message = "실패: ${failure.message}${cleanupMessage(failure)}"))
                }
            }
        }
        return true
    }

    private fun cleanupMessage(failure: Throwable): String =
        if (failure.suppressed.any { it is ExportDestinationCleanupException }) {
            " · 실패한 파일을 삭제하지 못했습니다. 저장 위치를 확인해 주세요."
        } else ""
}
