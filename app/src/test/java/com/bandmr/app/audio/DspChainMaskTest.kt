package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DspChainMaskTest {
    private val aiMasks = intArrayOf(Stem.PIANO.bit, Stem.OTHER.bit, Stem.PIANO.bit or Stem.OTHER.bit)

    @Test
    fun `AI 전용 스템만 제거하면 PCM을 바꾸거나 무음을 덧붙이지 않는다`() {
        for (channels in 1..2) {
            for (mask in aiMasks) {
                val input = signal(channels)
                val output = input.copyOf()
                val chain = DspChain(PIPELINE_SAMPLE_RATE, channels).also { it.muteMask = mask }
                chain.processInPlace(output, output.size)
                assertArrayEquals(input, output)
                var tail = 0
                chain.drain { _, n -> tail += n }
                assertEquals(0, tail)
                assertEquals("AI 설정은 보존한다", mask, chain.muteMask)
            }
        }
    }

    @Test
    fun `지원하는 DSP 효과는 수정 전 PCM과 잔여 출력까지 바이트 단위로 같다`() {
        // 5d4e522의 DspChain으로 모노·스테레오 각각 15가지 지원 마스크를 렌더한 SHA-256.
        assertEquals("d39bb9e0ab751427d8d3990726cb8eddfc512d342d25e6b821997102ed23ace0", digest(1))
        assertEquals("8e2fc15413bf108ab743cad78968eb8770799aab362414ae4e6ae5c97020e2b7", digest(2))
    }

    @Test
    fun `AI 전용 비트가 섞여도 지원하는 DSP 효과의 출력은 같다`() {
        for (channels in 1..2) {
            val expected = digest(channels)
            for (mask in aiMasks) assertEquals(expected, digest(channels, mask))
        }
    }

    private fun signal(channels: Int) =
        ShortArray(6000 * channels) { ((it * 7919L % 60001) - 30000).toShort() }

    private fun digest(channels: Int, extraMask: Int = 0): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun append(samples: ShortArray, count: Int) {
            val bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                bytes[i * 2] = samples[i].toByte()
                bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
            }
            hash.update(bytes)
        }
        val sizes = intArrayOf(512, 1400, 2048, 700)
        val input = signal(channels)
        for (mask in 1..15) {
            val chain = DspChain(PIPELINE_SAMPLE_RATE, channels).also { it.muteMask = mask or extraMask }
            var offset = 0
            var chunk = 0
            while (offset < input.size) {
                val end = minOf(input.size, offset + sizes[chunk++ % sizes.size])
                val part = input.copyOfRange(offset, end)
                chain.processInPlace(part, part.size)
                append(part, part.size)
                offset = end
            }
            chain.drain(::append)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
