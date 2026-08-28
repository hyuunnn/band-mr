package com.bandmr.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StemGainsTest {

    @Test
    fun `기본 패킹은 전부 100이다`() {
        val percents = Stem.unpackPercents(Stem.DEFAULT_PACKED)
        assertEquals(Stem.entries.size, percents.size)
        percents.forEach { assertEquals(Stem.GAIN_FULL, it) }
        assertEquals(Stem.DEFAULT_PACKED, Stem.packPercents(IntArray(Stem.entries.size) { Stem.GAIN_FULL }))
    }

    @Test
    fun `퍼센트 패킹을 왕복한다`() {
        val src = intArrayOf(0, 25, 50, 75, 100, 40)
        val packed = Stem.packPercents(src)
        assertArrayEquals(src, Stem.unpackPercents(packed))
        Stem.entries.forEachIndexed { i, stem ->
            assertEquals(src[i], Stem.percentOf(packed, stem))
        }
    }

    @Test
    fun `한 스템만 바꿔도 나머지는 유지된다`() {
        val packed = Stem.withPercent(Stem.DEFAULT_PACKED, Stem.VOCAL, 30)
        assertEquals(30, Stem.percentOf(packed, Stem.VOCAL))
        Stem.entries.filter { it != Stem.VOCAL }.forEach {
            assertEquals(Stem.GAIN_FULL, Stem.percentOf(packed, it))
        }
    }

    @Test
    fun `범위를 벗어난 값은 0_100으로 자른다`() {
        val packed = Stem.packPercents(intArrayOf(-10, 250, 50, 50, 50, 50))
        val out = Stem.unpackPercents(packed)
        assertEquals(0, out[0])
        assertEquals(100, out[1])
        assertEquals(50, out[2])
    }

    @Test
    fun `뮤트 마스크와 0_100 퍼센트를 서로 변환한다`() {
        val mask = Stem.VOCAL.bit or Stem.DRUMS.bit
        val packed = Stem.packedFromMuteMask(mask)
        assertEquals(0, Stem.percentOf(packed, Stem.VOCAL))
        assertEquals(0, Stem.percentOf(packed, Stem.DRUMS))
        assertEquals(100, Stem.percentOf(packed, Stem.BASS))
        assertEquals(mask, Stem.muteMaskFromPacked(packed))
        assertEquals(0, Stem.muteMaskFromPacked(Stem.DEFAULT_PACKED))
    }

    @Test
    fun `패킹된 퍼센트는 0_1 게인 배열이 된다`() {
        val packed = Stem.withPercent(
            Stem.withPercent(Stem.DEFAULT_PACKED, Stem.VOCAL, 0),
            Stem.GUITAR,
            50,
        )
        val gains = Stem.gainArrayFromPacked(packed)
        assertEquals(0f, gains[Stem.VOCAL.ordinal], 0f)
        assertEquals(0.5f, gains[Stem.GUITAR.ordinal], 0f)
        assertEquals(1f, gains[Stem.BASS.ordinal], 0f)
    }

    @Test
    fun `곡 저장은 패킹에서 뮤트 마스크를 파생한다`() {
        val packed = Stem.withPercent(Stem.DEFAULT_PACKED, Stem.BASS, 0)
        val song = Song(title = "t", uri = "file://x", durationMs = 1).withStemLevels(packed)
        assertEquals(packed, song.stemGainsPacked)
        assertEquals(Stem.BASS.bit, song.muteMask)
    }
}
