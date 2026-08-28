package com.bandmr.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.round

class PlaybackSpeedTest {

    private fun hundredths(speed: Float): Int = round(speed * 100f).toInt()

    @Test
    fun `0_05 단위로 스냅한다`() {
        assertEquals(25, hundredths(PlaybackSpeed.snap(0.1f)))
        assertEquals(25, hundredths(PlaybackSpeed.snap(0.27f)))
        assertEquals(30, hundredths(PlaybackSpeed.snap(0.28f)))
        assertEquals(30, hundredths(PlaybackSpeed.snap(0.32f)))
        assertEquals(35, hundredths(PlaybackSpeed.snap(0.33f)))
        assertEquals(100, hundredths(PlaybackSpeed.snap(1.02f)))
        assertEquals(105, hundredths(PlaybackSpeed.snap(1.03f)))
        assertEquals(200, hundredths(PlaybackSpeed.snap(1.98f)))
        assertEquals(200, hundredths(PlaybackSpeed.snap(4f)))
    }

    @Test
    fun `버튼 한 칸은 0_05씩 움직인다`() {
        assertEquals(95, hundredths(PlaybackSpeed.step(1.0f, -1)))
        assertEquals(105, hundredths(PlaybackSpeed.step(1.0f, 1)))
        assertEquals(25, hundredths(PlaybackSpeed.step(0.25f, -1)))
        assertEquals(30, hundredths(PlaybackSpeed.step(0.25f, 1)))
        assertEquals(35, hundredths(PlaybackSpeed.step(0.30f, 1)))
        assertEquals(200, hundredths(PlaybackSpeed.step(2.0f, 1)))
        assertEquals(195, hundredths(PlaybackSpeed.step(2.0f, -1)))
    }

    @Test
    fun `라벨은 1배는 원곡, 그 외는 두 자리 배수`() {
        assertTrue(PlaybackSpeed.isDefault(1f))
        assertTrue(PlaybackSpeed.isDefault(1.02f)) // snap → 1.00
        assertFalse(PlaybackSpeed.isDefault(1.05f))
        assertFalse(PlaybackSpeed.isDefault(0.25f))
        assertEquals("원곡 속도", PlaybackSpeed.formatLabel(1f))
        assertEquals("0.25×", PlaybackSpeed.formatLabel(0.25f))
        assertEquals("0.30×", PlaybackSpeed.formatMultiplier(0.30f))
        assertEquals("0.35×", PlaybackSpeed.formatMultiplier(0.35f))
        assertEquals("1.05×", PlaybackSpeed.formatMultiplier(1.05f))
        assertEquals("2×", PlaybackSpeed.formatMultiplier(2f))
    }

    @Test
    fun `슬라이더 눈금은 0_05 간격과 맞다`() {
        // 0.25 … 2.00, 0.05 간격 → 36칸, 사이 눈금 34개
        assertEquals(34, PlaybackSpeed.sliderSteps)
    }
}
