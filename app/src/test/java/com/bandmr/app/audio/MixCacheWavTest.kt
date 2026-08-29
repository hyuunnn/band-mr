package com.bandmr.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MixCache가 만드는 WAV 캐시 검증.
 *
 * MixCache는 디코딩 결과를 [WavWriter]로 곧바로 쓴다(1패스). 예전에는 raw 파일을 만든 뒤
 * 헤더를 붙여 복사했다(2패스). 두 방식의 출력이 **바이트 단위로 같아야** 하며,
 * 그 등가성을 [RawWavReference]를 비교 대상으로 고정한다.
 */
class MixCacheWavTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `1패스 WavWriter 출력은 2패스 raw 변환과 바이트 동일`() {
        // 프레임 수를 다양하게: 짝수/홀수, 쓰기 청크(16k short) 경계 안팎
        longArrayOf(1, 1000, 8191, 8192, 20_000).forEach { frames ->
            val data = pcm(frames.toInt(), channels = 2)

            val onePass = tmp.newFile("one_$frames.wav")
            WavWriter.create(onePass, SR).use { w ->
                // 디코더가 청크로 흘려보내는 상황을 재현
                var i = 0
                while (i < data.size) {
                    val n = minOf(CHUNK, data.size - i)
                    val part = ShortArray(n)
                    System.arraycopy(data, i, part, 0, n)
                    w.writeShorts(part, n)
                    i += n
                }
            }

            val raw = tmp.newFile("ref_$frames.raw")
            val twoPass = tmp.newFile("two_$frames.wav")
            RawWavReference.writeRaw(raw, data)
            assertEquals(frames, RawWavReference.convert(raw, twoPass, SR))

            assertArrayEquals(
                "frames=$frames: 1패스와 2패스 출력이 다르다",
                twoPass.readBytes(),
                onePass.readBytes(),
            )
        }
    }

    @Test
    fun `모노도 1패스와 2패스가 동일`() {
        val frames = 3000
        val data = pcm(frames, channels = 1)

        val onePass = tmp.newFile("mono_one.wav")
        WavWriter.create(onePass, SR, channels = 1).use { it.writeShorts(data, data.size) }

        val raw = tmp.newFile("mono.raw")
        val twoPass = tmp.newFile("mono_two.wav")
        RawWavReference.writeRaw(raw, data)
        RawWavReference.convert(raw, twoPass, SR, channels = 1)

        assertArrayEquals(twoPass.readBytes(), onePass.readBytes())
    }

    @Test
    fun `WavWriter가 쓴 캐시는 재생기와 같은 WavReader로 읽힌다`() {
        val frames = 1000
        // 좌우가 다른 삼각파: 순서 섞임/바이트 오더 버그를 잡아낸다
        val data = pcm(frames, channels = 2)

        val wav = tmp.newFile("a.wav")
        WavWriter.create(wav, SR).use { it.writeShorts(data, data.size) }

        WavReader(wav).use { r ->
            assertEquals(SR, r.sampleRate)
            assertEquals(2, r.channels)
            assertEquals(frames.toLong(), r.totalFrames)
            val read = ShortArray(frames * 2)
            assertEquals(frames, r.read(0, read, frames))
            assertArrayEquals(data, read)
        }
    }

    @Test
    fun `헤더 크기 필드는 실제 데이터 크기를 가리킨다`() {
        val frames = 100
        val wav = tmp.newFile("h.wav")
        WavWriter.create(wav, SR).use { it.writeShorts(pcm(frames, 2), frames * 2) }

        val dataBytes = frames * 2 * 2L
        val bb = java.nio.ByteBuffer.wrap(wav.readBytes(), 0, 44)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.int // RIFF
        assertEquals((36 + dataBytes).toInt(), bb.int) // 전체 크기
        bb.int // WAVE
        bb.int // fmt
        bb.int // 16
        bb.short; bb.short; bb.int; bb.int; bb.short; bb.short // fmt 본문
        bb.int // data 아이디
        assertEquals(dataBytes.toInt(), bb.int) // data 크기
    }

    private fun pcm(frames: Int, channels: Int): ShortArray =
        ShortArray(frames * channels) { i ->
            val f = i / channels
            if (channels == 1 || i % 2 == 0) ((f % 32767) * 20).toShort()
            else ((f % 32767) * -13).toShort()
        }

    private companion object {
        const val SR = 44100
        const val CHUNK = 16 * 1024 // AudioDecode의 싱크 버퍼와 동일 단위
    }
}
