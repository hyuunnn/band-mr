package com.bandmr.app.youtube

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class YouTubeSourceFileTest {
    @get:Rule val temp = TemporaryFolder()

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
}
