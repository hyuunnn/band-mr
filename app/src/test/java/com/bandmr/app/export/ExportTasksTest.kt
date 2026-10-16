package com.bandmr.app.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ExportTasksTest {
    @Test
    fun `요청한 화면이 사라져도 저장을 끝내고 재진입에서 결과를 읽는다`() = runTest {
        val tasks = ExportTasks(this)
        val screen = CoroutineScope(coroutineContext + Job())
        val rendering = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var saved = false
        screen.launch {
            assertTrue(tasks.start(7) {
                rendering.complete(Unit)
                finish.await()
                saved = true
                "저장 완료"
            })
        }
        rendering.await()
        screen.cancel()
        assertTrue(tasks.states.value.getValue(7).running)
        assertFalse(tasks.start(7) { error("재진입 중 중복 저장 금지") })
        finish.complete(Unit)
        runCurrent()
        assertTrue(saved)
        assertEquals(ExportStatus(message = "저장 완료"), tasks.states.value[7])
    }

    @Test
    fun `곡별 작업은 서로 진행 상태와 결과를 덮어쓰지 않는다`() = runTest {
        val tasks = ExportTasks(this)
        val finishFirst = CompletableDeferred<Unit>()
        tasks.start(1) { finishFirst.await(); "첫 곡 완료" }
        tasks.start(2) { throw IOException("disk full") }
        runCurrent()
        assertTrue(tasks.states.value.getValue(1).running)
        assertEquals(ExportStatus(message = "실패: disk full"), tasks.states.value[2])
        finishFirst.complete(Unit)
        runCurrent()
        assertEquals(ExportStatus(message = "첫 곡 완료"), tasks.states.value[1])
        assertEquals(ExportStatus(message = "실패: disk full"), tasks.states.value[2])
    }

    @Test
    fun `목적지 삭제까지 실패하면 결과에 파일 정리 실패를 알린다`() = runTest {
        val tasks = ExportTasks(this)
        tasks.start(1) {
            withExportDestination(discard = { throw SecurityException("delete denied") }) {
                throw IOException("disk full")
            }
        }
        runCurrent()
        val status = tasks.states.value.getValue(1)
        assertFalse(status.running)
        assertTrue(status.message!!.contains("disk full"))
        assertTrue(status.message.contains("파일을 삭제하지 못했습니다"))
    }
}
