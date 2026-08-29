package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시크마다 DspChain을 재할당하지 않고 [DspChain.reset]으로 제자리 리셋하기 위한 계약 검증:
 * "리셋한 체인의 출력 == 새로 만든 체인의 출력"이 바이트 단위로 성립해야 한다.
 *
 * 무의미한 테스트가 되지 않도록 (1) 리셋을 생략하면 실제로 달라진다는 것과
 * (2) 체인이 실제로 신호를 바꾼다는 것을 함께 검증한다.
 */
class DspChainResetTest {

    private val sr = PIPELINE_SAMPLE_RATE
    private val allMask =
        Stem.VOCAL.bit or Stem.DRUMS.bit or Stem.BASS.bit or Stem.GUITAR.bit

    @Test
    fun `리셋한 체인 출력은 새 체인 출력과 동일 - 스테레오`() {
        assertResetMatchesFresh(channels = 2, chunk = 2048)
    }

    @Test
    fun `리셋한 체인 출력은 새 체인 출력과 동일 - 모노`() {
        assertResetMatchesFresh(channels = 1, chunk = 2048)
    }

    @Test
    fun `청크 크기가 달라도 리셋 출력은 새 체인과 동일`() {
        // 블록(1024)의 배수/비배수, 블록보다 작은 값을 섞어 FIFO 경계를 흔든다
        intArrayOf(512, 700, 1024, 4096).forEach { chunk ->
            assertResetMatchesFresh(channels = 2, chunk = chunk)
        }
    }

    @Test
    fun `리셋을 생략하면 출력이 달라진다`() {
        val test = signal(FRAMES, 2, seed = 2)
        val fresh = render(newChain(2), test, chunk = 2048)

        // 워밍업만 하고 리셋하지 않은 체인
        val dirty = newChain(2)
        render(dirty, signal(FRAMES, 2, seed = 1), chunk = 2048)
        val without = render(dirty, test, chunk = 2048)

        assertFalse(
            "리셋 없이도 같다면 이 테스트는 리셋을 검증하지 못한다",
            without.contentEquals(fresh),
        )
    }

    @Test
    fun `체인은 실제로 신호를 바꾼다`() {
        val test = signal(FRAMES, 2, seed = 2)
        val processed = render(newChain(2), test, chunk = 2048)
        assertFalse("체인이 패스스루면 비교 자체가 무의미하다", processed.contentEquals(test))

        // 마스크가 0이면 반대로 완전 패스스루여야 한다
        val bypass = render(DspChain(sr, 2), test, chunk = 2048)
        assertArrayEquals(test, bypass)
    }

    @Test
    fun `muteMask 변경도 상태를 비운다`() {
        val test = signal(FRAMES, 2, seed = 2)
        val fresh = render(newChain(2), test, chunk = 2048)

        // 다른 마스크로 워밍업한 뒤 대상 마스크로 바꾸면(setter가 reset) 새 체인과 같아야 한다
        val reused = DspChain(sr, 2).also { it.muteMask = Stem.DRUMS.bit }
        render(reused, signal(FRAMES, 2, seed = 1), chunk = 2048)
        reused.muteMask = allMask
        assertArrayEquals(fresh, render(reused, test, chunk = 2048))
    }

    // ---------- 헬퍼 ----------

    private fun assertResetMatchesFresh(channels: Int, chunk: Int) {
        val test = signal(FRAMES, channels, seed = 2)

        val fresh = render(newChain(channels), test, chunk)

        val reused = newChain(channels)
        // 다른 신호로 FIFO·필터·magHist를 충분히 더럽힌다
        render(reused, signal(FRAMES, channels, seed = 1), chunk)
        reused.reset()
        val afterReset = render(reused, test, chunk)

        assertTrue("출력 길이가 입력과 같아야 함", fresh.size == test.size)
        assertArrayEquals(
            "ch=$channels chunk=$chunk: 리셋 출력이 새 체인과 다르다",
            fresh,
            afterReset,
        )
    }

    private fun newChain(channels: Int) = DspChain(sr, channels).also { it.muteMask = allMask }

    /** [chunk]프레임 단위로 제자리 처리하고 결과 복사본을 돌려준다 */
    private fun render(chain: DspChain, input: ShortArray, chunk: Int): ShortArray {
        val work = input.copyOf()
        var i = 0
        while (i < work.size) {
            val n = minOf(chunk, work.size - i)
            val part = ShortArray(n)
            System.arraycopy(work, i, part, 0, n)
            chain.processInPlace(part, n)
            System.arraycopy(part, 0, work, i, n)
            i += n
        }
        return work
    }

    /**
     * 결정적 테스트 신호. 중앙 성분(양 채널 동일)과 사이드 성분, 저역·타악성 임펄스를 섞어
     * 보컬/드럼/베이스/기타 경로가 모두 동작하게 한다.
     */
    private fun signal(frames: Int, channels: Int, seed: Int): ShortArray {
        val out = ShortArray(frames * channels)
        var rnd = seed * 7919 + 13
        for (f in 0 until frames) {
            val t = f / sr.toFloat()
            rnd = rnd * 1103515245 + 12345
            val noise = ((rnd shr 16) and 0x7FFF) / 32768f - 0.5f
            val center = 0.30f * kotlin.math.sin(2f * Math.PI.toFloat() * (220f + seed * 37f) * t)
            val bass = 0.25f * kotlin.math.sin(2f * Math.PI.toFloat() * (55f + seed * 3f) * t)
            val perc = if (f % (2000 + seed * 111) < 24) 0.4f * noise else 0.02f * noise
            val side = 0.15f * kotlin.math.sin(2f * Math.PI.toFloat() * (1500f + seed * 91f) * t)
            if (channels >= 2) {
                out[f * 2] = DspChain.clampShort(center + bass + perc + side)
                out[f * 2 + 1] = DspChain.clampShort(center + bass + perc - side)
            } else {
                out[f] = DspChain.clampShort(center + bass + perc)
            }
        }
        return out
    }

    private companion object {
        /** magHist(9블록) 워밍업을 넘기고 FIFO 경계를 여러 번 지나도록 충분히 길게 */
        const val FRAMES = 12_000
    }
}
