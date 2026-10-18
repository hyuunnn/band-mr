package com.bandmr.app.youtube

import com.bandmr.app.Locator
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeSourceFileTest {
    @get:Rule val temp = TemporaryFolder()

    private var previousCleanup: CompletableDeferred<Unit>? = null
    private var previousPreCache: Job? = null

    @Before
    fun rememberStartupJobs() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousCleanup = Locator.startupCleanup
        previousPreCache = Locator.startupPreCacheJob
    }

    @After
    fun restoreStartupJobs() {
        Locator.startupCleanup = previousCleanup
        Locator.startupPreCacheJob = previousPreCache
        Dispatchers.resetMain()
    }

    @Test(timeout = 10_000)
    fun `시작 정리 중에는 부분 파일을 만들지 않고 정리 후에는 사전 변환을 기다리지 않는다`() = runBlocking {
        val dir = temp.newFolder("sources")
        val stale = File(dir, "previous.m4a.part").apply { writeBytes(byteArrayOf(9)) }
        val cleanup = CompletableDeferred<Unit>()
        val preCache = Job()
        Locator.startupCleanup = cleanup
        Locator.startupPreCacheJob = preCache
        val payload = byteArrayOf(1, 2, 3, 4)
        val requests = AtomicInteger()
        val server = audioServer(payload, requests)
        val downloading = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            downloadAudioSource(dir, "new-video", "http://127.0.0.1:${server.address.port}/audio", "m4a")
        }
        try {
            assertFalse(downloading.isCompleted)
            assertEquals(0, requests.get())
            assertEquals(listOf(stale), dir.listFiles()!!.toList())

            // 시작 시점에 남은 파일 삭제가 끝난 뒤에만 새 다운로드를 허용한다.
            check(stale.delete())
            cleanup.complete(Unit)
            val source = withTimeout(5_000) { downloading.await() }
            assertArrayEquals(payload, source.readBytes())
            assertEquals(1, requests.get())
            assertTrue(preCache.isActive)
            assertEquals(listOf(source), dir.listFiles()!!.toList())
        } finally {
            downloading.cancelAndJoin()
            preCache.cancel()
            cleanup.complete(Unit)
            server.stop(0)
        }
    }

    @Test(timeout = 10_000)
    fun `완성 원본 재사용도 정리를 기다리고 그 사이 지워졌으면 다시 다운로드한다`() = runBlocking {
        val dir = temp.newFolder("sources")
        val source = File(dir, "same-video.m4a").apply { writeBytes(byteArrayOf(9)) }
        val cleanup = CompletableDeferred<Unit>()
        Locator.startupCleanup = cleanup
        val payload = byteArrayOf(1, 2, 3, 4)
        val requests = AtomicInteger()
        val server = audioServer(payload, requests)
        val downloading = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            downloadAudioSource(dir, "same-video", "http://127.0.0.1:${server.address.port}/audio", "m4a")
        }
        try {
            assertFalse(downloading.isCompleted)
            assertEquals(0, requests.get())
            check(source.delete())
            cleanup.complete(Unit)

            assertEquals(source, withTimeout(5_000) { downloading.await() })
            assertArrayEquals(payload, source.readBytes())
            assertEquals(1, requests.get())
        } finally {
            downloading.cancelAndJoin()
            cleanup.complete(Unit)
            server.stop(0)
        }
    }

    @Test(timeout = 10_000)
    fun `정리 대기 중 취소하면 파일을 만들지 않고 다른 대기자의 정리 신호도 풀지 않는다`() = runBlocking {
        val dir = File(temp.root, "sources")
        val cleanup = CompletableDeferred<Unit>()
        Locator.startupCleanup = cleanup
        val downloading = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            downloadAudioSource(dir, "cancelled", "http://127.0.0.1:1/audio", "m4a")
        }
        try {
            downloading.cancelAndJoin()
            assertFalse(dir.exists())
            assertFalse(cleanup.isCompleted)
            val other = async(start = CoroutineStart.UNDISPATCHED) { Locator.awaitStartupCleanup() }
            assertFalse(other.isCompleted)
            cleanup.complete(Unit)
            other.await()
        } finally {
            downloading.cancelAndJoin()
            cleanup.complete(Unit)
        }
    }

    @Test(timeout = 15_000)
    fun `길이 미상 수신 중 연결이 끊기면 부분 파일을 버리고 재시도 때 다시 받는다`() = runBlocking {
        val dir = temp.newFolder("sources")
        val payload = ByteArray(400) { it.toByte() }
        val receivedPrefix = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val responses = executor.submit {
            repeat(2) { attempt ->
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val request = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (true) {
                        val line = request.readLine() ?: error("요청이 중간에 끊겼습니다")
                        if (line.isEmpty()) break
                    }
                    requests.incrementAndGet()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(payload, 0, if (attempt == 0) 80 else payload.size)
                    output.flush()
                    if (attempt == 0) {
                        check(receivedPrefix.await(3, TimeUnit.SECONDS))
                        // 클라이언트가 일부를 파일에 쓴 뒤 실제 TCP RST로 수신을 끝낸다.
                        socket.setSoLinger(true, 0)
                    }
                }
            }
        }
        val url = "http://127.0.0.1:${server.localPort}/audio"
        try {
            try {
                downloadAudioSource(dir, "same-video", url, "m4a") { _, total ->
                    assertNull(total)
                    receivedPrefix.countDown()
                }
                fail("수신 중 연결 오류는 전파되어야 한다")
            } catch (_: IOException) {
                assertTrue("완성 원본과 .part 모두 남기지 않는다", dir.listFiles()!!.isEmpty())
            }

            // 두 번째 응답도 Content-Length가 없지만 정상 EOF로 끝나므로 성공한다.
            val source = downloadAudioSource(dir, "same-video", url, "m4a")
            responses.get(3, TimeUnit.SECONDS)
            assertArrayEquals(payload, source.readBytes())
            assertEquals(listOf(source), dir.listFiles()!!.toList())
            assertEquals(2, requests.get())
            assertEquals(source, downloadAudioSource(dir, "same-video", url, "m4a"))
            assertEquals("완성 원본은 다시 받지 않는다", 2, requests.get())
        } finally {
            receivedPrefix.countDown()
            server.close()
            responses.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test(timeout = 15_000)
    fun `취소된 다운로드 정리는 새 작업의 부분 파일을 삭제하거나 승격하지 않는다`() = runBlocking {
        val dir = temp.newFolder("sources")
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val firstProgress = CountDownLatch(1)
        val resumeFirst = CountDownLatch(1)
        val secondProgress = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val finishSecond = CountDownLatch(1)
        val requests = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/audio") { exchange ->
                val finish = if (requests.incrementAndGet() == 1) finishFirst else finishSecond
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { body ->
                    body.write(payload, 0, 4)
                    body.flush()
                    check(finish.await(5, TimeUnit.SECONDS))
                    body.write(payload, 4, 4)
                }
                exchange.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/audio"
        var secondResult: File? = null
        val first = launch(Dispatchers.IO) {
            downloadAudioSource(dir, "same-video", url, "m4a") { _, _ ->
                firstProgress.countDown()
                check(resumeFirst.await(5, TimeUnit.SECONDS))
            }
        }
        var second: kotlinx.coroutines.Job? = null
        try {
            assertTrue(firstProgress.await(5, TimeUnit.SECONDS))
            first.cancel()
            second = launch(Dispatchers.IO) {
                secondResult = downloadAudioSource(dir, "same-video", url, "m4a") { _, _ ->
                    secondProgress.countDown()
                }
            }
            assertTrue(secondProgress.await(5, TimeUnit.SECONDS))
            assertEquals(2, dir.listFiles()!!.count { it.name.endsWith(".part") })
            finishFirst.countDown()
            resumeFirst.countDown()
            first.join()
            assertTrue(first.isCancelled)
            assertFalse(File(dir, "same-video.m4a").exists())
            assertEquals(1, dir.listFiles()!!.count { it.name.endsWith(".part") })
            assertEquals(4L, dir.listFiles()!!.single().length())

            finishSecond.countDown()
            second.join()
            assertArrayEquals(payload, secondResult!!.readBytes())
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
            // 완성된 원본 재사용도 그대로 동작한다.
            assertEquals(secondResult, downloadAudioSource(dir, "same-video", url, "m4a"))
            assertEquals(2, requests.get())
        } finally {
            finishFirst.countDown()
            resumeFirst.countDown()
            finishSecond.countDown()
            first.cancel()
            second?.cancel()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun audioServer(payload: ByteArray, requests: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/audio") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
                exchange.close()
            }
            start()
        }
}
