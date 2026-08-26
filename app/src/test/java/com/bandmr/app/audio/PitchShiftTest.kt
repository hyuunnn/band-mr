package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class PitchShiftTest {

    private fun runShifter(semi: Int, frames: Int = 8000): Pair<FloatArray, FloatArray> {
        val sh = PitchShifter().also { it.semitones = semi }
        val input = FloatArray(frames) {
            sin(2.0 * PI * 220.0 * it / 44100.0).toFloat() * 0.5f
        }
        val outL = FloatArray(frames)
        for (i in 0 until frames) {
            sh.process(input[i], input[i])
            outL[i] = sh.outL
        }
        return input to outL
    }

    @Test
    fun `출력은 유한하고 비어있지 않다`() {
        for (semi in intArrayOf(-12, -5, 0, 5, 12)) {
            val (_, out) = runShifter(semi)
            var rms = 0.0
            for (v in out) {
                assertTrue("semi=$semi NaN", !v.isNaN())
                rms += v * v
            }
            rms = kotlin.math.sqrt(rms / out.size)
            assertTrue("semi=$semi rms=$rms", rms > 0.05)
        }
    }

    @Test
    fun `0반음은 무지연 패스스루`() {
        val (input, out) = runShifter(0)
        for (i in input.indices) {
            assertEquals("i=$i", input[i], out[i], 1e-6f)
        }
    }

    @Test
    fun `리셋이 정상 동작한다`() {
        val sh = PitchShifter()
        repeat(100) { sh.process(1f, 1f) }
        sh.reset()
        sh.process(0f, 0f)
        assertTrue(abs(sh.outL) < 1e-6f)
    }
}
