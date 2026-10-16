package com.bandmr.app.youtube

import com.bandmr.app.data.Song
import com.bandmr.app.data.SongDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class YouTubeSongImportTest {
    private val prefix = "file:///files/sources/dQw4w9WgXcQ."
    private val downloaded = Song(title = "video", uri = "${prefix}m4a", durationMs = 1000)

    @Test
    fun `캐시 실패 후 재시도와 재추가가 곡을 중복 등록하지 않는다`() = runTest {
        val dao = MemoryDao()
        val cache = mutableSetOf<Long>()
        try {
            importYouTubeSong(dao, prefix, { downloaded }, {
                cache += it.id
                error("decode failed")
            }, { dao.delete(it); cache -= it.id })
            fail("실패가 전파되어야 한다")
        } catch (failure: IllegalStateException) {
            assertEquals("decode failed", failure.message)
        }
        assertTrue(dao.rows.isEmpty())
        assertTrue(cache.isEmpty())

        val added = importYouTubeSong(dao, prefix, { downloaded }, { cache += it.id }, { dao.delete(it); cache -= it.id })
        val reused = importYouTubeSong(dao, prefix, { error("원본을 다시 받으면 안 된다") },
            { cache += it.id }, { dao.delete(it); cache -= it.id })
        assertEquals(added, reused)
        assertEquals(listOf(added), dao.rows)
        assertEquals(setOf(added.id), cache)
        assertEquals(2, dao.inserts)
    }

    @Test
    fun `캐시 준비 중 중단하면 신규 행과 캐시를 정리한다`() = runTest {
        val dao = MemoryDao()
        val cache = mutableSetOf<Long>()
        val preparing = CompletableDeferred<Unit>()
        val task = launch {
            importYouTubeSong(dao, prefix, { downloaded }, {
                cache += it.id
                preparing.complete(Unit)
                awaitCancellation()
            }, { dao.delete(it); cache -= it.id })
        }
        preparing.await()
        task.cancelAndJoin()
        assertTrue(dao.rows.isEmpty())
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `취소 후 비동기 파일 정리가 끝날 때까지 기다린다`() = runTest {
        val dao = MemoryDao()
        val cache = mutableSetOf<Long>()
        val preparing = CompletableDeferred<Unit>()
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        val task = launch {
            importYouTubeSong(dao, prefix, { downloaded }, {
                cache += it.id
                preparing.complete(Unit)
                awaitCancellation()
            }, {
                deleting.complete(Unit)
                finishDelete.await()
                dao.delete(it)
                cache -= it.id
            })
        }
        preparing.await()
        task.cancel()
        deleting.await()
        assertEquals(1, dao.rows.size)
        assertFalse(task.isCompleted)
        finishDelete.complete(Unit)
        task.join()
        assertTrue(dao.rows.isEmpty())
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `등록 반환 직전에 중단해도 생성된 ID를 잃지 않고 정리한다`() = runTest {
        val dao = MemoryDao()
        val inserting = CompletableDeferred<Unit>()
        val finishInsert = CompletableDeferred<Unit>()
        dao.afterInsert = {
            inserting.complete(Unit)
            finishInsert.await()
        }
        var prepared = false
        val discarded = mutableListOf<Long>()
        val task = launch {
            importYouTubeSong(dao, prefix, { downloaded }, { prepared = true }, {
                dao.delete(it)
                discarded += it.id
            })
        }
        inserting.await()
        assertEquals(1, dao.rows.size)
        task.cancel()
        finishInsert.complete(Unit)
        task.join()
        assertFalse(prepared)
        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(1L), discarded)
    }

    @Test
    fun `취소를 확인하지 않는 캐시 준비가 늦게 끝나도 신규 등록을 정리한다`() = runTest {
        val dao = MemoryDao()
        val cache = mutableSetOf<Long>()
        val task = launch {
            importYouTubeSong(dao, prefix, { downloaded }, {
                currentCoroutineContext()[Job]!!.cancel()
                cache += it.id
            }, { dao.delete(it); cache -= it.id })
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(dao.rows.isEmpty())
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `기존 영상은 확장자가 달라도 설정과 ID를 보존하며 재사용한다`() = runTest {
        val existing = downloaded.copy(id = 9, uri = "${prefix}webm", semitones = 7, speed = 0.75f)
        val dao = MemoryDao(existing)
        var prepared: Song? = null
        val result = importYouTubeSong(dao, prefix, { error("기존 영상 다운로드 금지") },
            { prepared = it }, { error("기존 캐시 삭제 금지") })
        assertEquals(existing, result)
        assertEquals(existing, prepared)
        assertEquals(0, dao.inserts)
        assertEquals(listOf(existing), dao.rows)
    }

    @Test
    fun `기존 곡의 캐시 재시도가 실패하거나 취소돼도 기존 곡을 지우지 않는다`() = runTest {
        val existing = downloaded.copy(id = 9, semitones = 3)
        val dao = MemoryDao(existing)
        try {
            importYouTubeSong(dao, prefix, { error("다운로드 금지") }, { error("decode failed") },
                { error("기존 캐시 삭제 금지") })
            fail("실패가 전파되어야 한다")
        } catch (failure: IllegalStateException) {
            assertEquals("decode failed", failure.message)
        }
        val preparing = CompletableDeferred<Unit>()
        val task = launch {
            importYouTubeSong(dao, prefix, { error("다운로드 금지") }, {
                preparing.complete(Unit)
                awaitCancellation()
            }, { error("기존 캐시 삭제 금지") })
        }
        preparing.await()
        task.cancelAndJoin()
        assertEquals(listOf(existing), dao.rows)
        assertEquals(0, dao.inserts)
    }

    private class MemoryDao(vararg initial: Song) : SongDao {
        val rows = initial.toMutableList()
        var inserts = 0
        var afterInsert: suspend () -> Unit = {}
        override suspend fun getAllOnce() = rows.toList()
        override suspend fun insert(song: Song): Long {
            val id = (++inserts).toLong()
            rows += song.copy(id = id)
            afterInsert()
            return id
        }
        override suspend fun delete(song: Song) { rows.removeAll { it.id == song.id } }
        override suspend fun get(id: Long) = rows.find { it.id == id }
        override fun observeAll() = flowOf(rows.toList())
        override fun observe(id: Long) = flowOf(rows.find { it.id == id })
        override suspend fun updateStemLevels(id: Long, packed: Long, mask: Int): Unit = error("unused")
        override suspend fun updateSemitones(id: Long, semitones: Int): Unit = error("unused")
        override suspend fun updateSpeed(id: Long, speed: Float): Unit = error("unused")
        override suspend fun updateLoop(id: Long, startMs: Long?, endMs: Long?): Unit = error("unused")
        override suspend fun updateSeparation(id: Long, tier: String, dir: String): Unit = error("unused")
        override suspend fun clearAllSeparation(): Unit = error("unused")
    }
}
