package com.bandmr.app.export

import com.bandmr.app.data.Stem
import com.bandmr.app.separation.withStemMutation
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * 스템마다 새 목적지 하나를 소유한다. 열기·복사·close 실패나 취소 시 현재 문서만
 * 지우고 앞서 끝난 스템은 보존한다. 성공 개수와 콜백은 스트림이 닫힌 뒤에만 갱신한다.
 * 승격과 같은 잠금 안에서 입력을 모두 열어 같은 분리 결과를 고정한다. 복사는 잠금 밖에서
 * 수행하며, 열린 입력은 파일 교체·삭제 후에도 유효하다. 사용하지 못한 입력도 모두 닫는다.
 */
internal suspend fun <T> copyStemFiles(
    dir: File,
    createDestination: (Stem) -> T?,
    openDestination: (T) -> OutputStream?,
    discardDestination: suspend (T) -> Unit,
    openSource: (File) -> InputStream = { it.inputStream() },
    onEach: (String) -> Unit = {},
): Int {
    val inputs = linkedMapOf<Stem, InputStream>()
    var failure: Throwable? = null
    try {
        withStemMutation {
            for (stem in Stem.entries) {
                val source = File(dir, "${stem.fileName}.wav")
                if (source.exists()) inputs[stem] = openSource(source)
            }
        }
        if (inputs.isEmpty()) throw IOException("분리된 스템이 없습니다")
        var count = 0
        for ((stem, input) in inputs) {
            coroutineContext.ensureActive()
            val target = createDestination(stem) ?: throw IOException("저장 파일을 만들 수 없습니다")
            withExportDestination(discard = { discardDestination(target) }) {
                coroutineContext.ensureActive()
                val output = openDestination(target) ?: throw IOException("저장 위치를 열 수 없습니다")
                output.use { out -> input.use { it.copyTo(out) } }
                coroutineContext.ensureActive()
            }
            onEach(stem.label)
            count++
        }
        return count
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        // 정상 복사한 입력은 use에서 이미 닫혔다. 중단 시 남은 입력도 닫고 원래 오류를 보존한다.
        for (input in inputs.values) {
            try {
                input.close()
            } catch (closeFailure: Exception) {
                if (closeFailure !== failure) failure?.addSuppressed(closeFailure)
            }
        }
    }
}
