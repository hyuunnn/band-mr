package com.bandmr.app.export

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

internal class ExportDestinationCleanupException(cause: Throwable) :
    IOException("실패한 저장 파일을 삭제하지 못했습니다", cause)

/**
 * 새로 만든 출력 문서 하나를 빈 파일부터 복사 완료까지 소유한다. 믹스는 전체 저장에,
 * 스템은 파일마다 적용한다. 취소 후에도 삭제를 끝내고 삭제 실패는 원래 오류에 덧붙인다.
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
