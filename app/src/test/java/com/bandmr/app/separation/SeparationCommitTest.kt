package com.bandmr.app.separation

import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 분리 결과 공개([SeparationService.commitStems])의 취소 계약: 파일과 DB가 같은 상태여야 한다.
 *
 * 취소는 세그먼트 경계에서만 보므로 마지막 세그먼트 중에 취소하면 분리가 정상 반환한다.
 * 수정 전에는 그대로 승격한 뒤 취소가 터져 DB 갱신만 건너뛰었다(파일은 새 구성, DB는 이전 구성).
 */
class SeparationCommitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `분리가 정상 반환했어도 이미 취소됐으면 승격도 기록도 하지 않는다`() = runBlocking {
        val (part, dest) = stemDirs()
        var recorded = false
        val job = launch {
            // 마지막 세그먼트 중 취소: 분리는 끝났고 취소는 이미 걸려 있다
            coroutineContext.job.cancel()
            SeparationService.commitStems(part, dest) { recorded = true }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(recorded)
        assertEquals("이전 스템이 그대로여야 DB(이전 티어)와 맞는다", "old", File(dest, STEM).readText())
        assertTrue("임시 디렉터리 정리는 run()의 catch 몫이다", part.isDirectory)
    }

    @Test
    fun `승격을 시작한 뒤 들어온 취소에도 기록까지 끝낸다`() = runBlocking {
        val (part, dest) = stemDirs()
        var recorded: File? = null
        val job = launch {
            val self = coroutineContext.job
            SeparationService.commitStems(part, dest) { dir ->
                self.cancel()
                delay(10) // 취소 가능한 구간이라면 여기서 끊겨 기록을 건너뛴다
                recorded = dir
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(dest, recorded)
        assertEquals("new", File(dest, STEM).readText())
        assertFalse(part.exists())
    }

    /** 다시 분리하는 상황: 정식 디렉터리에 이전 스템, `.part`에 새 스템 */
    private fun stemDirs(): Pair<File, File> {
        val part = tmp.newFolder("7.part").also { File(it, STEM).writeText("new") }
        val dest = tmp.newFolder("7").also { File(it, STEM).writeText("old") }
        return part to dest
    }

    private companion object {
        const val STEM = "vocals.wav"
    }
}
