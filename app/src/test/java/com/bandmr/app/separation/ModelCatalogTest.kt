package com.bandmr.app.separation

import com.bandmr.app.data.Stem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `경량과 구 id는 6스템 균형형으로 읽는다`() {
        assertEquals(Tier.S6_BALANCED, Tier.fromId(null))
        assertEquals(Tier.S6_BALANCED, Tier.fromId(""))
        assertEquals(Tier.S6_BALANCED, Tier.fromId("light"))
        assertEquals(Tier.S6_BALANCED, Tier.fromId("balanced"))
        assertEquals(Tier.S6_QUALITY, Tier.fromId("quality"))
        assertEquals(Tier.S4_BALANCED, Tier.fromId("4s-balanced"))
        assertEquals(Tier.S4_QUALITY, Tier.fromId("4s-quality"))
    }

    @Test
    fun `카탈로그는 4_6스템과 균형_품질만 갖는다`() {
        assertEquals(4, Tier.entries.size)
        assertEquals(setOf(StemLayout.FOUR, StemLayout.SIX), Tier.entries.map { it.layout }.toSet())
        assertEquals(setOf(Quality.BALANCED, Quality.QUALITY), Tier.entries.map { it.quality }.toSet())
        assertFalse(Tier.entries.any { it.id == "light" || it.segmentSamples == 131_072 })
        assertEquals(Tier.S4_BALANCED, Tier.of(StemLayout.FOUR, Quality.BALANCED))
        assertEquals(Tier.S6_QUALITY, Tier.of(StemLayout.SIX, Quality.QUALITY))
    }

    @Test
    fun `스템 순서는 export 로그와 같다`() {
        assertEquals(listOf("drums", "bass", "other", "vocals"), StemLayout.FOUR.stemOrder)
        assertEquals(
            listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
            StemLayout.SIX.stemOrder,
        )
        assertEquals(
            listOf(Stem.DRUMS, Stem.BASS, Stem.OTHER, Stem.VOCAL),
            StemLayout.FOUR.stems,
        )
        assertTrue(StemLayout.SIX.stems.containsAll(listOf(Stem.GUITAR, Stem.PIANO)))
        assertEquals(StemLayout.FOUR.stemOrder, Tier.S4_BALANCED.stemOrder)
        assertEquals(StemLayout.SIX.stemOrder, Tier.S6_QUALITY.stemOrder)
    }

    @Test
    fun `화면 목록은 AI OFF와 같은 Stem 순서다`() {
        assertEquals(
            listOf(Stem.VOCAL, Stem.DRUMS, Stem.BASS, Stem.OTHER),
            StemLayout.FOUR.displayStems,
        )
        assertEquals(Stem.entries, StemLayout.SIX.displayStems)
        assertEquals(StemLayout.FOUR.displayStems, Tier.S4_QUALITY.displayStems)
        assertEquals(Stem.entries, Tier.S6_QUALITY.displayStems)
    }

    @Test
    fun `6스템 온디스크 파일명은 기존 경로를 유지한다`() {
        assertEquals("model-6s.onnx", Tier.S6_BALANCED.fileName)
        assertEquals("model-6s.onnx", Tier.S6_QUALITY.fileName)
        assertEquals("balanced", Tier.S6_BALANCED.id)
        assertEquals("quality", Tier.S6_QUALITY.id)
        assertEquals("model-4s.onnx", Tier.S4_BALANCED.fileName)
    }

    @Test
    fun `SHA-256 핀은 64자 hex다`() {
        val hex = Regex("[0-9a-f]{64}")
        Tier.entries.forEach { tier ->
            assertTrue("${tier.id}: ${tier.sha256}", hex.matches(tier.sha256))
        }
    }
}
