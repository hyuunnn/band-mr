package com.bandmr.app.separation

import com.bandmr.app.io.FilePromote
import com.bandmr.app.io.CacheStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SeparationCommitTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 10_000)
    fun `마지막 블로킹 추론 중 취소하면 기존 파일과 DB를 보존한다`() = runBlocking {
        val dest = temp.newFolder("stems")
        val old = File(dest, "old.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val part = temp.newFolder("stems.part")
        File(part, "new.wav").writeBytes(byteArrayOf(4, 5, 6))
        val inference = CountDownLatch(1)
        val finishInference = CountDownLatch(1)
        var dbTier = "old"
        val task = launch(Dispatchers.Default) {
            try {
                withContext(Dispatchers.IO) {
                    inference.countDown()
                    check(finishInference.await(5, TimeUnit.SECONDS))
                    commitSeparation(
                        promote = { FilePromote.directory(part, dest); dest },
                        record = { dbTier = "new" },
                    )
                }
            } finally {
                part.deleteRecursively()
            }
        }
        try {
            assertTrue(inference.await(5, TimeUnit.SECONDS))
            task.cancel()
            finishInference.countDown()
            task.join()
            assertArrayEquals(byteArrayOf(1, 2, 3), old.readBytes())
            assertFalse(File(dest, "new.wav").exists())
            assertFalse(part.exists())
            assertEquals("old", dbTier)
        } finally {
            finishInference.countDown()
            task.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun `승격 후 취소되어도 DB 갱신까지 끝낸 뒤 IO에서 복귀한다`() = runBlocking {
        val dest = temp.newFolder("stems")
        File(dest, "old.wav").writeBytes(byteArrayOf(1))
        val part = temp.newFolder("stems.part")
        File(part, "new.wav").writeBytes(byteArrayOf(2, 3))
        val promoted = CompletableDeferred<Unit>()
        val finishRecord = CompletableDeferred<Unit>()
        var dbTier = "old"
        var dbDir = "old-path"
        val task = launch(Dispatchers.Default) {
            withContext(Dispatchers.IO) {
                commitSeparation(
                    promote = { FilePromote.directory(part, dest); dest },
                    record = { dir ->
                        promoted.complete(Unit)
                        finishRecord.await()
                        dbTier = "new"
                        dbDir = dir.absolutePath
                    },
                )
            }
        }
        try {
            promoted.await()
            task.cancel()
            finishRecord.complete(Unit)
            task.join()
            assertTrue(task.isCancelled)
            assertArrayEquals(byteArrayOf(2, 3), File(dest, "new.wav").readBytes())
            assertFalse(File(dest, "old.wav").exists())
            assertEquals("new", dbTier)
            assertEquals(dest.absolutePath, dbDir)
        } finally {
            finishRecord.complete(Unit)
            task.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun `전체 스템 삭제는 확정 중인 파일과 DB 갱신이 끝난 뒤 실행한다`() = runBlocking {
        val root = temp.newFolder("stems")
        val dest = File(root, "1").apply { mkdirs() }
        File(dest, "old.wav").writeBytes(byteArrayOf(1))
        val part = File(root, "1.part").apply { mkdirs() }
        File(part, "new.wav").writeBytes(byteArrayOf(2, 3))
        val promoted = CompletableDeferred<Unit>()
        val finishRecord = CompletableDeferred<Unit>()
        val cleared = CompletableDeferred<Unit>()
        var dbTier: String? = "old"
        val task = launch(Dispatchers.Default) {
            withContext(Dispatchers.IO) {
                commitSeparation(
                    promote = { FilePromote.directory(part, dest); dest },
                    record = {
                        promoted.complete(Unit)
                        finishRecord.await()
                        dbTier = "new"
                    },
                )
            }
        }
        promoted.await()
        val clear = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            withStemMutation {
                CacheStorage.clearSubdirectories(root, includeInFlight = true)
                dbTier = null
                cleared.complete(Unit)
            }
        }
        try {
            assertFalse(cleared.isCompleted)
            task.cancel()
            finishRecord.complete(Unit)
            task.join()
            clear.join()
            assertTrue(cleared.isCompleted)
            assertTrue(root.listFiles()!!.isEmpty())
            assertNull(dbTier)
        } finally {
            finishRecord.complete(Unit)
            task.cancel()
            clear.cancel()
        }
    }
}
