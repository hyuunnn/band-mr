package com.bandmr.app.data

import com.bandmr.app.audio.commitMixCache
import com.bandmr.app.separation.withStemMutation
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SongDeletionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 10_000)
    fun `곡 삭제 뒤 디코딩이 끝나도 정식 캐시를 다시 만들지 않는다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val part = temp.newFile("mix.wav.part").apply { writeBytes(byteArrayOf(1, 2)) }
        val cache = File(temp.root, "mix.wav")

        deleteSong(song, dao, temp.root) { cache.delete() }
        val result = runCatching { commitMixCache(part, cache) { dao.get(song.id) != null } }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertFalse(cache.exists())
        assertNull(dao.row)
    }

    @Test(timeout = 10_000)
    fun `캐시 승격이 먼저 확정 중이면 곡 삭제가 기다렸다가 완성 캐시를 지운다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val part = temp.newFile("mix.wav.part").apply { writeBytes(byteArrayOf(1, 2)) }
        val cache = File(temp.root, "mix.wav")
        val validating = CompletableDeferred<Unit>()
        val finishValidation = CompletableDeferred<Unit>()
        val promotion = launch(start = CoroutineStart.UNDISPATCHED) {
            commitMixCache(part, cache) {
                validating.complete(Unit)
                finishValidation.await()
                dao.get(song.id) != null
            }
        }
        validating.await()
        var cacheDeleted = false
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            deleteSong(song, dao, temp.root) {
                assertTrue("승격이 끝난 캐시를 삭제해야 한다", cache.exists())
                cacheDeleted = cache.delete()
            }
        }
        try {
            assertFalse(cacheDeleted)
            assertFalse(deletion.isCompleted)
            finishValidation.complete(Unit)
            promotion.join()
            deletion.join()
            assertTrue(cacheDeleted)
            assertFalse(cache.exists())
            assertFalse(part.exists())
            assertNull(dao.row)
        } finally {
            finishValidation.complete(Unit)
            promotion.cancelAndJoin()
            deletion.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun `삭제 확정 중 시작한 승격은 DB 삭제까지 기다린 뒤 곡 부재를 확인한다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val part = temp.newFile("mix.wav.part").apply { writeBytes(byteArrayOf(1, 2)) }
        val cache = File(temp.root, "mix.wav")
        val deletingRow = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        dao.beforeDelete = {
            deletingRow.complete(Unit)
            finishDelete.await()
        }
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            deleteSong(song, dao, temp.root) { cache.delete() }
        }
        deletingRow.await()
        var validated = false
        val promotion = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                commitMixCache(part, cache) {
                    validated = true
                    dao.get(song.id) != null
                }
            }
        }
        try {
            assertNotNull(dao.row)
            assertFalse(validated)
            assertFalse(promotion.isCompleted)
            finishDelete.complete(Unit)
            deletion.join()
            assertTrue(promotion.await().exceptionOrNull() is IllegalStateException)
            assertTrue(validated)
            assertFalse(cache.exists())
            assertNull(dao.row)
        } finally {
            finishDelete.complete(Unit)
            deletion.cancelAndJoin()
            promotion.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun `스템 삭제 후 취소되어도 캐시와 DB 행까지 삭제한다`() = runBlocking {
        val stems = temp.newFolder("stems")
        File(stems, "vocals.wav").writeBytes(byteArrayOf(1, 2))
        val cache = temp.newFile("mix.wav")
        val song = song(stems)
        val dao = RecordingSongDao(song)
        val deletingRow = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        dao.beforeDelete = {
            deletingRow.complete(Unit)
            finishDelete.await()
        }
        val task = launch(Dispatchers.Default) {
            withContext(Dispatchers.IO) {
                deleteSong(song, dao, temp.root) { cache.delete() }
            }
        }
        try {
            deletingRow.await()
            assertFalse(stems.exists())
            assertFalse(cache.exists())
            assertNotNull(dao.row)
            task.cancel()
            finishDelete.complete(Unit)
            task.join()
            assertTrue(task.isCancelled)
            assertNull(dao.row)
        } finally {
            finishDelete.complete(Unit)
            task.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun `스템 변경 락 대기 중 취소되면 파일과 DB 행을 보존한다`() = runBlocking {
        val stems = temp.newFolder("stems")
        val vocal = File(stems, "vocals.wav").apply { writeBytes(byteArrayOf(1, 2)) }
        val cache = temp.newFile("mix.wav")
        val song = song(stems)
        val dao = RecordingSongDao(song)
        val locked = CompletableDeferred<Unit>()
        val unlock = CompletableDeferred<Unit>()
        val mutation = launch(Dispatchers.IO) {
            withStemMutation {
                locked.complete(Unit)
                unlock.await()
            }
        }
        try {
            locked.await()
            // UNDISPATCHED로 시작해 삭제 요청이 락에서 대기한 뒤에 취소한다.
            val deletion = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                deleteSong(song, dao, temp.root) { cache.delete() }
            }
            deletion.cancelAndJoin()
            assertArrayEquals(byteArrayOf(1, 2), vocal.readBytes())
            assertTrue(cache.exists())
            assertEquals(song, dao.row)
        } finally {
            unlock.complete(Unit)
            mutation.join()
        }
    }

    @Test(timeout = 10_000)
    fun `미분리 곡도 캐시와 DB 행을 삭제한다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val cache = temp.newFile("mix.wav")
        withContext(Dispatchers.IO) {
            deleteSong(song, dao, temp.root) { cache.delete() }
        }
        assertFalse(cache.exists())
        assertNull(dao.row)
    }

    @Test
    fun `임포트 롤백은 다운로드 원본을 남기고 캐시와 등록을 삭제한다`() = runBlocking {
        val sources = temp.newFolder("sources")
        val source = File(sources, "video.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val song = song(null).copy(uri = "file://${source.absolutePath}")
        val dao = RecordingSongDao(song)
        val cache = temp.newFile("mix.wav")
        withContext(Dispatchers.IO) {
            deleteSong(song, dao, sources, keepSource = true) { cache.delete() }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        assertFalse(cache.exists())
        assertNull(dao.row)
    }

    @Test
    fun `등록 당시 미분리여도 삭제 시점에 완료된 스템까지 정리한다`() = runBlocking {
        val original = song(null)
        val stems = temp.newFolder("completed-stems")
        File(stems, "vocals.wav").writeBytes(byteArrayOf(1, 2))
        val dao = RecordingSongDao(original.copy(stemsDir = stems.absolutePath, separatedTier = "balanced"))
        val cache = temp.newFile("mix.wav")
        withContext(Dispatchers.IO) {
            deleteSong(original, dao, temp.root) { cache.delete() }
        }
        assertFalse(stems.exists())
        assertFalse(cache.exists())
        assertNull(dao.row)
    }

    private fun song(stems: File?) = Song(
        id = 1L,
        title = "test",
        uri = "content://test/song",
        durationMs = 1_000L,
        separatedTier = if (stems != null) "balanced" else null,
        stemsDir = stems?.absolutePath,
    )

    private class RecordingSongDao(var row: Song?) : SongDao {
        var beforeDelete: suspend () -> Unit = {}

        override fun observeAll() = flowOf(listOfNotNull(row))
        override fun observe(id: Long) = flowOf(row?.takeIf { it.id == id })
        override suspend fun getAllOnce() = listOfNotNull(row)
        override suspend fun get(id: Long) = row?.takeIf { it.id == id }
        override suspend fun delete(song: Song) {
            beforeDelete()
            if (row?.id == song.id) row = null
        }

        override suspend fun insert(song: Song): Long = error("unused")
        override suspend fun updateStemLevels(id: Long, packed: Long, mask: Int): Unit = error("unused")
        override suspend fun updateSemitones(id: Long, semitones: Int): Unit = error("unused")
        override suspend fun updateSpeed(id: Long, speed: Float): Unit = error("unused")
        override suspend fun updateLoop(id: Long, startMs: Long?, endMs: Long?): Unit = error("unused")
        override suspend fun updateSeparation(id: Long, tier: String, dir: String): Unit = error("unused")
        override suspend fun clearAllSeparation(): Unit = error("unused")
    }
}
