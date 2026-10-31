package com.bandmr.app.youtube

import com.bandmr.app.data.Song
import com.bandmr.app.data.SongDao
import com.bandmr.app.separation.withStemMutation
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSongImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun setMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private val downloaded by lazy {
        Song(title = "video", uri = temp.newFile("video.m4a").toURI().toString(), durationMs = 1000)
    }

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

    @Test(timeout = 10_000)
    fun `재사용 결정 뒤 삭제된 원본은 잠금 밖에서 다시 받고 한 번만 등록한다`() = runTest {
        val source = File(java.net.URI(downloaded.uri))
        val dao = MemoryDao(downloaded.copy(id = 9))
        val deleting = CompletableDeferred<Unit>()
        val reused = CompletableDeferred<Unit>()
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            withStemMutation {
                deleting.complete(Unit)
                reused.await()
                check(source.delete())
                dao.delete(requireNotNull(dao.get(9)))
            }
        }
        deleting.await()
        var downloads = 0
        val added = importYouTubeSong(dao, downloadSong = {
            downloads++
            if (downloads == 1) {
                assertTrue(source.exists())
                reused.complete(Unit)
            } else {
                // 다시 받는 동안 확정 잠금을 잡지 않아야 한다.
                withStemMutation { source.writeBytes(byteArrayOf(1, 2, 3)) }
            }
            downloaded
        }, prepareCache = {
            assertTrue("등록 시점에 원본이 살아 있어야 한다", source.isFile)
        }, rollback = { dao.delete(it) })
        deletion.join()
        assertEquals(2, downloads)
        assertEquals(1, dao.inserts)
        assertEquals(listOf(added), dao.rows)
    }

    @Test(timeout = 10_000)
    fun `등록이 먼저 확정 중이면 삭제는 새 공유 참조를 확인한 뒤 원본을 보존한다`() = runTest {
        val source = File(java.net.URI(downloaded.uri))
        val existing = downloaded.copy(id = 9)
        val dao = MemoryDao(existing)
        val inserting = CompletableDeferred<Unit>()
        val finishInsert = CompletableDeferred<Unit>()
        dao.afterInsert = { inserting.complete(Unit); finishInsert.await() }
        val registration = launch(start = CoroutineStart.UNDISPATCHED) {
            importYouTubeSong(dao, { downloaded }, {}, { dao.delete(it) })
        }
        inserting.await()
        val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
            withStemMutation {
                val shared = dao.getAllOnce().any { it.id != existing.id && it.uri == existing.uri }
                if (!shared) source.delete()
                dao.delete(existing)
            }
        }
        try {
            assertFalse("삭제는 등록 확정이 끝날 때까지 기다린다", deletion.isCompleted)
        } finally {
            finishInsert.complete(Unit)
            registration.join()
            deletion.join()
        }
        assertTrue(source.isFile)
        assertEquals(1, dao.rows.size)
        assertNotEquals(existing.id, dao.rows.single().id)
    }

    @Test(timeout = 10_000)
    fun `다운로드는 곡 확정 잠금 밖에서 실행한다`() = runTest {
        val dao = MemoryDao()
        val added = importYouTubeSong(dao, downloadSong = {
            withStemMutation { downloaded }
        }, prepareCache = {}, rollback = { dao.delete(it) })
        assertEquals(listOf(added), dao.rows)
    }

    @Test(timeout = 10_000)
    fun `원본 재확인 잠금을 기다리는 중 취소하면 행을 만들지 않는다`() = runTest {
        val dao = MemoryDao()
        val locked = CompletableDeferred<Unit>()
        val unlock = CompletableDeferred<Unit>()
        val downloadedSource = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            withStemMutation { locked.complete(Unit); unlock.await() }
        }
        locked.await()
        val registration = launch(start = CoroutineStart.UNDISPATCHED) {
            importYouTubeSong(dao, downloadSong = {
                downloaded.also { downloadedSource.complete(Unit) }
            }, prepareCache = { error("취소된 곡의 캐시 준비 금지") }, rollback = { dao.delete(it) })
        }
        try {
            downloadedSource.await()
            registration.cancelAndJoin()
            assertTrue(dao.rows.isEmpty())
            assertEquals(0, dao.inserts)
            assertTrue(File(java.net.URI(downloaded.uri)).isFile)
        } finally {
            unlock.complete(Unit)
            holder.join()
            registration.cancelAndJoin()
        }
    }

    @Test(timeout = 15_000)
    fun `다운로드 직후 원본이 삭제되면 HTTP로 다시 받아 등록한다`() = runTest {
        val dir = temp.newFolder("retry-source")
        val payload = byteArrayOf(1, 2, 3, 4)
        val requests = AtomicInteger()
        val dao = MemoryDao()
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
            var first = true
            val result = importYouTubeSong(dao, downloadSong = {
                val source = withContext(Dispatchers.IO) {
                    downloadAudioSource(dir, "same-video", "http://127.0.0.1:${server.address.port}/audio", "m4a")
                }
                if (first) {
                    first = false
                    withStemMutation { check(source.delete()) }
                }
                downloaded.copy(uri = source.toURI().toString())
            }, prepareCache = { song ->
                assertArrayEquals(payload, File(java.net.URI(song.uri)).readBytes())
            }, rollback = { dao.delete(it) })
            assertEquals(2, requests.get())
            assertEquals(1, dao.inserts)
            assertEquals(listOf(result), dao.rows)
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
