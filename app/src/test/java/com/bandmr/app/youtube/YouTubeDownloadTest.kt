package com.bandmr.app.youtube

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException

class YouTubeDownloadTest {

    private val payload = ByteArray(200) { it.toByte() }

    @Test
    fun `마지막 블로킹 read 중 취소하면 쓰기와 진행률을 실행하지 않는다`() {
        val job = Job()
        val out = ByteArrayOutputStream()
        var progressed = false
        val body = object : ByteArrayInputStream(payload) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                job.cancel()
                return super.read(b, off, len)
            }
        }
        try {
            runBlocking(job) { copyHttpBody(body, out, payload.size.toLong()) { progressed = true } }
            throw AssertionError("취소가 전파되어야 함")
        } catch (_: CancellationException) {
            assertEquals(0, out.size())
            assertFalse(progressed)
        }
    }

    @Test
    fun `길이 미상 read가 취소 후 RST로 끝나도 완료로 처리하지 않는다`() {
        val job = Job()
        val body = object : InputStream() {
            var first = true
            override fun read(): Int = error("bulk read만 사용")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) {
                    first = false
                    b[off] = 1
                    return 1
                }
                job.cancel()
                throw SocketException("Connection reset")
            }
        }
        var returned = false
        try {
            runBlocking(job) {
                copyHttpBody(body, ByteArrayOutputStream(), null)
                returned = true
            }
            throw AssertionError("취소가 전파되어야 함")
        } catch (_: CancellationException) {
            assertFalse(returned)
        }
    }

    @Test
    fun `마지막 진행률에서 취소하면 복사 완료를 반환하지 않는다`() {
        val job = Job()
        var returned = false
        try {
            runBlocking(job) {
                copyHttpBody(ByteArrayInputStream(payload), ByteArrayOutputStream(), payload.size.toLong()) {
                    job.cancel()
                }
                returned = true
            }
            throw AssertionError("취소가 전파되어야 함")
        } catch (_: CancellationException) {
            assertFalse(returned)
        }
    }

    @Test
    fun `전량 수신 후 read 예외는 정상 종료`() {
        val out = ByteArrayOutputStream()
        val n = runBlocking { copyHttpBody(RstAfterEndStream(payload), out, payload.size.toLong()) }
        assertEquals(payload.size.toLong(), n)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `전량 수신 후 close 예외는 정상 종료로 본다`() {
        val body = CloseThrowsStream(payload)
        val out = ByteArrayOutputStream()
        val n = runBlocking { copyHttpBody(body, out, payload.size.toLong()) }
        assertEquals(payload.size.toLong(), n)
        try {
            body.close()
        } catch (e: IOException) {
            assertTrue(downloadReachedTotal(n, payload.size.toLong()))
        }
    }

    @Test
    fun `중간 RST는 그대로 실패`() {
        val out = ByteArrayOutputStream()
        try {
            runBlocking { copyHttpBody(RstAfterEndStream(payload), out, (payload.size * 2).toLong()) }
            throw AssertionError("짧으면 실패해야 함")
        } catch (e: IOException) {
            assertTrue(e.message == "Connection reset" || e.message!!.contains("끊겼"))
        }
    }

    @Test
    fun `Content-Length보다 짧게 EOF면 실패`() {
        val out = ByteArrayOutputStream()
        try {
            runBlocking { copyHttpBody(ByteArrayInputStream(payload), out, (payload.size + 50).toLong()) }
            throw AssertionError("짧으면 실패해야 함")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("끊겼"))
        }
    }

    @Test
    fun `길이를 모를 때 EOF는 받은 만큼 성공`() {
        val out = ByteArrayOutputStream()
        val n = runBlocking { copyHttpBody(ByteArrayInputStream(payload), out, null) }
        assertEquals(payload.size.toLong(), n)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `받은 양이 total 이상이면 완료로 본다`() {
        assertTrue(downloadReachedTotal(100, 100))
        assertTrue(downloadReachedTotal(101, 100))
        assertFalse(downloadReachedTotal(99, 100))
        assertFalse(downloadReachedTotal(100, null))
    }

    @Test
    fun `길이 미상이면 RST도 정상 종료`() {
        val out = ByteArrayOutputStream()
        val n = runBlocking { copyHttpBody(RstAfterEndStream(payload), out, null) }
        assertEquals(payload.size.toLong(), n)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test
    fun `복사 완료 후 close 예외는 파일을 유지한다`() {
        val e = IOException("unexpected end of stream")
        assertTrue(shouldKeepDownload(e, 0, null, 200, copyReturned = true))
        assertTrue(shouldKeepDownload(e, 200, 200, 200, copyReturned = false))
        assertFalse(shouldKeepDownload(e, 50, 200, 50, copyReturned = false))
        assertTrue(isBenignDisconnect(SocketException("Connection reset")))
    }
}

/** 마지막 바이트까지 준 뒤 다음 read에서 RST */
private class RstAfterEndStream(private val data: ByteArray) : InputStream() {
    private var pos = 0
    override fun read(): Int = throw UnsupportedOperationException()
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) throw IOException("Connection reset")
        val n = minOf(len, data.size - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}

/** 본문은 정상이지만 close 때 예외 — HttpURLConnection에서 자주 남 */
private class CloseThrowsStream(data: ByteArray) : ByteArrayInputStream(data) {
    override fun close() {
        throw IOException("unexpected end of stream")
    }
}
