package com.bandmr.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * 듀얼 탭 가변 딜레이 피치 시프터 (raised-cosine 크로스페이드).
 * 스테레오 위상을 유지하기 위해 L/R이 동일한 포인터를 공유한다.
 * 실시간용으로 품질은 보통 수준 (±12반음에서 워블 아티팩트 있음).
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

    companion object {
        private const val WINDOW = 1800 // 약 41ms @44.1k
    }
}
