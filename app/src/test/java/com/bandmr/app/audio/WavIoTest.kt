package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

class WavIoTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `헤더에 길이를 쓰지 못하면 close가 실패를 전파한다`() {
        val file = temp.newFile("header.wav")
        val patchTarget = temp.newFolder("not-a-file")
        val writer = writer(patchTarget.absolutePath, file.outputStream())
        writer.writeShorts(ShortArray(256) { 1234 }, 256)

        assertThrows(IOException::class.java) { writer.close() }

        assertEquals(44L + 256 * 2, file.length())
        WavReader(file).use { assertEquals(0L, it.totalFrames) }
    }

    @Test
    fun `마지막 버퍼 출력 실패를 전파하고 출력 스트림도 닫는다`() {
        val file = temp.newFile("flush.wav")
        val output = file.outputStream()
        var remaining = 128
        var closed = false
        val writer = writer(file.absolutePath, object : OutputStream() {
            override fun write(value: Int) {
                if (remaining == 0) throw IOException("disk full")
                output.write(value)
                remaining--
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val accepted = minOf(length, remaining)
                output.write(bytes, offset, accepted)
                remaining -= accepted
                if (accepted < length) throw IOException("disk full")
            }
            override fun close() {
                closed = true
                output.close()
            }
        })
        writer.writeShorts(ShortArray(256) { 1234 }, 256)
        assertEquals("짧은 PCM은 close 전까지 버퍼에 남는다", 0L, file.length())

        val failure = assertThrows(IOException::class.java) { writer.close() }

        assertEquals("disk full", failure.message)
        assertTrue(closed)
        assertEquals(128L, file.length())
        WavReader(file).use { assertEquals(0L, it.totalFrames) }
    }

    @Test
    fun `정상 WAV는 수정 전 b56d773의 모노와 스테레오 바이트와 같다`() {
        val expected = mapOf(
            1 to "c04ccfaea4cd0f3dc7ed3a454dc48c1f4108f00b5b1db170850ee25e7d684a2c",
            2 to "e6af143685a46f95451c5045ba64aa228637332534c7163d6a97e1f16e07cbcf",
        )
        for ((channels, digest) in expected) {
            val file = temp.newFile("normal-$channels.wav")
            WavWriter.create(file, 44100, channels).use { writer ->
                for (frames in listOf(1, 137, 2048, 4096, 20000)) {
                    val input = ShortArray(frames * channels) {
                        ((it * 7919L % 60001) - 30000).toShort()
                    }
                    writer.writeShorts(input, input.size)
                }
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("channels=$channels", digest, actual)
        }
    }

    @Test
    fun `본문이 잘리면 이전 청크를 반환하지 않고 실제 프레임만 읽는다`() {
        val file = File.createTempFile("truncated", ".wav")
        try {
            val pcm = shortArrayOf(100, 101, 200, 201, 300, 301, 400, 401)
            WavWriter.create(file, 44100).use { it.writeShorts(pcm, pcm.size) }
            RandomAccessFile(file, "rw").use { it.setLength(44L + 3 * 4) }
            WavReader(file).use { reader ->
                val buffer = ShortArray(4)
                assertEquals(2, reader.read(0, buffer, 2))
                assertEquals(1, reader.read(2, buffer, 2))
                assertArrayEquals(shortArrayOf(300, 301), buffer.copyOf(2))
                assertEquals(0, reader.read(3, buffer, 1))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `잘린 마지막 프레임의 일부 바이트는 반환하지 않는다`() {
        val file = File.createTempFile("partial-frame", ".wav")
        try {
            val pcm = shortArrayOf(100, 101, 200, 201)
            WavWriter.create(file, 44100).use { it.writeShorts(pcm, pcm.size) }
            RandomAccessFile(file, "rw").use { it.setLength(44L + 4 + 3) }
            WavReader(file).use { reader ->
                val buffer = ShortArray(4) { -1 }
                assertEquals(1, reader.read(0, buffer, 2))
                assertArrayEquals(shortArrayOf(100, 101, -1, -1), buffer)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `WAV 쓰기-읽기 라운드트립`() {
        val f = File.createTempFile("wavtest", ".wav")
        val frames = 1000
        val data = ShortArray(frames * 2) { (it % 32767).toShort() }

        WavWriter.create(f, 44100).use { w -> w.writeShorts(data, data.size) }

        WavReader(f).use { r ->
            assertEquals(44100, r.sampleRate)
            assertEquals(2, r.channels)
            assertEquals(frames.toLong(), r.totalFrames)

            val out = ShortArray(frames * 2)
            val got = r.read(0, out, frames)
            assertEquals(frames, got)
            for (i in data.indices) assertEquals(data[i], out[i])

            // 중간 임의 위치 읽기
            val mid = ShortArray(20)
            assertEquals(10, r.read(500, mid, 10))
            for (i in 0 until 20) assertEquals(data[1000 + i], mid[i])
        }
        assertEquals(44L + data.size * 2L, f.length())
        f.delete()
    }

    @Test
    fun `여러 번 나눠 써도 헤더 크기가 갱신된다`() {
        val f = File.createTempFile("wavtest2", ".wav")
        val writer = WavWriter.create(f, 44100)
        val chunk = ShortArray(2000)
        repeat(5) { writer.writeShorts(chunk, chunk.size) }
        writer.close()
        assertEquals(44L + chunk.size * 2L * 5, f.length())

        WavReader(f).use { r -> assertEquals(5000L, r.totalFrames) }
        f.delete()
    }

    @Test
    fun `EOF에서 남은 프레임 수만큼만 읽는다`() {
        val f = File.createTempFile("wavtest3", ".wav")
        val total = 50
        WavWriter.create(f, 22050).use { w ->
            w.writeShorts(ShortArray(total * 2), total * 2)
        }
        WavReader(f).use { r ->
            val buf = ShortArray(total * 4)
            // 40프레임부터 30프레임 요청 → 끝까지 남은 10프레임만 반환
            assertEquals(10, r.read(40, buf, 30))
        }
        f.delete()
    }

    private fun writer(path: String, output: OutputStream): WavWriter =
        WavWriter::class.java.getDeclaredConstructor(
            String::class.java, OutputStream::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(path, output, 44100, 2)
}
