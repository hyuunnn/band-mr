package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [DspChain] 선채움 계약: 스펙트럼 단계가 켜진 마스크의 출력은 **1블록 무음 뒤에 스펙트럼 단계 출력이
 * 그대로 이어진 것**이어야 하고, 청크 크기와 무관해야 한다.
 *
 * 수정 전에는 모자란 샘플을 청크 끝에 0으로 채워, 리셋 직후 첫 청크(2048프레임이면 1536프레임부터
 * 512프레임)와 512의 배수가 아닌 청크(A-B의 B 직전·곡 끝)에서 신호 중간에 무음 구멍이 났다.
 * DspChainResetTest는 "리셋한 체인 == 새 체인" 비교라 양쪽에 똑같이 난 구멍을 잡지 못했다.
 */
class DspChainPrimeTest {

    private val sr = PIPELINE_SAMPLE_RATE
    private val block = SpectralStage.BLOCK
    private val hop = SpectralStage.HOP

    /** 리셋 직후 첫 청크, 512 배수가 아닌 청크, 블록보다 작은 청크, 내보내기 청크를 섞는다 */
    private val chunkings = listOf(
        intArrayOf(2048),
        intArrayOf(2048, 2048, 300, 2048, 777, 1500),
        intArrayOf(300, 700, 1024, 4096, 100),
        intArrayOf(8192),
    )

    @Test
    fun `연속 신호에 무음 구멍이 없다`() {
        chunkings.forEach { sizes ->
            val out = render(chain(Stem.VOCAL.bit), antiPhaseSine(FRAMES), sizes)
            for (i in 0 until block * 2) {
                assertEquals("선채움은 정확히 1블록 무음 sizes=${sizes.toList()} i=$i", 0, out[i].toInt())
            }
            // 스펙트럼 출력의 첫 hop은 창 때문에 0에서 페이드인하므로 앞 몇 프레임은 건너뛴다
            val runs = zeroRuns(out, fromFrame = block + FADE_SKIP, minLen = 8)
            assertTrue("sizes=${sizes.toList()} 무음 구멍(시작 프레임, 길이): $runs", runs.isEmpty())
        }
    }

    /**
     * 기존 구현([SpectralStage])과 수치 비교: 체인 출력 == 1블록 무음 + 입력 전체를 새 스테이지에 한 번에
     * 넣고 꺼낸 출력. 보컬 마스크만 켜면 시간 단계 필터가 없어 스펙트럼 출력의 클램프와 같아야 한다.
     */
    @Test
    fun `출력은 선채움 1블록 뒤 스펙트럼 단계 출력과 같다`() {
        val input = mixedSignal(FRAMES)
        val expected = ShortArray(input.size)
        val stage = SpectralStage(sr, 2)
        val fin = FloatArray(input.size) { input[it] / 32768f }
        stage.feed(fin, 0, fin.size, muteDrums = false, muteBass = false, muteVocal = true)
        val raw = FloatArray(fin.size)
        val got = stage.read(raw, 0, raw.size)
        val lead = block * 2
        assertTrue("스펙트럼 출력이 모자라면 비교가 성립하지 않는다", got >= expected.size - lead)
        for (i in lead until expected.size) expected[i] = DspChain.clampShort(raw[i - lead])

        chunkings.forEach { sizes ->
            assertArrayEquals("sizes=${sizes.toList()}", expected, render(chain(Stem.VOCAL.bit), input, sizes))
        }
    }

    /** 시간 단계 필터가 켜진 마스크까지 — 수정 전에는 구멍 위치가 청크마다 달라 출력이 갈렸다 */
    @Test
    fun `모든 마스크에서 청크 크기와 무관하게 출력이 같다`() {
        val input = mixedSignal(FRAMES)
        val all = Stem.VOCAL.bit or Stem.DRUMS.bit or Stem.BASS.bit or Stem.GUITAR.bit
        listOf(Stem.VOCAL.bit, Stem.DRUMS.bit, Stem.BASS.bit, Stem.GUITAR.bit, all).forEach { mask ->
            val reference = render(chain(mask), input, intArrayOf(2048))
            chunkings.forEach { sizes ->
                assertArrayEquals(
                    "mask=$mask sizes=${sizes.toList()}",
                    reference,
                    render(chain(mask), input, sizes),
                )
            }
        }
    }

