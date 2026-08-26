package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

class FftTest {

    @Test
    fun `임펄스 입력은 모든 빈에서 크기 1`() {
        val n = 64
        val fft = Fft(n)
        val re = FloatArray(n).also { it[0] = 1f }
        val im = FloatArray(n)
        fft.run(re, im, inverse = false)
        for (i in 0 until n) {
            assertEquals(1f, re[i], 1e-4f)
            assertEquals(0f, im[i], 1e-4f)
        }
    }

    @Test
    fun `정현파는 해당 빈과 대칭 빈에만 에너지`() {
        val n = 128
        val k = 10
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2.0 * PI * k * it / n).toFloat() }
        val im = FloatArray(n)
        fft.run(re, im, inverse = false)

        fun mag(i: Int) = kotlin.math.hypot(re[i].toDouble(), im[i].toDouble())
        assertEquals(n / 2.0, mag(k), 1e-2)
        assertEquals(n / 2.0, mag(n - k), 1e-2)
        for (i in 0 until n) {
            if (i != k && i != n - k) assertTrue("bin $i", mag(i) < 1e-2)
        }
    }

    @Test
    fun `역변환 라운드트립은 원 신호 복원`() {
        val n = 256
        val fft = Fft(n)
        val rnd = Random(42)
        val origRe = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val origIm = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val re = origRe.copyOf()
        val im = origIm.copyOf()
        fft.run(re, im, inverse = false)
        fft.run(re, im, inverse = true)
        for (i in 0 until n) {
            assertTrue(abs(re[i] - origRe[i]) < 1e-3f)
            assertTrue(abs(im[i] - origIm[i]) < 1e-3f)
        }
    }
}
