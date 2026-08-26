package com.bandmr.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {

    private fun steadyState(bq: Biquad, input: Float, samples: Int = 8000): Float {
        var y = 0f
        repeat(samples) { y = bq.process(input) }
        return y
    }

    @Test
    fun `로우패스는 DC를 통과시킨다`() {
        val bq = Biquad().apply { setLowPass(44100f, 1000f, 0.707f) }
        assertTrue(steadyState(bq, 1f) > 0.99f)
    }

    @Test
    fun `하이패스는 DC를 차단한다`() {
        val bq = Biquad().apply { setHighPass(44100f, 100f, 0.707f) }
        assertTrue(kotlin.math.abs(steadyState(bq, 1f)) < 0.01f)
    }

    @Test
    fun `하이패스는 고역을 통과시킨다`() {
        // 나이퀴스트 신호 (+1,-1 교대)는 모든 바이쿼드에서 이득 1
        val hp = Biquad().apply { setHighPass(44100f, 100f, 0.707f) }
        var y = 0f
        var x = 1f
        repeat(4000) { y = hp.process(x); x = -x }
        assertTrue(kotlin.math.abs(y) > 0.95f)

        val lp = Biquad().apply { setLowPass(44100f, 1000f, 0.707f) }
        x = 1f
        repeat(4000) { y = lp.process(x); x = -x }
        assertTrue(kotlin.math.abs(y) < 0.05f)
    }

    @Test
    fun `리셋 후 상태가 초기화된다`() {
        val bq = Biquad().apply { setHighPass(44100f, 100f, 0.707f) }
        repeat(100) { bq.process(1f) }
        bq.reset()
        assertTrue(bq.process(0f) == 0f)
    }
}
