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

    /**
     * [PitchShifter.renderTo]는 재생([AudioTrackEngine])과 내보내기(Exporter)에 흩어져 있던
     * "process → clampShort" 루프를 하나로 모은 것이다. 원래 인라인 루프와 **바이트 동일**해야
     * 저장 파일이 들었던 소리와 일치한다(AGENTS.md: 신규 DSP는 원본 구현과 수치 비교).
     */
    @Test
    fun `renderTo는 옛 인라인 루프와 바이트 동일 - float 입력`() {
        for (semi in intArrayOf(-12, -5, 0, 7, 12)) {
            val frames = 3000
            val src = FloatArray(frames * 2) { i ->
                sin(2.0 * PI * (i % 2 == 0).let { if (it) 220.0 else 277.0 } * i / 44100.0)
                    .toFloat() * 0.8f
            }

            // 리팩토링 전 AudioTrackEngine.pitchFloatToOut 본문
            val legacy = ShortArray(frames * 2)
            val shLegacy = PitchShifter().also { it.semitones = semi }
            var i = 0
            while (i < frames * 2) {
                shLegacy.process(src[i], src[i + 1])
                legacy[i] = DspChain.clampShort(shLegacy.outL)
                legacy[i + 1] = DspChain.clampShort(shLegacy.outR)
                i += 2
            }

            val actual = ShortArray(frames * 2)
            PitchShifter().also { it.semitones = semi }.renderTo(src, frames, actual)

            assertArrayEquals("semi=$semi", legacy, actual)
        }
    }

    /**
     * 긴 연속 재생에서 읽기 인덱스가 버퍼 밖으로 나가 오디오 스레드가 죽던 회귀 테스트.
     *
     * `delay`가 현재 `write`보다 극히 조금 클 때(예: write=889, delay=889.000061)
     * `write - delay`는 -6.1e-5 수준의 음수가 되고, 1800 근처 float 간격이 1.22e-4라
     * `pos += window`가 **정확히 1800f로 반올림**된다 → `toInt()`가 배열 밖(=1800)을 가리킨다.
     * 실기기에서 `ArrayIndexOutOfBoundsException: length=1800; index=1800`으로 앱이 종료됐다.
     *
     * 아래 프레임 수는 각 반음이 처음 그 경계를 밟는 지점(무한 스윕으로 실측)이다.
     * 모든 비-0 반음에서 발생하며 가장 빠른 +6반음은 44.1k에서 5.3초, +1반음은 12초 만에 닿는다.
     */
    @Test
    fun `긴 재생에서도 읽기 인덱스가 버퍼를 벗어나지 않는다`() {
        // semi to 처음 경계를 밟는 프레임 번호
        val firstBoundaryHit = listOf(
            6 to 232_281,
            1 to 530_089,
            11 to 979_748,
            -10 to 2_124_852,
        )
        for ((semi, hit) in firstBoundaryHit) {
            val sh = PitchShifter().also { it.semitones = semi }
            val frames = hit + 1_000 // 경계를 확실히 지나도록 여유
            for (n in 0 until frames) {
                val v = sin(2.0 * PI * 220.0 * n / 44100.0).toFloat() * 0.5f
                sh.process(v, -v)
                if (sh.outL.isNaN() || sh.outR.isNaN()) {
                    throw AssertionError("semi=$semi n=$n NaN")
                }
            }
        }
    }

    @Test
    fun `renderTo는 옛 인라인 루프와 바이트 동일 - PCM16 입력 및 제자리 처리`() {
        for (semi in intArrayOf(-12, 0, 5)) {
            val frames = 3000
            val src = ShortArray(frames * 2) { i ->
                (sin(2.0 * PI * 330.0 * i / 44100.0) * 20000).toInt().toShort()
            }

            // 리팩토링 전 Exporter.renderDspChunks 본문 (제자리 처리)
            val legacy = src.copyOf()
            val shLegacy = PitchShifter().also { it.semitones = semi }
            for (f in 0 until frames) {
                shLegacy.process(legacy[f * 2] / 32768f, legacy[f * 2 + 1] / 32768f)
                legacy[f * 2] = DspChain.clampShort(shLegacy.outL)
                legacy[f * 2 + 1] = DspChain.clampShort(shLegacy.outR)
            }

            // src === out 앨리어싱이 안전해야 한다 (Exporter가 이렇게 쓴다)
            val inPlace = src.copyOf()
            PitchShifter().also { it.semitones = semi }.renderTo(inPlace, frames, inPlace)

            assertArrayEquals("semi=$semi 제자리", legacy, inPlace)

            // 별도 출력 배열을 줘도 같은 결과
            val separate = ShortArray(frames * 2)
            PitchShifter().also { it.semitones = semi }.renderTo(src, frames, separate)
            assertArrayEquals("semi=$semi 분리 버퍼", legacy, separate)
        }
    }
}
