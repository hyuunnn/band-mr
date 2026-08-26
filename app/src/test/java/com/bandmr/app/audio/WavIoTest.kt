package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WavIoTest {

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
}
