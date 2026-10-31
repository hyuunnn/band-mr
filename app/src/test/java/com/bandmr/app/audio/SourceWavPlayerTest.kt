package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceWavPlayerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `피아노와 그 외만 제거해도 기본 원본 재생 출력은 바뀌지 않는다`() {
        val file = tmp.newFile("source.wav")
        val samples = ShortArray(2048 * 2) { ((it * 7919L % 60001) - 30000).toShort() }
        WavWriter.create(file, PIPELINE_SAMPLE_RATE).use { it.writeShorts(samples, samples.size) }
        val player = SourceWavPlayer(file)
        val reference = SourceWavPlayer(file)
        try {
            player.muteMask = Stem.PIANO.bit or Stem.OTHER.bit
            assertArrayEquals(render(reference, 0), render(player, 0))
        } finally {
            player.release()
            reference.release()
        }
    }

    @Test
    fun `같은 마스크 재적용은 다음 청크의 DSP 출력을 바꾸지 않는다`() {
        val file = tmp.newFile("source.wav")
        val samples = ShortArray(8192 * 2) { ((it * 7919L % 24001) - 12000).toShort() }
        WavWriter.create(file, PIPELINE_SAMPLE_RATE).use { it.writeShorts(samples, samples.size) }
        val reference = SourceWavPlayer(file)
        val reapplied = SourceWavPlayer(file)
        try {
            reference.muteMask = Stem.DRUMS.bit
            reapplied.muteMask = Stem.DRUMS.bit
            assertArrayEquals(render(reference, 0), render(reapplied, 0))
            reapplied.muteMask = Stem.DRUMS.bit
            assertArrayEquals(render(reference, 2048), render(reapplied, 2048))
        } finally {
            reference.release()
            reapplied.release()
        }
    }

    @Test
    fun `마스크가 바뀌면 새 체인의 출력으로 시작한다`() {
        val file = tmp.newFile("source.wav")
        val samples = ShortArray(8192 * 2) { ((it * 7919L % 24001) - 12000).toShort() }
        WavWriter.create(file, PIPELINE_SAMPLE_RATE).use { it.writeShorts(samples, samples.size) }
        val reused = SourceWavPlayer(file)
        val fresh = SourceWavPlayer(file)
        try {
            reused.muteMask = Stem.DRUMS.bit
            render(reused, 0)
            reused.muteMask = Stem.BASS.bit
            fresh.muteMask = Stem.BASS.bit
            assertArrayEquals(render(fresh, 2048), render(reused, 2048))
        } finally {
            reused.release()
            fresh.release()
        }
    }

    @Test
    fun `종료 훅을 옮겨도 렌더와 DSP 잔여 출력은 기존 파이프라인과 바이트 동일하다`() {
        val file = tmp.newFile("tail.wav")
        val samples = ShortArray(8192 * 2) { ((it * 7919L % 24001) - 12000).toShort() }
        WavWriter.create(file, PIPELINE_SAMPLE_RATE).use { it.writeShorts(samples, samples.size) }
        for (mask in listOf(Stem.VOCAL.bit, Stem.DRUMS.bit, Stem.BASS.bit, Stem.GUITAR.bit)) {
            val player = SourceWavPlayer(file).also { it.muteMask = mask }
            try {
                val chain = DspChain(PIPELINE_SAMPLE_RATE, 2).also { it.muteMask = mask }
                val pitch = PitchShifter()
                for (pos in 0 until 8192 step 2048) {
                    val input = samples.copyOfRange(pos * 2, (pos + 2048) * 2)
                    val expected = ShortArray(input.size)
                    pitch.renderTo(input, 2048, expected)
                    chain.processInPlace(expected, expected.size)
                    assertArrayEquals(expected, render(player, pos.toLong()))
                }
                val expectedTail = mutableListOf<Short>()
                chain.drain { data, count -> for (i in 0 until count) expectedTail += data[i] }
                val actualTail = mutableListOf<Short>()
                val write: (ShortArray, Int) -> Unit = { data, count ->
                    for (i in 0 until count) actualTail += data[i]
                }
                SourceWavPlayer::class.java.getDeclaredMethod("drainProcessors", kotlin.jvm.functions.Function2::class.java)
                    .apply { isAccessible = true }.invoke(player, write)
                assertTrue(expectedTail.isNotEmpty())
                assertArrayEquals(expectedTail.toShortArray(), actualTail.toShortArray())
            } finally {
                player.release()
            }
        }
    }

    private fun render(player: SourceWavPlayer, position: Long): ShortArray {
        val frames = SourceWavPlayer::class.java.getDeclaredMethod("renderChunk", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(player, position, 2048) as Int
        val out = AudioTrackEngine::class.java.getDeclaredField("outShort").apply { isAccessible = true }.get(player) as ShortArray
        return out.copyOf(frames * 2)
    }
}
