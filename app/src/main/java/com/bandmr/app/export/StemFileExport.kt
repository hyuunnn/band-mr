package com.bandmr.app.export

import com.bandmr.app.data.Stem
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * 스템마다 새 목적지 하나를 소유한다. 열기·복사·close 실패나 취소 시 현재 문서만
 * 지우고 앞서 끝난 스템은 보존한다. 성공 개수와 콜백은 스트림이 닫힌 뒤에만 갱신한다.
 */
internal suspend fun <T> copyStemFiles(
    dir: File,
    createDestination: (Stem) -> T?,
    openDestination: (T) -> OutputStream?,
    discardDestination: suspend (T) -> Unit,
    onEach: (String) -> Unit = {},
): Int {
    var count = 0
    for (stem in Stem.entries) {
        coroutineContext.ensureActive()
        val source = File(dir, "${stem.fileName}.wav")
        if (!source.exists()) continue
        val target = createDestination(stem) ?: throw IOException("저장 파일을 만들 수 없습니다")
        withExportDestination(discard = { discardDestination(target) }) {
            coroutineContext.ensureActive()
            val output = openDestination(target) ?: throw IOException("저장 위치를 열 수 없습니다")
            output.use { out -> source.inputStream().use { it.copyTo(out) } }
            coroutineContext.ensureActive()
        }
        onEach(stem.label)
        count++
    }
    return count
}
