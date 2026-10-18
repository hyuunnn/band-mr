package com.bandmr.app.separation

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

private val stemMutationMutex = Mutex()

/** 스템·곡 캐시 승격과 곡·전체 스템 삭제가 서로의 파일·DB 변경 사이에 끼어들지 않게 한다. */
internal suspend fun <T> withStemMutation(block: suspend () -> T): T = stemMutationMutex.withLock {
    coroutineContext.ensureActive()
    withContext(NonCancellable) { block() }
}

/**
 * 확정 전 취소는 이전 결과를 보존한다. 확정에 들어간 뒤에는 파일 승격과 DB 갱신을
 * 함께 끝낸다. 둘 사이의 취소나 IO 디스패처 복귀 시 취소로 파일·DB가 갈라지지 않게 한다.
 * 추론은 이 구간 밖에서 수행하며, 파일/DB 자체의 I/O 실패까지 원자적으로 만드는 것은 아니다.
 *
 * 곡 행이 없으면 승격하지 않는다. 삭제가 이 잠금을 먼저 끝내면 UPDATE는 맞는 행이 없고,
 * 파일만 정식 위치에 남아 다음 실행 전까지 용량을 차지한다.
 *
 * @return 파일과 DB를 갱신했으면 true
 */
internal suspend fun commitSeparation(
    songExists: suspend () -> Boolean,
    promote: () -> File,
    record: suspend (File) -> Unit,
): Boolean = withStemMutation {
    if (!songExists()) return@withStemMutation false
    record(promote())
    true
}
