package com.bandmr.app.export

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

internal class ExportDestinationCleanupException(cause: Throwable) :
    IOException("실패한 저장 파일을 삭제하지 못했습니다", cause)

/**
 * CreateDocument가 미리 만든 빈 파일부터 최종 복사 중 남은 부분 파일까지 같은 수명으로
 * 소유한다. 취소 후에도 삭제를 끝내고, 삭제 실패는 원래 오류에 덧붙여 호출자에게 전달한다.
 */
internal suspend fun <T> withExportDestination(
    discard: suspend () -> Unit,
    export: suspend () -> T,
): T = try {
    export()
} catch (failure: Throwable) {
    withContext(NonCancellable) {
        try {
            discard()
        } catch (cleanup: Throwable) {
            failure.addSuppressed(ExportDestinationCleanupException(cleanup))
        }
    }
    throw failure
}
