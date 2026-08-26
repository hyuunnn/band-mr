package com.bandmr.app.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearResamplerTest {

    @Test
    fun `같은 샘플레이트는 사실상 패스스루`() {
        val r = AudioDecode.LinearResampler(44100, 44100)
        val n = 1000
        val l = FloatArray(n) { it / n.toFloat() }
        val r2 = l.copyOf()
        var count = 0L
        var lastA = 0f
        r.process(l, r2, n) { a, b ->
            assertEquals(a, b, 0f) // 좌우 동일 입력
            if (count > 0) assertTrue("단조 증가", a >= lastA - 1e-6f)
            lastA = a
            count++
        }
        count += r.flush { _, _ -> }
        assertEquals("n=$n emitted=$count", n.toLong(), count)
    }

    @Test
    fun `다운샘플 출력 프레임 수가 비율과 일치`() {
        val r = AudioDecode.LinearResampler(48000, 44100)
        // 48k 10초 → 44.1k로 약 441000프레임
        val blockFrames = 48000
        val blocks = 10
        var count = 0L
        repeat(blocks) {
            val l = FloatArray(blockFrames)
            val rr = FloatArray(blockFrames)
            count += r.process(l, rr, blockFrames) { _, _ -> count++ }
        }
        count += r.flush { _, _ -> }
        val expected = 441_000.0
        assertTrue("emitted=$count", kotlin.math.abs(count - expected) < 5)
    }

    @Test
    fun `다운샘플 시 안티에일리어싱 필터 후 DC가 보존된다`() {
        val r = AudioDecode.LinearResampler(48000, 44100)
        val n = 96000 // 2초 — 필터 정착 충분
        var last = 0f to 0f
        val l = FloatArray(4096) { 1f }
        val rr = FloatArray(4096) { 1f }
        repeat(n / 4096) {
            r.process(l, rr, 4096) { a, b -> last = a to b }
        }
        r.flush { a, b -> last = a to b }
        assertTrue("last=${last.first}", last.first > 0.95f && last.second > 0.95f)
    }

    @Test
    fun `인스턴스별로 독립적으로 카운트한다`() {
        val buf = FloatArray(100)
        for (instance in 0 until 2) {
            val r = AudioDecode.LinearResampler(44100, 44100)
            var count = 0L
            count += r.process(buf, buf, 100) { _, _ -> count++ } + r.flush { _, _ -> }
            assertEquals("instance=$instance", 100L, count)
        }
    }
}
