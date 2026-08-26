package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random
import org.junit.Test

class SpectralStageTest {

    private fun stereoFrames(n: Int, rnd: Random): FloatArray =
        FloatArray(n * 2) { rnd.nextFloat() * 2f - 1f }

    @Test
    fun `뮤트 없을 때는 완전 패스스루`() {
        val st = SpectralStage(44100)
        val rnd = Random(7)
        val input = stereoFrames(4096, rnd)
        st.feed(input, 0, input.size, muteDrums = false, muteBass = false)
        val out = FloatArray(input.size)
        val got = st.read(out, 0, out.size)
        assertEquals(input.size, got)
        for (i in input.indices) assertEquals(input[i], out[i], 0f)
    }

    @Test
    fun `FIFO 읽기가 블록 경계 없이 동작`() {
        val st = SpectralStage(44100)
        val rnd = Random(3)
        val input = stereoFrames(8192, rnd)
        st.feed(input, 0, input.size, muteDrums = true, muteBass = true)

        // 블록 지연만큼 미방출분이 남고, 나머지는 전부 읽힌다
        var total = 0
        val buf = FloatArray(333) // 블록 크기와 안 맞는 크기로 읽기
        while (true) {
            val got = st.read(buf, 0, buf.size)
            if (got <= 0) break
            total += got
        }
        assertTrue(
            "total=$total expected≈${input.size}",
            total in (input.size - SpectralStage.BLOCK * 2)..input.size,
        )
    }

    @Test
    fun `드럼 억제 후에도 출력 에너지는 유한`() {
        val st = SpectralStage(44100)
        val rnd = Random(11)
        val input = stereoFrames(16384, rnd)
        st.feed(input, 0, input.size, muteDrums = true, muteBass = false)

        var energy = 0.0
        val buf = FloatArray(1024)
        while (true) {
            val got = st.read(buf, 0, buf.size)
            if (got <= 0) break
            for (i in 0 until got) {
                assertTrue(!buf[i].isNaN())
                energy += buf[i].toDouble() * buf[i]
            }
        }
        assertTrue(energy > 0.0)
    }

    @Test
    fun `STFT 경로는 원 신호를 재구성한다`() {
        val st = SpectralStage(44100)
        val frames = 16384
        val input = FloatArray(frames * 2) {
            (kotlin.math.sin(2.0 * Math.PI * 1000.0 * (it / 2) / 44100.0) * 0.5f).toFloat()
        }
        // 드럼 억제만 켬(1kHz 순음은 화성 성분이므로 마스크가 거의 1로 통과)
        st.feed(input, 0, input.size, muteDrums = true, muteBass = false)

        val out = FloatArray(input.size)
        var got = 0
        while (true) {
            val g = st.read(out, got, out.size - got)
            if (g <= 0) break
            got += g
        }
        assertTrue("got=$got", got >= input.size - SpectralStage.BLOCK * 2)

        var err = 0.0
        var ref = 0.0
        val start = SpectralStage.BLOCK * 2
        val end = minOf(got, input.size) - SpectralStage.BLOCK * 2
        for (i in start until end) {
            err += (out[i] - input[i]).let { it * it }
            ref += input[i] * input[i]
        }
        val rel = kotlin.math.sqrt(err / ref)
        assertTrue("relative recon error=$rel", rel < 0.2)
    }

    @Test
    fun `리셋 후 FIFO가 비운다`() {
        val st = SpectralStage(44100)
        val input = stereoFrames(2048, Random(5))
        st.feed(input, 0, input.size, muteDrums = false, muteBass = false)
        st.read(FloatArray(16), 0, 16)
        st.reset()
        assertEquals(0, st.read(FloatArray(64), 0, 64))
    }
}