    /**
     * 수정 전 구현과 수치 비교. 2048프레임 청크에서 수정 전 출력은 [0, 1536)이 스펙트럼 출력 그대로,
     * [1536, 2048)이 무음 구멍, 그 뒤는 hop(512)만큼 밀린 스펙트럼 출력이었다. 구멍을 뺀 나머지는
     * 수정 후 출력을 지연 차이만큼 당긴 것과 같다 — 선채움 말고는 바뀐 게 없다는 뜻이다.
     */
    @Test
    fun `수정 전 구현과는 구멍과 지연만 다르다`() {
        val input = mixedSignal(FRAMES)
        val legacy = legacyVocalOnly(input, chunkFrames = 2048)
        val fixed = render(chain(Stem.VOCAL.bit), input, intArrayOf(2048))

        for (f in block + hop until 2 * block) {
            assertEquals("수정 전 구멍 f=$f", 0, legacy[2 * f].toInt())
            assertEquals("수정 전 구멍 f=$f", 0, legacy[2 * f + 1].toInt())
        }
        // 구멍 앞: 수정 전은 지연 0, 수정 후는 1블록
        for (s in 0 until 2 * (block + hop)) {
            assertEquals("s=$s", legacy[s].toInt(), fixed[s + 2 * block].toInt())
        }
        // 구멍 뒤: 수정 전은 hop만큼 밀렸으므로 수정 후가 (블록 − hop)만큼 더 늦다
        val extra = 2 * (block - hop)
        for (s in 2 * (2 * block) until fixed.size - extra) {
            assertEquals("s=$s", legacy[s].toInt(), fixed[s + extra].toInt())
        }
    }

    // ---------- 헬퍼 ----------

    private fun chain(mask: Int) = DspChain(sr, 2).also { it.muteMask = mask }

    /** [sizes]를 돌려 가며 그 프레임 수만큼씩 제자리 처리한다 */
    private fun render(chain: DspChain, input: ShortArray, sizes: IntArray): ShortArray {
        val out = input.copyOf()
        var pos = 0
        var k = 0
        while (pos < out.size) {
            val n = minOf(sizes[k++ % sizes.size] * 2, out.size - pos)
            val part = out.copyOfRange(pos, pos + n)
            chain.processInPlace(part, n)
            System.arraycopy(part, 0, out, pos, n)
            pos += n
        }
        return out
    }

    /** 수정 전 [DspChain.processInPlace](보컬 마스크 = 시간 단계 없음): 모자란 샘플을 청크 끝에 0으로 채웠다 */
    private fun legacyVocalOnly(input: ShortArray, chunkFrames: Int): ShortArray {
        val stage = SpectralStage(sr, 2)
        val out = input.copyOf()
        val fin = FloatArray(chunkFrames * 2)
        val fout = FloatArray(chunkFrames * 2)
        var pos = 0
        while (pos < out.size) {
            val n = minOf(chunkFrames * 2, out.size - pos)
            for (k in 0 until n) fin[k] = out[pos + k] / 32768f
            stage.feed(fin, 0, n, muteDrums = false, muteBass = false, muteVocal = true)
            var got = stage.read(fout, 0, n)
            while (got < n) fout[got++] = 0f
            for (k in 0 until n) out[pos + k] = DspChain.clampShort(fout[k])
            pos += n
        }
        return out
    }

    /** [fromFrame] 이후 양 채널이 정확히 0인 구간 중 [minLen]프레임 이상인 것(시작, 길이) */
    private fun zeroRuns(out: ShortArray, fromFrame: Int, minLen: Int): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        val frames = out.size / 2
        var start = -1
        for (f in fromFrame..frames) {
            val zero = f < frames && out[2 * f].toInt() == 0 && out[2 * f + 1].toInt() == 0
            if (zero && start < 0) start = f
            if (!zero && start >= 0) {
                if (f - start >= minLen) runs += start to (f - start)
                start = -1
            }
        }
        return runs
    }

    /** 역위상(L = −R) 1kHz 사인 — 보컬 마스킹이 건드리지 않아 스펙트럼 단계를 그대로 통과한다 */
    private fun antiPhaseSine(frames: Int) = ShortArray(frames * 2).also { a ->
        for (f in 0 until frames) {
            val v = (sin(2 * PI * 1000.0 * f / sr) * 12000).toInt()
            a[2 * f] = v.toShort()
            a[2 * f + 1] = (-v).toShort()
        }
    }

    /** 중앙·사이드·저역·타악 성분을 섞어 보컬/드럼/베이스/기타 경로가 모두 일하게 한다 */
    private fun mixedSignal(frames: Int): ShortArray {
        val out = ShortArray(frames * 2)
        var rnd = 12345
        for (f in 0 until frames) {
            val t = f / sr.toDouble()
            rnd = rnd * 1103515245 + 12345
            val noise = ((rnd shr 16) and 0x7FFF) / 32768f - 0.5f
            val center = 0.3f * sin(2 * PI * 440.0 * t).toFloat()
            val side = 0.15f * sin(2 * PI * 1500.0 * t).toFloat()
            val bass = 0.2f * sin(2 * PI * 110.0 * t).toFloat()
            val perc = if (f % 2205 < 24) 0.4f * noise else 0.02f * noise
            out[2 * f] = DspChain.clampShort(center + side + bass + perc)
            out[2 * f + 1] = DspChain.clampShort(center - side + bass + perc)
        }
        return out
    }

    private companion object {
        /** 2048의 배수 — 수정 전 비교에서 마지막 청크가 모자라지 않게 한다 */
        const val FRAMES = 12_288

        /** 선채움 직후 페이드인 첫머리(창 값이 거의 0)에서 생기는 0 몇 개를 건너뛴다 */
        const val FADE_SKIP = 16
    }
}
