package com.bandmr.app.export

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream

class ExportDestinationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `렌더 실패 시 미리 생성된 빈 목적지와 임시 WAV를 삭제한다`() = runTest {
        val cache = temp.newFolder("cache")
        val dest = temp.newFile("saved.wav")
        try {
            withExportDestination(discard = { check(dest.delete()) }) {
                writeMixWav(cache, render = { error("render failed") }) {
                    fail("실패한 렌더는 복사하면 안 된다")
                }
            }
            fail("실패가 전파되어야 한다")
        } catch (failure: IllegalStateException) {
            assertEquals("render failed", failure.message)
        }
        assertFalse(dest.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `목적지 쓰기가 중간에 실패하면 잘린 WAV도 삭제한다`() = runTest {
        val cache = temp.newFolder("cache")
        val dest = temp.newFile("saved.wav")
        val writeFailure = IOException("disk full")
        var bytesBeforeCleanup = 0L
        try {
            withExportDestination(discard = {
                bytesBeforeCleanup = dest.length()
                check(dest.delete())
            }) {
                writeMixWav(cache, render = { it.writeShorts(ShortArray(2048), 2048) }) { tmp ->
                    tmp.inputStream().use { input ->
                        object : OutputStream() {
                            val output = dest.outputStream()
                            var remaining = 128
                            override fun write(value: Int) {
                                if (remaining-- <= 0) throw writeFailure
                                output.write(value)
                            }
                            override fun close() = output.close()
                        }.use { input.copyTo(it) }
                    }
                }
            }
            fail("쓰기 오류가 전파되어야 한다")
        } catch (failure: IOException) {
            assertSame(writeFailure, failure)
        }
        assertEquals(128L, bytesBeforeCleanup)
        assertFalse(dest.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `취소된 렌더도 목적지 삭제가 끝날 때까지 기다린다`() = runTest {
        val cache = temp.newFolder("cache")
        val dest = temp.newFile("saved.wav")
        val rendering = CompletableDeferred<Unit>()
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        val task = launch {
            withExportDestination(discard = {
                deleting.complete(Unit)
                finishDelete.await()
                check(dest.delete())
            }) {
                writeMixWav(cache, render = {
                    it.writeShorts(shortArrayOf(1, 2), 2)
                    rendering.complete(Unit)
                    awaitCancellation()
                }) { fail("취소된 렌더는 복사하면 안 된다") }
            }
        }
        rendering.await()
        task.cancel()
        deleting.await()
        assertFalse(task.isCompleted)
        assertTrue(cache.listFiles()!!.isEmpty())
        finishDelete.complete(Unit)
        task.join()
        assertFalse(dest.exists())
    }

    @Test
    fun `삭제 권한 오류는 원래 실패 원인을 보존하며 전달한다`() = runTest {
        val original = IOException("copy failed")
        val cleanup = SecurityException("delete denied")
        try {
            withExportDestination(discard = { throw cleanup }) { throw original }
            fail("원래 오류가 전파되어야 한다")
        } catch (failure: IOException) {
            assertSame(original, failure)
            assertTrue(failure.suppressed.single() is ExportDestinationCleanupException)
            assertSame(cleanup, failure.suppressed.single().cause)
        }
    }

    @Test
    fun `성공한 저장은 목적지를 보존한다`() = runTest {
        val cache = temp.newFolder("cache")
        val dest = temp.newFile("saved.wav")
        withExportDestination(discard = { fail("완료한 파일 삭제 금지") }) {
            writeMixWav(cache, render = { it.writeShorts(shortArrayOf(1, 2), 2) }) {
                it.copyTo(dest, overwrite = true)
            }
        }
        assertEquals(48L, dest.length())
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
