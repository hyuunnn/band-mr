package com.bandmr.app.export

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.audio.WavReader
import com.bandmr.app.audio.WavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MixWavExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 10_000)
    fun `취소된 렌더의 정리는 새 내보내기를 손상시키지 않는다`() = runBlocking {
        val cache = temp.newFolder("cache")
        val rendering = CountDownLatch(1)
        val finishRender = CountDownLatch(1)
        var cancelledCopy = false
        var firstTmp: File? = null
        val samples = ShortArray(2048) { (it - 1000).toShort() }
        val dest = temp.newFile("saved.wav")
        val first = launch(Dispatchers.IO) {
            writeMixWav(cache, render = { writer ->
                writer.writeShorts(samples, samples.size)
                firstTmp = cache.listFiles()!!.single()
                rendering.countDown()
                check(finishRender.await(5, TimeUnit.SECONDS))
                // 취소를 소비하지 않는 블로킹 렌더도 복사 전에 차단해야 한다.
                writer.writeShorts(samples, samples.size)
            }, copyToDestination = { cancelledCopy = true })
        }
        try {
            assertTrue(rendering.await(5, TimeUnit.SECONDS))
            first.cancel()
            writeMixWav(cache, render = { it.writeShorts(samples, samples.size) }) {
                assertNotEquals(firstTmp, it)
                it.copyTo(dest, overwrite = true)
            }
            assertTrue(firstTmp!!.exists())
            finishRender.countDown()
            first.join()

            assertFalse(cancelledCopy)
            assertFalse(firstTmp.exists())
            assertTrue(cache.listFiles()!!.isEmpty())
            WavReader(dest).use { reader ->
                val actual = ShortArray(samples.size)
                assertEquals(samples.size / 2, reader.read(0, actual, samples.size / 2))
                assertArrayEquals(samples, actual)
            }
        } finally {
            finishRender.countDown()
            first.cancel()
        }
    }

    @Test
    fun `정상 내보내기는 기존 WAV 작성 결과와 바이트가 같다`() = runBlocking {
        val cache = temp.newFolder("cache")
        val reference = temp.newFile("reference.wav")
        val dest = temp.newFile("saved.wav")
        val samples = ShortArray(16_384) { (it * 31).toShort() }
        WavWriter.create(reference, PIPELINE_SAMPLE_RATE).use { it.writeShorts(samples, samples.size) }
        writeMixWav(cache, render = { it.writeShorts(samples, samples.size) }) {
            it.copyTo(dest, overwrite = true)
        }
        assertArrayEquals(reference.readBytes(), dest.readBytes())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `렌더 실패 시 임시 파일을 정리하고 목적지를 건드리지 않는다`() = runBlocking {
        val cache = temp.newFolder("cache")
        var copied = false
        try {
            writeMixWav(cache, render = {
                it.writeShorts(shortArrayOf(1, 2), 2)
                error("render failed")
            }, copyToDestination = { copied = true })
            fail("렌더 실패가 전파되어야 함")
        } catch (e: IllegalStateException) {
            assertEquals("render failed", e.message)
        }
        assertFalse(copied)
        assertTrue(cache.listFiles()!!.isEmpty())
    }
}
