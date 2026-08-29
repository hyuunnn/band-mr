package com.bandmr.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.PI
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
    fun `리셋한 시프터 출력은 새 인스턴스와 동일`() {
        // 시크마다 PitchShifter를 새로 만들지 않고 reset()으로 재사용하기 위한 계약.
        // 0반음은 패스스루라 리셋 여부와 무관하므로 반드시 비-0반음으로 검증한다.
        for (semi in intArrayOf(-5, 3, 12)) {
            val frames = 4000
            val warm = FloatArray(frames) { sin(2.0 * PI * 330.0 * it / 44100.0).toFloat() * 0.7f }
            val test = FloatArray(frames) { sin(2.0 * PI * 220.0 * it / 44100.0).toFloat() * 0.5f }

            val fresh = PitchShifter().also { it.semitones = semi }
            val expected = FloatArray(frames) { i ->
                fresh.process(test[i], test[i]); fresh.outL
            }

            val reused = PitchShifter().also { it.semitones = semi }
            for (v in warm) reused.process(v, -v)
            reused.reset()
            val actual = FloatArray(frames) { i ->
                reused.process(test[i], test[i]); reused.outL
            }

            assertArrayEquals("semi=$semi", expected, actual, 0f)

            // 리셋을 생략하면 실제로 달라진다(무의미화 방지)
            val dirty = PitchShifter().also { it.semitones = semi }
            for (v in warm) dirty.process(v, -v)
            val without = FloatArray(frames) { i ->
                dirty.process(test[i], test[i]); dirty.outL
            }
            assertFalse("semi=$semi: 리셋 없이도 같으면 검증이 무의미", without.contentEquals(expected))
        }
    }
}
