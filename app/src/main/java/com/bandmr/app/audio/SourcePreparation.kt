package com.bandmr.app.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 원본 캐시 준비의 완료 콜백은 최신 요청만 실행한다. 시작·무효화·완료는 메인 스레드에서
 * 직렬화한다. 종료 후 같은 곡을 다시 준비해도 이전 작업이 새 재생기를 덮어쓰지 못한다.
 * 무효화된 디코딩은 캐시를 완성하도록 두고, 싱글턴 스코프도 유지한다.
 */
internal class SourcePreparation(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private var currentRequest: Any? = null

    fun start(prepare: suspend () -> Unit, onComplete: (Boolean) -> Unit) {
        val request = Any()
        currentRequest = request
        scope.launch(ioDispatcher) {
            val ok = runCatching { prepare() }.isSuccess
            withContext(callbackDispatcher) {
                if (currentRequest !== request) return@withContext
                currentRequest = null
                onComplete(ok)
            }
        }
    }

    fun invalidate() {
        currentRequest = null
    }
}
