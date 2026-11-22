package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DspChainChunkTest {
    @Test
    fun `모든 제거 마스크의 전체 PCM은 청크 크기와 무관하다`() {
        for (channels in 1..2) {
            val input = signal(6001, channels)
            for (mask in 1..15) {
                val expected = render(input, channels, mask, intArrayOf(8192))
                for (chunks in listOf(intArrayOf(2048), intArrayOf(1, 137, 511, 512, 700, 1024))) {
                    assertArrayEquals("ch=$channels mask=$mask chunks=${chunks.toList()}",
                        expected, render(input, channels, mask, chunks))
                }
            }
        }
    }

    @Test
    fun `한 블록보다 짧은 입력도 지연과 잔여 출력을 잃지 않는다`() {
        for (channels in 1..2) {
            for (frames in listOf(1, 511, 512, 513, 1023, 1024, 1025)) {
                val input = signal(frames, channels)
                assertArrayEquals("ch=$channels frames=$frames",
                    render(input, channels, Stem.DRUMS.bit, intArrayOf(8192)),
                    render(input, channels, Stem.DRUMS.bit, intArrayOf(1)))
            }
        }
    }

    @Test
    fun `스펙트럼 지연은 시작에만 있고 첫 청크 끝에 무음이 끼지 않는다`() {
        val frames = 16384
        val input = ShortArray(frames * 2) { if (it % 2 == 0) 10000 else -10000 }
        val output = render(input, 2, Stem.VOCAL.bit, intArrayOf(2048))
        assertTrue(output.take(SpectralStage.BLOCK * 2).all { it == 0.toShort() })
        for (frame in SpectralStage.BLOCK + SpectralStage.HOP until frames) {
            assertTrue("frame=$frame", output[frame * 2] > 9000)
        }
    }

    @Test
    fun `수정 전 연속 DSP 구간의 PCM을 보존한다`() {
        // 수정 전 한 청크 출력에서 청크 끝의 무음과 미방출분을 제외한 PCM의 SHA-256.
        // 처리 알고리즘은 보존하고, 스펙트럼 경로의 출력 위치만 고정 지연 뒤로 옮겨야 한다.
        for (channels in 1..2) {
            val input = signal(6000, channels)
            val digest = MessageDigest.getInstance("SHA-256")
            for (mask in 1..15) {
                val chain = DspChain(PIPELINE_SAMPLE_RATE, channels).also { it.muteMask = mask }
                val output = input.copyOf()
                chain.processInPlace(output, output.size)
                val spectral = mask and (Stem.DRUMS.bit or Stem.BASS.bit) != 0 ||
                    (channels == 2 && mask and Stem.VOCAL.bit != 0)
                val offset = if (spectral) SpectralStage.BLOCK * channels else 0
                val count = (6000 - SpectralStage.BLOCK) * channels
                for (i in offset until offset + count) {
                    digest.update(output[i].toByte())
                    digest.update((output[i].toInt() shr 8).toByte())
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val expected = if (channels == 1) {
                "c5d28b5d027e96e31487c9a0543bc3829c64f10dcc1b38856aad8c0e074af9c3"
            } else {
                "afce87bbeb220e010847cdfb3346eb81a6a3cefab8062cc3029c81c471e5d1ba"
            }
            assertEquals("ch=$channels", expected, actual)
        }
    }

    private fun render(input: ShortArray, channels: Int, mask: Int, chunks: IntArray): ShortArray {
        val chain = DspChain(PIPELINE_SAMPLE_RATE, channels).also { it.muteMask = mask }
        val result = ArrayList<Short>()
        var pos = 0
        var chunk = 0
        while (pos < input.size) {
            val n = minOf(chunks[chunk++ % chunks.size] * channels, input.size - pos)
            val part = input.copyOfRange(pos, pos + n)
            chain.processInPlace(part, n)
            result.addAll(part.toList())
            pos += n
        }
        chain.drain { samples, count -> for (i in 0 until count) result.add(samples[i]) }
        return result.toShortArray()
    }

    private fun signal(frames: Int, channels: Int) =
        ShortArray(frames * channels) { ((it * 7919L % 24001) - 12000).toShort() }
}
