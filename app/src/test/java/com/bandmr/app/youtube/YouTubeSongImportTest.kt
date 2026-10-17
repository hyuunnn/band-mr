package com.bandmr.app.youtube

import com.bandmr.app.data.Song
import com.bandmr.app.data.SongDao
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class YouTubeSongImportTest {
    @get:Rule val temp = TemporaryFolder()

    private val prefix = "file:///files/sources/dQw4w9WgXcQ."
    private val downloaded = Song(title = "video", uri = "${prefix}m4a", durationMs = 1000)

    @Test
    fun `캐시 실패 후 재시도하면 실패한 등록 없이 새 곡 하나만 남는다`() = runTest {
        val dao = MemoryDao()
        val cache = mutableSetOf<Long>()
        try {
            importYouTubeSong(dao, { downloaded }, {
                cache += it.id
                error("decode failed")
            }, { dao.delete(it); cache -= it.id })
            fail("실패가 전파되어야 한다")
        } catch (failure: IllegalStateException) {
            assertEquals("decode failed", failure.message)
        }
        assertTrue(dao.rows.isEmpty())
        assertTrue(cache.isEmpty())

        val added = importYouTubeSong(dao, { downloaded }, { cache += it.id }, { dao.delete(it); cache -= it.id })
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
            importYouTubeSong(dao, { downloaded }, {
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
            importYouTubeSong(dao, { downloaded }, {
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
            importYouTubeSong(dao, { downloaded }, { prepared = true }, {
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
            importYouTubeSong(dao, { downloaded }, {
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
    fun `같은 영상도 기존 곡의 설정을 보존하고 기본 설정의 별도 곡으로 등록한다`() = runTest {
        val existing = downloaded.copy(id = 9, semitones = 7, speed = 0.75f)
        val dao = MemoryDao(existing)
        var prepared: Song? = null
        val result = importYouTubeSong(dao, { downloaded },
            { prepared = it }, { error("기존 캐시 삭제 금지") })
        assertNotEquals(existing.id, result.id)
        assertEquals(downloaded.copy(id = result.id), result)
        assertEquals(result, prepared)
        assertEquals(1, dao.inserts)
        assertEquals(listOf(existing, result), dao.rows)
    }

    @Test
    fun `같은 영상의 재추가가 실패하거나 취소돼도 기존 곡과 캐시는 보존한다`() = runTest {
        val existing = downloaded.copy(id = 9, semitones = 3)
        val dao = MemoryDao(existing)
        val cache = mutableSetOf(existing.id)
        val rollback: suspend (Song) -> Unit = { dao.delete(it); cache -= it.id }
        try {
            importYouTubeSong(dao, { downloaded }, {
                cache += it.id
                error("decode failed")
            }, rollback)
            fail("실패가 전파되어야 한다")
        } catch (failure: IllegalStateException) {
            assertEquals("decode failed", failure.message)
        }
        val preparing = CompletableDeferred<Unit>()
        val task = launch {
            importYouTubeSong(dao, { downloaded }, {
                cache += it.id
                preparing.complete(Unit)
                awaitCancellation()
            }, rollback)
        }
        preparing.await()
        task.cancelAndJoin()
        assertEquals(listOf(existing), dao.rows)
        assertEquals(setOf(existing.id), cache)
        assertEquals(2, dao.inserts)
    }

    @Test(timeout = 15_000)
    fun `원본과 캐시가 없는 영상도 재추가하면 다시 받고 완성 원본은 다음 추가에 재사용한다`() = runTest {
        val dir = temp.newFolder("sources")
        val source = File(dir, "same-video.m4a")
        val existing = downloaded.copy(id = 9, uri = source.toURI().toString())
        val dao = MemoryDao(existing)
        val cache = mutableSetOf<Long>()
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/audio") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
                exchange.close()
            }
            start()
        }
        try {
            assertFalse(source.exists())
            val url = "http://127.0.0.1:${server.address.port}/audio"
            val download: suspend () -> Song = {
                val file = withContext(Dispatchers.IO) {
                    downloadAudioSource(dir, "same-video", url, "m4a")
                }
                downloaded.copy(uri = file.toURI().toString())
            }
            val prepare: suspend (Song) -> Unit = {
                assertArrayEquals(payload, source.readBytes())
                cache += it.id
            }
            val rollback: suspend (Song) -> Unit = { dao.delete(it); cache -= it.id }

            val added = importYouTubeSong(dao, download, prepare, rollback)
            val repeated = importYouTubeSong(dao, download, prepare, rollback)

            assertEquals(1, requests.get())
            assertNotEquals(existing.id, added.id)
            assertNotEquals(added.id, repeated.id)
            assertEquals(listOf(existing, added, repeated), dao.rows)
            assertEquals(setOf(added.id, repeated.id), cache)
        } finally {
            server.stop(0)
        }
    }

    private class MemoryDao(vararg initial: Song) : SongDao {
        val rows = initial.toMutableList()
        private var nextId = initial.maxOfOrNull { it.id } ?: 0L
        var inserts = 0
        var afterInsert: suspend () -> Unit = {}
        override suspend fun getAllOnce() = rows.toList()
        override suspend fun insert(song: Song): Long {
            inserts++
            val id = ++nextId
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
