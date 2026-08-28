package com.bandmr.app.audio

import android.media.AudioTrack
import android.media.PlaybackParams
import kotlin.math.round

/**
 * 재생 속도(템포). 키([PitchShifter])와 독립이며,
 * AudioTrack의 타임스트레치([PlaybackParams.speed])로 적용한다.
 * 0.25~2.0, 한 칸은 0.05 (0.25, 0.30, 0.35, …).
 */
object PlaybackSpeed {
    private const val MIN_H = 25
    private const val MAX_H = 200
    private const val STEP_H = 5

    const val MIN = MIN_H / 100f
    const val MAX = MAX_H / 100f
    const val DEFAULT = 1.0f

    /** Slider `steps`: min/max 사이 눈금 수 */
    val sliderSteps: Int = (MAX_H - MIN_H) / STEP_H - 1

    fun snap(speed: Float): Float = toSpeed(snapHundredths(speed))

    fun step(speed: Float, deltaSteps: Int): Float =
        toSpeed((snapHundredths(speed) + deltaSteps * STEP_H).coerceIn(MIN_H, MAX_H))

    fun isDefault(speed: Float): Boolean =
        snapHundredths(speed) == 100

    /** UI용. 1.0 → "원곡 속도", 그 외 "0.25×" / "0.30×" / "1.05×" */
    fun formatLabel(speed: Float): String =
        if (isDefault(speed)) "원곡 속도" else formatMultiplier(speed)

    fun formatMultiplier(speed: Float): String {
        val hundredths = snapHundredths(speed)
        val whole = hundredths / 100
        val frac = hundredths % 100
        return if (frac == 0) "${whole}×"
        else "$whole.${frac.toString().padStart(2, '0')}×"
    }

    /** 피치는 1로 고정 — 키 조절은 [PitchShifter]가 담당 */
    fun applyTo(track: AudioTrack?, speed: Float) {
        val t = track ?: return
        val s = snap(speed)
        runCatching {
            t.playbackParams = PlaybackParams()
                .setSpeed(s)
                .setPitch(1f)
        }.onFailure {
            android.util.Log.w("PlaybackSpeed", "속도 적용 실패 speed=$s", it)
        }
    }

    private fun snapHundredths(speed: Float): Int {
        val h = round(speed * 100f).toInt().coerceIn(MIN_H, MAX_H)
        val snapped = (round(h.toFloat() / STEP_H) * STEP_H).toInt()
        return snapped.coerceIn(MIN_H, MAX_H)
    }

    private fun toSpeed(hundredths: Int): Float = hundredths / 100f
}
