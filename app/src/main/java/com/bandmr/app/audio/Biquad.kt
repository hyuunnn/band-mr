package com.bandmr.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** RBJ cookbook biquad 필터 (모노 1채널) */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun setHighPass(sr: Float, freq: Float, q: Float) {
        val w0 = 2.0 * PI * freq / sr
        val cw = cos(w0); val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = ((1 + cw) / 2 / a0).toFloat()
        b1 = ((-(1 + cw)) / a0).toFloat()
        b2 = ((1 + cw) / 2 / a0).toFloat()
        a1 = ((-2 * cw) / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    fun setLowPass(sr: Float, freq: Float, q: Float) {
        val w0 = 2.0 * PI * freq / sr
        val cw = cos(w0); val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = (((1 - cw) / 2) / a0).toFloat()
        b1 = ((1 - cw) / a0).toFloat()
        b2 = (((1 - cw) / 2) / a0).toFloat()
        a1 = ((-2 * cw) / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    fun setPeaking(sr: Float, freq: Float, q: Float, dbGain: Float) {
        val A = 10.0.pow(dbGain / 40.0)
        val w0 = 2.0 * PI * freq / sr
        val cw = cos(w0); val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / A
        b0 = ((1 + alpha * A) / a0).toFloat()
        b1 = ((-2 * cw) / a0).toFloat()
        b2 = ((1 - alpha * A) / a0).toFloat()
        a1 = ((-2 * cw) / a0).toFloat()
        a2 = ((1 - alpha / A) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}
