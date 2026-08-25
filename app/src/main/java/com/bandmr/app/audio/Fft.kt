package com.bandmr.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 사전 계산된 트위들 테이블을 쓰는 radix-2 FFT (in-place) */
class Fft(private val n: Int) {

    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n and (n - 1) == 0 && n >= 4) { "FFT 크기는 2의 거듭제곱" }
        for (i in 0 until n / 2) {
            cosTable[i] = cos(2.0 * PI * i / n).toFloat()
            sinTable[i] = sin(2.0 * PI * i / n).toFloat()
        }
        var bits = 0
        while (1 shl bits < n) bits++
        for (i in 0 until n) {
            var r = 0
            for (b in 0 until bits) if ((i and (1 shl b)) != 0) r = r or (1 shl (bits - 1 - b))
            rev[i] = r
        }
    }

    /** 역변환은 1/n 스케일까지 적용 */
    fun run(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (off in start until start + half) {
                    val other = off + half
                    val c = cosTable[k]
                    val sn = if (inverse) sinTable[k] else -sinTable[k]
                    val tre = re[other] * c - im[other] * sn
                    val tim = re[other] * sn + im[other] * c
                    re[other] = re[off] - tre
                    im[other] = im[off] - tim
                    re[off] += tre
                    im[off] += tim
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val invN = 1f / n
            for (i in 0 until n) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }
}
