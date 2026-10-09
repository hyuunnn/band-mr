package com.bandmr.app.export

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.audio.WavWriter
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 내보내기마다 임시 WAV를 소유한다. 화면 이탈로 취소된 렌더가 늦게 끝나도 다른 저장의
 * 파일을 지울 수 없으며, WAV 헤더를 닫은 뒤 취소를 확인해 목적지 복사 시작을 막는다.
 */
internal suspend fun writeMixWav(
    cacheDir: File,
    render: suspend (WavWriter) -> Unit,
    copyToDestination: (File) -> Unit,
) {
    coroutineContext.ensureActive()
    val tmp = File.createTempFile("export_mix-", ".wav", cacheDir)
    try {
        WavWriter.create(tmp, PIPELINE_SAMPLE_RATE).use { render(it) }
        coroutineContext.ensureActive()
        copyToDestination(tmp)
    } finally {
        tmp.delete()
    }
}
