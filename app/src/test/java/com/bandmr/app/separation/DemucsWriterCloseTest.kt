package com.bandmr.app.separation

import com.bandmr.app.audio.WavReader
import com.bandmr.app.audio.WavWriter
import com.bandmr.app.io.FilePromote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

class DemucsWriterCloseTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `닫기 실패는 다른 작성기도 닫은 뒤 승격과 완료 기록을 막는다`() = runBlocking {
        val part = temp.newFolder("stems.part")
        val dest = temp.newFolder("stems")
        val old = File(dest, "vocals.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val failure = IOException("close failed")
        var closed = false
        val failed = failingWriter(File(part, "vocals.wav"), failure) { closed = true }
        val healthyFile = File(part, "drums.wav")
        val healthy = WavWriter.create(healthyFile, 44100)
        healthy.writeShorts(ShortArray(256) { 2345 }, 256)
        var promoted = false
        var recorded = false

        val result = runCatching {
            DemucsSeparator.closeWriters(listOf(failed, healthy), null)
            commitSeparation(
                songExists = { true },
                promote = { promoted = true; FilePromote.directory(part, dest); dest },
                record = { recorded = true },
            )
        }

        assertSame(failure, result.exceptionOrNull())
        assertTrue(closed)
        assertFalse(promoted)
        assertFalse(recorded)
        assertArrayEquals(byteArrayOf(1, 2, 3), old.readBytes())
        WavReader(healthyFile).use { assertEquals(128L, it.totalFrames) }
    }

    @Test
    fun `헤더 패치 실패도 모든 작성기를 닫은 뒤 전파한다`() {
        val file = temp.newFile("header.wav")
        val patchTarget = temp.newFolder("not-a-file")
        val broken = writer(patchTarget.absolutePath, file.outputStream())
        broken.writeShorts(ShortArray(256), 256)
        val healthyFile = temp.newFile("healthy.wav")
        val healthy = WavWriter.create(healthyFile, 44100)
        healthy.writeShorts(ShortArray(256), 256)

        assertThrows(IOException::class.java) {
            DemucsSeparator.closeWriters(listOf(broken, healthy), null)
        }

        WavReader(file).use { assertEquals(0L, it.totalFrames) }
        WavReader(healthyFile).use { assertEquals(128L, it.totalFrames) }
    }

    @Test
    fun `여러 닫기 실패는 첫 오류에 덧붙이고 마지막 정상 작성기까지 닫는다`() {
        val first = IOException("first close")
        val second = IOException("second close")
        val healthyFile = temp.newFile("healthy.wav")
        val writers = listOf(
            failingWriter(temp.newFile("first.wav"), first),
            failingWriter(temp.newFile("second.wav"), second),
            WavWriter.create(healthyFile, 44100).also { it.writeShorts(ShortArray(256), 256) },
        )

        val failure = assertThrows(IOException::class.java) { DemucsSeparator.closeWriters(writers, null) }

        assertSame(first, failure)
        assertArrayEquals(arrayOf<Throwable>(second), failure.suppressed)
        WavReader(healthyFile).use { assertEquals(128L, it.totalFrames) }
    }

    @Test
    fun `기존 추론 오류와 취소를 닫기 실패가 덮어쓰지 않는다`() {
        for (original in listOf(IOException("inference failed"), CancellationException("cancelled"))) {
            val closeFailure = IOException("close failed")
            val healthyFile = temp.newFile()
            val writers = listOf(
                failingWriter(temp.newFile(), closeFailure),
                WavWriter.create(healthyFile, 44100).also { it.writeShorts(ShortArray(256), 256) },
            )

            val result = runCatching {
                DemucsSeparator.closeWriters(writers, original)
                throw original
            }

            assertSame(original, result.exceptionOrNull())
            assertArrayEquals(arrayOf<Throwable>(closeFailure), original.suppressed)
            WavReader(healthyFile).use { assertEquals(128L, it.totalFrames) }
        }
    }

    private fun failingWriter(file: File, failure: IOException, onClose: () -> Unit = {}): WavWriter {
        val output = file.outputStream()
        return writer(file.absolutePath, object : OutputStream() {
            override fun write(value: Int) = output.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
            override fun close() {
                output.close()
                onClose()
                throw failure
            }
        }).also { it.writeShorts(ShortArray(256) { 1234 }, 256) }
    }

    private fun writer(path: String, output: OutputStream): WavWriter =
        WavWriter::class.java.getDeclaredConstructor(
            String::class.java, OutputStream::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(path, output, 44100, 2)
}
