package com.bandmr.app.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 원본 캐시 준비의 완료 콜백은 최신 요청만 실행한다. 시작·무효화·완료는 메인 스레드에서
 * 직렬화한다. 종료 후 같은 곡을 다시 준비해도 이전 작업이 새 재생기를 덮어쓰지 못한다.
 * 일반 무효화는 디코딩이 캐시를 완성하도록 둔다. 캐시 비우기만 [cancelAndJoin]으로
 * 기존 작업을 취소하고 종료를 기다린다. 싱글턴 스코프는 유지한다.
 */
internal class SourcePreparation(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private var currentRequest: Any? = null
    private val jobs = mutableListOf<Job>()

    fun start(prepare: suspend () -> Unit, onComplete: (Boolean) -> Unit) {
        val request = Any()
        currentRequest = request
        jobs.removeAll { it.isCompleted }
        jobs += scope.launch(ioDispatcher) {
            val ok = try {
                prepare()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
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

    suspend fun cancelAndJoin() {
        invalidate()
        val pending = jobs.toList()
        pending.forEach { it.cancel() }
        pending.forEach { it.join() }
        jobs.removeAll { it.isCompleted }
    }
}
