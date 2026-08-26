package com.bandmr.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MixCache가 만드는 WAV 캐시 검증: raw PCM에 헤더를 붙인 뒤
 * 실제 재생기와 동일한 [WavReader]로 파싱/읽기가 정상인지 확인한다.
 */
class MixCacheWavTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `raw를 WAV로 감싸면 WavReader로 읽힌다`() {
        val sampleRate = 44100
        val frames = 1000
        val raw = tmp.newFile("a.raw")
        // 좌우가 다른 삼각파: 순서 섞임/바이트 오더 버그를 잡아낸다
        val data = ShortArray(frames * 2) { i ->
            val f = i / 2
            if (i % 2 == 0) ((f % 32767) * 20).toShort() else ((f % 32767) * -13).toShort()
        }
        java.nio.ByteBuffer.allocate(data.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            for (v in data) putShort(v)
        }.also { bb -> raw.writeBytes(bb.array()) }

        val wav = tmp.newFile("a.wav")
        val outFrames = RawToWav.convert(raw, wav, sampleRate)

        assertEquals(frames.toLong(), outFrames)
        WavReader(wav).use { r ->
            assertEquals(sampleRate, r.sampleRate)
            assertEquals(2, r.channels)
            assertEquals(frames.toLong(), r.totalFrames)
            val read = ShortArray(frames * 2)
            assertEquals(frames, r.read(0, read, frames))
            assertArrayEquals(data, read)
        }
    }

    @Test
    fun `헤더 크기 필드는 실제 데이터 크기를 가리킨다`() {
        val dataBytes = 400L
        val h = RawToWav.header(44100, 2, dataBytes)
        val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.int // RIFF
        assertEquals((36 + dataBytes).toInt(), bb.int) // 전체 크기
        bb.int // WAVE
        bb.int // fmt
        bb.int // 16
        bb.short; bb.short; bb.int; bb.int; bb.short; bb.short // fmt 본문
        bb.int // data 아이디
        assertEquals(dataBytes.toInt(), bb.int) // data 크기
    }
}
