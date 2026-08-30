package com.bandmr.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * 듀얼 탭 가변 딜레이 피치 시프터 (raised-cosine 크로스페이드).
 * 스테레오 위상을 유지하기 위해 L/R이 동일한 포인터를 공유한다.
 *
 * 두 읽기 탭의 간격이 버퍼의 절반(900샘플 = 20.4ms)으로 고정이라, 그 간격이 반파장에 걸리는
 * 주파수는 크로스페이드가 스칠 때 깊게 상쇄된다 — 플랜저 성분이 생기는 원인이다. 실측(순음 훑기)
 * 진폭 변동은 중간값 약 4dB, 최악 약 30dB. **반음이 커져도 깊이는 늘지 않고 훑는 속도만 빨라진다**
 * (+1반음 약 1.5Hz → +12반음 약 24.5Hz). 여러 악기가 섞인 음원에서는 서로 가려져 잘 드러나지 않는다.
 * 없애려면 위상 보코더로 바꿔야 하는데, 그쪽은 대신 phasiness가 생긴다.
 */
class PitchShifter {

    private val window = WINDOW
    private val bufL = FloatArray(WINDOW)
    private val bufR = FloatArray(WINDOW)
    private var write = 0
    private var phase = 0f
    private var ratio = 1f

    var semitones: Int = 0
        set(value) {
            if (field != value) {
                field = value
                ratio = Math.pow(2.0, value / 12.0).toFloat()
                phase = 0f
            }
        }

    var outL: Float = 0f
        private set
    var outR: Float = 0f
        private set

    fun process(l: Float, r: Float) {
        bufL[write] = l
        bufR[write] = r

        // 0반음은 지연·크로스페이드 없는 패스스루 (연습 앱 특성상 기본 상태가 0이므로 중요)
        if (ratio == 1f) {
            outL = l
            outR = r
            write++
            if (write >= window) write = 0
            return
        }

        phase += (ratio - 1f) / window
        phase -= floor(phase)

        // 지연 = (1 - phase) * W : ratio>1(업)일 때 지연이 감소해야 하므로
        val p1 = phase
        val p2 = if (phase < 0.5f) phase + 0.5f else phase - 0.5f
        val d1 = (1f - p1) * window
        val d2 = (1f - p2) * window

        val s1l = read(bufL, d1); val s2l = read(bufL, d2)
        val s1r = read(bufR, d1); val s2r = read(bufR, d2)

        val a1 = 0.5f - 0.5f * cos(2.0 * PI * p1).toFloat()
        val a2 = 1f - a1

        outL = s1l * a1 + s2l * a2
        outR = s1r * a1 + s2r * a2

        write++
        if (write >= window) write = 0
    }

    private fun read(buf: FloatArray, delay: Float): Float {
        var pos = write - delay
        while (pos < 0f) pos += window
        val i = pos.toInt()
        val fr = pos - i
        val j = if (i + 1 >= window) 0 else i + 1
        return buf[i] * (1f - fr) + buf[j] * fr
    }

    fun reset() {
        java.util.Arrays.fill(bufL, 0f)
        java.util.Arrays.fill(bufR, 0f)
        write = 0
        phase = 0f
    }

    /**
     * interleaved 스테레오 [frames]프레임에 피치를 적용해 PCM16으로 [out]에 쓴다.
     *
     * 재생([AudioTrackEngine])과 내보내기(Exporter)가 반드시 같은 순서·같은 클램프를 지나야
     * 저장 파일이 들었던 소리와 일치한다 — 주석으로만 맞추지 않도록 이 함수를 공유한다.
     */
    fun renderTo(src: FloatArray, frames: Int, out: ShortArray) {
        var i = 0
        while (i < frames * 2) {
            process(src[i], src[i + 1])
            out[i] = DspChain.clampShort(outL)
            out[i + 1] = DspChain.clampShort(outR)
            i += 2
        }
    }

    /**
     * [renderTo]의 PCM16 입력판. 내부에서 -1..1로 정규화한다.
     * [src]와 [out]이 같은 배열이어도 안전하다(프레임을 다 읽은 뒤에 쓴다) — Exporter가 제자리 처리에 쓴다.
     */
    fun renderTo(src: ShortArray, frames: Int, out: ShortArray) {
        var i = 0
        while (i < frames * 2) {
            process(src[i] / 32768f, src[i + 1] / 32768f)
            out[i] = DspChain.clampShort(outL)
            out[i + 1] = DspChain.clampShort(outR)
            i += 2
        }
    }

    companion object {
        private const val WINDOW = 1800 // 약 41ms @44.1k
    }
}
