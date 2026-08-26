package com.bandmr.app.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemucsChunkMathTest {

    private val seg = 1024
    private val fade = seg / DemucsSeparator.FADE_DIVISOR
    private val hop = seg - fade

    @Test
    fun `첫 구간은 램프인 없이 1로 시작`() {
        for (j in 0 until fade) {
            assertEquals(1f, DemucsSeparator.weightAt(j, seg, isFirst = true, isLast = false, fade), 1e-6f)
        }
    }

    @Test
    fun `마지막 구간은 램프아웃 없이 1로 끝난다`() {
        for (j in maxOf(0, seg - fade) until seg) {
            assertEquals(1f, DemucsSeparator.weightAt(j, seg, isFirst = false, isLast = true, fade), 1e-6f)
        }
    }

    @Test
    fun `중간 구간은 양끝 램프`() {
        assertEquals(0f, DemucsSeparator.weightAt(0, seg, false, false, fade), 1e-6f)
        assertEquals(fade.toFloat() / fade, DemucsSeparator.weightAt(fade, seg, false, false, fade), 1e-6f)
        val dEnd0 = DemucsSeparator.weightAt(seg - 1, seg, false, false, fade)
        assertEquals(0f, dEnd0, 1e-6f)
    }

    @Test
    fun `인접 구간 가중치 합이 1에 수렴`() {
        // 이전 구간 캐리 영역 j∈[hop,seg) ↔ 다음 구간 머리 j'=j-hop∈[0,fade)
        for (jp in 0 until fade) {
            val j = jp + hop
            val wPrev = DemucsSeparator.weightAt(j, seg, isFirst = false, isLast = false, fade)
            val wNext = DemucsSeparator.weightAt(jp, seg, isFirst = false, isLast = false, fade)
            val sum = wPrev + wNext
            assertTrue("jp=$jp sum=$sum", sum in 0.98f..1.02f)
        }
    }

    @Test
    fun `단일 구간(첫+마지막)은 전부 가중치 1`() {
        val len = 700
        for (j in 0 until len) {
            assertEquals(
                1f,
                DemucsSeparator.weightAt(j, len, isFirst = true, isLast = true, fade),
                1e-6f,
            )
        }
    }

    @Test
    fun `fade 0이면 항상 1`() {
        for (j in 0 until 16) {
            assertEquals(1f, DemucsSeparator.weightAt(j, 16, false, false, 0), 1e-6f)
        }
    }
}
