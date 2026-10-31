package com.bandmr.app.data

import com.bandmr.app.audio.commitMixCache
import com.bandmr.app.io.FilePromote
import com.bandmr.app.separation.commitSeparation
import com.bandmr.app.separation.withStemMutation
import java.io.File
import java.io.IOException
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
    fun `삭제 중 대기한 스템 승격은 락 안에서 곡 부재를 확인하고 건너뛴다`() = runBlocking {
        val dest = temp.newFolder("stems")
        File(dest, "old.wav").writeBytes(byteArrayOf(1))
        val part = temp.newFolder("stems.part")
        File(part, "new.wav").writeBytes(byteArrayOf(2, 3))
        val song = song(dest)
        val dao = RecordingSongDao(song)
        val deletingRow = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        dao.beforeDelete = {
            deletingRow.complete(Unit)
            finishDelete.await()
        }
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            deleteSong(song, dao, temp.root) {}
        }
        deletingRow.await()
        var checked = false
        var recorded = false
        val promotion = async(start = CoroutineStart.UNDISPATCHED) {
            commitSeparation(
                songExists = { checked = true; dao.get(song.id) != null },
                promote = { FilePromote.directory(part, dest); dest },
                record = { recorded = true; dao.updateSeparation(song.id, "new", it.absolutePath) },
            )
        }
        try {
            assertFalse(checked)
            assertFalse(promotion.isCompleted)
            finishDelete.complete(Unit)
            deletion.join()
            assertFalse(promotion.await())
            assertTrue(checked)
            assertFalse(recorded)
            assertNull(dao.row)
            assertFalse(dest.exists())
            assertTrue("승격하지 않은 산출물 정리는 호출자가 맡는다", part.exists())
        } finally {
            finishDelete.complete(Unit)
            deletion.cancelAndJoin()
            promotion.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun `스템 승격이 먼저 확정 중이면 삭제가 DB 갱신까지 기다려 새 스템도 지운다`() = runBlocking {
        val dest = temp.newFolder("stems")
        File(dest, "old.wav").writeBytes(byteArrayOf(1))
        val part = temp.newFolder("stems.part")
        File(part, "new.wav").writeBytes(byteArrayOf(2, 3))
        val song = song(dest)
        val dao = RecordingSongDao(song)
        val promoted = CompletableDeferred<Unit>()
        val finishRecord = CompletableDeferred<Unit>()
        val promotion = async(start = CoroutineStart.UNDISPATCHED) {
            commitSeparation(
                songExists = { dao.get(song.id) != null },
                promote = { FilePromote.directory(part, dest); dest },
                record = {
                    promoted.complete(Unit)
                    finishRecord.await()
                    dao.updateSeparation(song.id, "new", it.absolutePath)
                },
            )
        }
        promoted.await()
        var cleared = false
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            deleteSong(song, dao, temp.root) { cleared = true }
        }
        try {
            assertFalse(cleared)
            assertFalse(deletion.isCompleted)
            assertTrue(File(dest, "new.wav").exists())
            finishRecord.complete(Unit)
            assertTrue(promotion.await())
            deletion.join()
            assertTrue(cleared)
            assertNull(dao.row)
            assertFalse(dest.exists())
            assertFalse(part.exists())
        } finally {
            finishRecord.complete(Unit)
            promotion.cancelAndJoin()
            deletion.cancelAndJoin()
        }
    }

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

    @Test
    fun `마지막 로컬 곡을 지운 뒤 파일 접근 권한을 반납한다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val released = mutableListOf<String>()
        deleteSong(song, dao, temp.root, releaseSourcePermission = {
            assertNull("DB 삭제를 완료한 뒤 권한을 반납한다", dao.row)
            released += it
        }) {}
        assertEquals(listOf(song.uri), released)
    }

    @Test
    fun `같은 로컬 파일을 쓰는 다른 곡이 있으면 접근 권한을 유지한다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val shared = song.copy(id = 2, semitones = 5)
        dao.otherRows += shared
        val released = mutableListOf<String>()
        deleteSong(song, dao, temp.root, releaseSourcePermission = { released += it }) {}
        assertTrue(released.isEmpty())
        assertEquals(listOf(shared), dao.getAllOnce())
    }

    @Test
    fun `DB 삭제가 실패하면 원본 접근 권한을 반납하지 않는다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        dao.beforeDelete = { throw IOException("delete failed") }
        var released = false
        try {
            deleteSong(song, dao, temp.root, releaseSourcePermission = { released = true }) {}
            fail("삭제 실패가 전파되어야 한다")
        } catch (failure: IOException) {
            assertEquals("delete failed", failure.message)
        }
        assertFalse(released)
        assertEquals(song, dao.row)
    }

    @Test
    fun `로컬 등록 실패 시 새로 받은 권한만 반납한다`() = runBlocking {
        for (shared in listOf(false, true)) {
            val song = song(null)
            val dao = RecordingSongDao(if (shared) song else null)
            dao.beforeInsert = { throw IOException("insert failed") }
            val events = mutableListOf<String>()
            try {
                registerLocalSong(song.copy(id = 0), dao, { events += "take" }, { events += "release:$it" })
                fail("등록 실패가 전파되어야 한다")
            } catch (failure: IOException) {
                assertEquals("insert failed", failure.message)
            }
            assertEquals(if (shared) listOf("take") else listOf("take", "release:${song.uri}"), events)
            assertEquals(if (shared) listOf(song) else emptyList<Song>(), dao.getAllOnce())
        }
    }

    @Test(timeout = 10_000)
    fun `같은 파일 등록 중 삭제는 등록 확정 뒤 공유 여부를 확인한다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val inserting = CompletableDeferred<Unit>()
        val finishInsert = CompletableDeferred<Unit>()
        dao.beforeInsert = { inserting.complete(Unit); finishInsert.await() }
        val released = mutableListOf<String>()
        val registration = async(start = CoroutineStart.UNDISPATCHED) {
            registerLocalSong(song.copy(id = 0), dao, {}, { released += it })
        }
        inserting.await()
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            deleteSong(song, dao, temp.root, releaseSourcePermission = { released += it }) {}
        }
        try {
            assertFalse(deletion.isCompleted)
            finishInsert.complete(Unit)
            val newId = registration.await()
            deletion.join()
            assertTrue(released.isEmpty())
            assertEquals(listOf(newId), dao.getAllOnce().map { it.id })
        } finally {
            finishInsert.complete(Unit)
            registration.cancelAndJoin()
            deletion.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun `삭제 중 같은 파일을 등록하면 권한 반납 뒤 다시 획득한다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        dao.beforeDelete = { deleting.complete(Unit); finishDelete.await() }
        val events = mutableListOf<String>()
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            deleteSong(song, dao, temp.root, releaseSourcePermission = { events += "release" }) {}
        }
        deleting.await()
        val registration = async(start = CoroutineStart.UNDISPATCHED) {
            registerLocalSong(song.copy(id = 0), dao, { events += "take" }, { events += "rollback" })
        }
        try {
            assertTrue(events.isEmpty())
            finishDelete.complete(Unit)
            deletion.join()
            val newId = registration.await()
            assertEquals(listOf("release", "take"), events)
            assertEquals(listOf(newId), dao.getAllOnce().map { it.id })
        } finally {
            finishDelete.complete(Unit)
            deletion.cancelAndJoin()
            registration.cancelAndJoin()
        }
    }

    @Test
    fun `시작 권한 정리는 DB가 참조하지 않는 권한만 반납한다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        val stale = "content://test/deleted-song"
        val released = mutableListOf<String>()
        cleanUpUnusedSongPermissions(dao, listOf(song.uri, stale)) { released += it }
        assertEquals(listOf(stale), released)
        assertEquals(song, dao.row)
    }

    @Test(timeout = 10_000)
    fun `시작 권한 정리는 진행 중인 등록 확정 뒤 새 참조를 확인한다`() = runBlocking {
        val song = song(null).copy(id = 0)
        val dao = RecordingSongDao(null)
        val inserting = CompletableDeferred<Unit>()
        val finishInsert = CompletableDeferred<Unit>()
        dao.beforeInsert = { inserting.complete(Unit); finishInsert.await() }
        val stale = "content://test/deleted-song"
        val released = mutableListOf<String>()
        val registration = async(start = CoroutineStart.UNDISPATCHED) {
            registerLocalSong(song, dao, {}, { released += it })
        }
        inserting.await()
        val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
            cleanUpUnusedSongPermissions(dao, listOf(song.uri, stale)) { released += it }
        }
        try {
            assertFalse(cleanup.isCompleted)
            assertTrue(released.isEmpty())
            finishInsert.complete(Unit)
            val id = registration.await()
            cleanup.join()
            assertEquals(listOf(stale), released)
            assertEquals(listOf(song.copy(id = id)), dao.getAllOnce())
        } finally {
            finishInsert.complete(Unit)
            registration.cancelAndJoin()
            cleanup.cancelAndJoin()
        }
    }

    @Test
    fun `권한 획득을 거부당하면 새 행을 만들거나 기존 공유 권한을 반납하지 않는다`() = runBlocking {
        val song = song(null)
        val dao = RecordingSongDao(song)
        var inserting = false
        var released = false
        dao.beforeInsert = { inserting = true }
        try {
            registerLocalSong(song.copy(id = 0), dao, { throw SecurityException("permission denied") }, { released = true })
            fail("권한 획득 실패가 전파되어야 한다")
        } catch (failure: SecurityException) {
            assertEquals("permission denied", failure.message)
        }
        assertFalse(inserting)
        assertFalse(released)
        assertEquals(listOf(song), dao.getAllOnce())
    }

    @Test(timeout = 10_000)
    fun `권한 획득 후 등록 중 취소되어도 행 확정을 마쳐 참조와 권한을 함께 남긴다`() = runBlocking {
        val song = song(null).copy(id = 0)
        val dao = RecordingSongDao(null)
        val inserting = CompletableDeferred<Unit>()
        val finishInsert = CompletableDeferred<Unit>()
        dao.beforeInsert = { inserting.complete(Unit); finishInsert.await() }
        var permissionHeld = false
        val registration = launch(start = CoroutineStart.UNDISPATCHED) {
            registerLocalSong(song, dao, { permissionHeld = true }, { permissionHeld = false })
        }
        try {
            inserting.await()
            registration.cancel()
            finishInsert.complete(Unit)
            registration.join()
            assertTrue(registration.isCancelled)
            assertTrue(permissionHeld)
            assertEquals(listOf(song.uri), dao.getAllOnce().map { it.uri })
        } finally {
            finishInsert.complete(Unit)
            registration.cancelAndJoin()
        }
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
        val otherRows = mutableListOf<Song>()
        var beforeDelete: suspend () -> Unit = {}
        var beforeInsert: suspend () -> Unit = {}

        override fun observeAll() = flowOf(listOfNotNull(row) + otherRows)
        override fun observe(id: Long) = flowOf(row?.takeIf { it.id == id })
        override suspend fun getAllOnce() = listOfNotNull(row) + otherRows
        override suspend fun get(id: Long) = row?.takeIf { it.id == id }
        override suspend fun delete(song: Song) {
            beforeDelete()
            if (row?.id == song.id) row = null
            otherRows.removeAll { it.id == song.id }
        }

        override suspend fun insert(song: Song): Long {
            beforeInsert()
            val id = (getAllOnce().maxOfOrNull { it.id } ?: 0L) + 1L
            otherRows += song.copy(id = id)
            return id
        }
        override suspend fun updateStemLevels(id: Long, packed: Long, mask: Int): Unit = error("unused")
        override suspend fun updateSemitones(id: Long, semitones: Int): Unit = error("unused")
        override suspend fun updateSpeed(id: Long, speed: Float): Unit = error("unused")
        override suspend fun updateLoop(id: Long, startMs: Long?, endMs: Long?): Unit = error("unused")
        override suspend fun updateSeparation(id: Long, tier: String, dir: String) {
            row?.takeIf { it.id == id }?.let { row = it.copy(separatedTier = tier, stemsDir = dir) }
        }
        override suspend fun clearAllSeparation(): Unit = error("unused")
    }
}
