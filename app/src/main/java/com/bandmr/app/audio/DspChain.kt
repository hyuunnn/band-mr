package com.bandmr.app.audio

import com.bandmr.app.data.Stem

/**
 * MR 제거 DSP 체인 (실시간·오프라인 공용).
 *
 *  스펙트럼 단계(SpectralStage): 드럼=HPSS 타악 억제 / 베이스=f0 배음 노칭
 *  시간 단계: 보컬=대역 한정 위상 상쇄(150Hz 미만 원본 유지) / 베이스=보조 하이패스 / 기타=중역 딥
 *
 * 스펙트럼 단계 때문에 약 1블록(1024샘플 ≈ 23ms)의 지연이 있으며,
 * 오프라인 렌더링 시 마지막에 [drain]을 호출해 잔여분을 출력해야 한다.
 */
class DspChain(private val sampleRate: Int, private val channels: Int) {

    @Volatile
    var muteMask: Int = 0
        set(value) {
            if (field != value) {
                field = value
                resetState()
            }
        }

    private val spectral = SpectralStage(sampleRate, channels)

    // 보컬: 저역 유지(LP) + 사이드 신호 하이패스
    private val vocKeepLpL = Biquad()
    private val vocKeepLpR = Biquad()
    private val vocHpSideA = Biquad()
    private val vocHpSideB = Biquad()

    // 베이스 보조 하이패스 2단
    private val hpL = arrayOf(Biquad(), Biquad())
    private val hpR = arrayOf(Biquad(), Biquad())

    // 기타: 중역 페킹 딥 2단
    private val dipL = arrayOf(Biquad(), Biquad())
    private val dipR = arrayOf(Biquad(), Biquad())

    private var floatIn = FloatArray(BUF)
    private var floatOut = FloatArray(BUF)

    init {
        rebuild()
    }

    private fun rebuild() {
        val sr = sampleRate.toFloat()
        vocKeepLpL.setLowPass(sr, VOCAL_KEEP_HZ, 0.707f)
        vocKeepLpR.setLowPass(sr, VOCAL_KEEP_HZ, 0.707f)
        vocHpSideA.setHighPass(sr, VOCAL_KEEP_HZ, 0.707f)
        vocHpSideB.setHighPass(sr, VOCAL_KEEP_HZ, 0.707f)
        for (i in 0..1) {
            hpL[i].setHighPass(sr, BASS_HP_HZ, 0.707f)
            hpR[i].setHighPass(sr, BASS_HP_HZ, 0.707f)
            dipL[i].setPeaking(sr, if (i == 0) 1200f else 3000f, if (i == 0) 0.9f else 1.0f, if (i == 0) -9f else -7f)
            dipR[i].setPeaking(sr, if (i == 0) 1200f else 3000f, if (i == 0) 0.9f else 1.0f, if (i == 0) -9f else -7f)
        }
    }

    private fun resetState() {
        rebuild()
        for (i in 0..1) {
            hpL[i].reset(); hpR[i].reset(); dipL[i].reset(); dipR[i].reset()
        }
        vocKeepLpL.reset(); vocKeepLpR.reset(); vocHpSideA.reset(); vocHpSideB.reset()
        spectral.reset()
    }

    /** interleaved shorts [n]개를 제자리 처리 */
    fun processInPlace(data: ShortArray, n: Int) {
        val mask = muteMask
        if (mask == 0 || n == 0) return
        ensureBuffers(n)

        for (i in 0 until n) floatIn[i] = data[i] / 32768f
        spectral.feed(floatIn, 0, n, drumsOn(mask), bassOn(mask))

        var got = spectral.read(floatOut, 0, n)
        while (got < n) floatOut[got++] = 0f

        applyTimeDomain(floatOut, n, mask)
        for (i in 0 until n) data[i] = DspChain.clampShort(floatOut[i])
    }

    private fun applyTimeDomain(x: FloatArray, n: Int, mask: Int) {
        val stereo = channels >= 2
        val vocal = mask and Stem.VOCAL.bit != 0
        val bass = mask and Stem.BASS.bit != 0
        val guitar = mask and Stem.GUITAR.bit != 0

        if (stereo) {
            var i = 0
            while (i + 1 < n) {
                var l = x[i]
                var r = x[i + 1]
                if (vocal) {
                    // 저역은 원본 유지, 고역만 L-R 상쇄 → 킥/베이스 중앙 성분 보존
                    val sideA = vocHpSideA.process(l - r) * 0.70710678f
                    val sideB = vocHpSideB.process(r - l) * 0.70710678f
                    l = vocKeepLpL.process(l) + sideA
                    r = vocKeepLpR.process(r) + sideB
                }
                if (bass) {
                    l = hpL[0].process(hpL[1].process(l))
                    r = hpR[0].process(hpR[1].process(r))
                }
                if (guitar) {
                    l = dipL[0].process(dipL[1].process(l))
                    r = dipR[0].process(dipR[1].process(r))
                }
                x[i] = l
                x[i + 1] = r
                i += 2
            }
        } else {
            for (i in 0 until n) {
                var m = x[i]
                if (bass) m = hpL[0].process(hpL[1].process(m))
                if (guitar) m = dipL[0].process(dipL[1].process(m))
                x[i] = m
            }
        }
    }

    /**
     * 오프라인 렌더링 종료 시 호출. 스펙트럼 파이프라인에 남은 지연분을
     * 무음 입력으로 밀어내며 결과를 [sink]로 방출한다.
     */
    fun drain(sink: (ShortArray, Int) -> Unit) {
        val mask = muteMask
        if (mask == 0) return
        val flushSamples = SpectralStage.BLOCK * 4
        val zeros = FloatArray(SpectralStage.HOP * 2)
        val out = ShortArray(BUF)
        var emitted = 0
        while (emitted < flushSamples) {
            val chunk = minOf(zeros.size, flushSamples - emitted)
            spectral.feed(zeros, 0, chunk, drumsOn(mask), bassOn(mask))
            emitted += chunk
            var got = spectral.read(floatOut, 0, floatOut.size)
            if (got > 0) {
                applyTimeDomain(floatOut, got, mask)
                for (i in 0 until got) out[i] = clampShort(floatOut[i])
                sink(out, got)
            }
        }
        // FIFO 완전 비우기
        while (true) {
            val got = spectral.read(floatOut, 0, floatOut.size)
            if (got <= 0) break
            applyTimeDomain(floatOut, got, mask)
            for (i in 0 until got) out[i] = clampShort(floatOut[i])
            sink(out, got)
        }
    }

    private fun drumsOn(mask: Int) = mask and Stem.DRUMS.bit != 0
    private fun bassOn(mask: Int) = mask and Stem.BASS.bit != 0

    private fun ensureBuffers(n: Int) {
        if (floatIn.size < n) {
            floatIn = FloatArray(maxOf(n, floatIn.size * 2))
            floatOut = FloatArray(floatIn.size)
        } else if (floatOut.size < n) {
            floatOut = FloatArray(n)
        }
    }

    companion object {
        fun clampShort(v: Float): Short =
            (v.coerceIn(-1f, 0.9999f) * 32767f).toInt().toShort()

        private const val BUF = 4096
        private const val VOCAL_KEEP_HZ = 150f
        private const val BASS_HP_HZ = 100f
    }
}
