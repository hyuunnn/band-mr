package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceWavPlayerTest {
    @get:Rule val tmp = TemporaryFolder()

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

    private fun render(player: SourceWavPlayer, position: Long): ShortArray {
        val frames = SourceWavPlayer::class.java.getDeclaredMethod("renderChunk", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(player, position, 2048) as Int
        val out = AudioTrackEngine::class.java.getDeclaredField("outShort").apply { isAccessible = true }.get(player) as ShortArray
        return out.copyOf(frames * 2)
    }
}
