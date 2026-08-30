package com.bandmr.app.separation

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DemucsSeparator.chunkPlan]의 핵심 계약: **기록 길이의 합 == 입력 프레임 수**.
 *
 * 어긋나면 스템이 원본보다 길어지고, AI ON 재생 길이가 AI OFF와 달라진다
 * (StemWavSet이 가장 긴 스템을 곡 길이로 쓴다). 실기기에서 3분35초 곡을 균형형으로
 * 분리했을 때 스템이 1.49초 길어지고 마지막 약 1.1초가 중복 재생됐다.
 */
class DemucsChunkPlanTest {

    private val tiers = Tier.entries.map { it.label to it.segmentSamples }

    private fun written(totalFrames: Long, seg: Int): Long =
        DemucsSeparator.chunkPlan(totalFrames, seg).sumOf { it.writable.toLong() }

    @Test
    fun `기록 길이 합은 입력 프레임과 정확히 같다 - 실기기 사례`() {
        // MixCache WAV 37,945,388B → (37,945,388 - 44) / 4
        val total = 9_486_336L
        for ((label, seg) in tiers) {
            assertEquals("$label seg=$seg", total, written(total, seg))
        }
        // 균형형이 어긋났던 실측값을 그대로 고정한다
        assertEquals(9_551_872L, legacyWritten(total, 262_144))
    }

    /**
     * 수정 전 구현이 초과 기록하던 조건을 그대로 재현해, 지금은 어긋나지 않음을 고정한다.
     * 초과는 길이·세그먼트 조합에 따라 약 1/3의 곡에서 발생했다.
     */
    @Test
    fun `길이를 훑어도 초과 기록이 없다`() {
        for ((label, seg) in tiers) {
            var mismatched = 0
            var legacyMismatched = 0
            for (sec in 5..600) {
                val total = sec.toLong() * PIPELINE_SAMPLE_RATE
                if (written(total, seg) != total) mismatched++
                if (legacyWritten(total, seg) != total) legacyMismatched++
            }
            assertEquals("$label: 초과 기록", 0, mismatched)
            // 수정 전에는 실제로 어긋났다 — 검증이 무의미해지지 않도록 함께 고정한다
            assertTrue("$label: 옛 구현이 멀쩡하면 이 테스트는 의미가 없다", legacyMismatched > 0)
        }
    }

    @Test
    fun `마지막 청크는 남은 만큼만 기록하고 거기서 끝난다`() {
        val seg = 262_144
        val hop = seg - seg / DemucsSeparator.FADE_DIVISOR
        // 남은 길이가 hop보다 커서(=fade 구간이 남아) 옛 구현이 한 바퀴 더 돌던 형태
        val total = 4L * hop + (hop + 1)
        val plan = DemucsSeparator.chunkPlan(total, seg)

        assertTrue("마지막 청크만 isLast", plan.dropLast(1).none { it.isLast })
        assertTrue(plan.last().isLast)
        assertEquals("계획은 마지막 청크에서 끝난다", total, plan.last().pos + plan.last().len)
        assertEquals(total, plan.sumOf { it.writable.toLong() })
        plan.dropLast(1).forEach { assertEquals("중간 청크는 hop만 기록", hop, it.writable) }
    }

    @Test
    fun `한 청크로 끝나는 짧은 곡`() {
        val seg = 131_072
        for (total in longArrayOf(1, 1000, seg - 1L, seg.toLong())) {
            val plan = DemucsSeparator.chunkPlan(total, seg)
            assertEquals("total=$total 청크 1개", 1, plan.size)
            assertEquals("total=$total", total, plan.single().writable.toLong())
            assertTrue(plan.single().isLast)
        }
    }

    @Test
    fun `읽기 시작 위치는 hop 간격이고 끝을 넘지 않는다`() {
        for ((label, seg) in tiers) {
            val hop = seg - seg / DemucsSeparator.FADE_DIVISOR
            val total = 200L * PIPELINE_SAMPLE_RATE
            DemucsSeparator.chunkPlan(total, seg).forEachIndexed { i, c ->
                assertEquals("$label 청크 $i 시작", i.toLong() * hop, c.pos)
                assertTrue("$label 청크 $i 는 끝을 넘지 않는다", c.pos + c.len <= total)
                assertTrue("$label 청크 $i len<=seg", c.len <= seg)
            }
        }
    }

    /** 수정 전 루프의 길이 계산(비교 기준) */
    private fun legacyWritten(totalFrames: Long, seg: Int): Long {
        val fade = seg / DemucsSeparator.FADE_DIVISOR
        val hop = seg - fade
        var written = 0L
        var pos = 0L
        var chunkIdx = 0
        while (pos < totalFrames) {
            val len = minOf(seg.toLong(), totalFrames - pos).toInt()
            val isLast = pos + len >= totalFrames
            written += when {
                !isLast -> hop
                chunkIdx > 0 -> maxOf(len, fade)
                else -> len
            }
            pos += hop
            chunkIdx++
        }
        return written
    }
}
