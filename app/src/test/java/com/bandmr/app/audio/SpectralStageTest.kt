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

    // ---------- 보컬(중앙) 마스킹 ----------

    /** L/R에 각각 주어진 신호를 넣고 보컬 마스킹 후 (입력에너지, 출력에너지)를 반환 */
    private fun vocalEnergyRatio(
        frames: Int,
        left: (Int) -> Float,
        right: (Int) -> Float,
        strength: Float = 1f,
    ): Double {
        val st = SpectralStage(44100)
        st.vocalStrength = strength
        val input = FloatArray(frames * 2)
        for (f in 0 until frames) {
            input[f * 2] = left(f)
            input[f * 2 + 1] = right(f)
        }
        st.feed(input, 0, input.size, muteDrums = false, muteBass = false, muteVocal = true)

        val out = FloatArray(input.size)
        var got = 0
        while (true) {
            val g = st.read(out, got, out.size - got)
            if (g <= 0) break
            got += g
        }
        // OLA 웜업/꼬리 구간 제외한 정상 구간만 비교
        val start = SpectralStage.BLOCK * 4
        val end = minOf(got, input.size) - SpectralStage.BLOCK * 4
        var inE = 0.0
        var outE = 0.0
        for (i in start until end) {
            inE += input[i].toDouble() * input[i]
            outE += out[i].toDouble() * out[i]
        }
        return outE / inE
    }

    private fun sine(hz: Double, amp: Float): (Int) -> Float =
        { f -> (kotlin.math.sin(2.0 * Math.PI * hz * f / 44100.0) * amp).toFloat() }

    @Test
    fun `보컬 마스킹 - 중앙 패닝 성분은 크게 감쇠`() {
        val c = sine(1000.0, 0.5f)
        val ratio = vocalEnergyRatio(32768, c, c) // L=R 완전 중앙
        assertTrue("center energy ratio=$ratio", ratio < 0.02) // -17dB 이상 감쇠
    }

    @Test
    fun `보컬 마스킹 - 편측 패닝 성분은 보존`() {
        val l = sine(1000.0, 0.5f)
        val ratio = vocalEnergyRatio(32768, l, { 0f }) // L에만 존재
        assertTrue("side energy ratio=$ratio", ratio > 0.9)
    }

    @Test
    fun `보컬 마스킹 - 역위상 성분은 보존`() {
        val v = sine(1000.0, 0.5f)
        val ratio = vocalEnergyRatio(32768, v, { f -> -v(f) })
        assertTrue("anti-phase energy ratio=$ratio", ratio > 0.9)
    }

    @Test
    fun `보컬 마스킹 - 강도가 낮으면 감쇠도 약해진다`() {
        val c = sine(1000.0, 0.5f)
        val weak = vocalEnergyRatio(32768, c, c, strength = 0f)
        val strong = vocalEnergyRatio(32768, c, c, strength = 1f)
        // 강도 0은 -12dB급(들리는 수준), 강도 1은 -40dB급(사실상 제거) → 뚜렷한 차이
        assertTrue("weak=$weak strong=$strong", weak > strong * 50)
        assertTrue("weak=$weak", weak in 0.02..0.2)
    }

    @Test
    fun `보컬 마스킹 - 저역 중앙 성분은 보존`() {
        val kick = sine(80.0, 0.5f) // 150Hz 미만 → 보존 대상
        val ratio = vocalEnergyRatio(32768, kick, kick)
        assertTrue("low-freq center energy ratio=$ratio", ratio > 0.7)
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
