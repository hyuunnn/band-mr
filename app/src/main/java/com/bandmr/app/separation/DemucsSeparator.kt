package com.bandmr.app.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bandmr.app.audio.WavWriter
import com.bandmr.app.data.Stem
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Demucs ONNX 모델로 스템 분리.
 * 입력: 44.1kHz 스테레오 PCM16 raw 파일 / 출력: 스템별 WAV + 오버랩 크로스페이드.
 *
 * 원본 demucs(apply_model)와 동일하게 구간별 mean/std 정규화를 적용하고,
 * 첫 구간은 램프인, 마지막 구간은 램프아웃을 생략한다(곡 시작/끝 페이드 방지).
 */
class DemucsSeparator(private val env: OrtEnvironment = OrtEnvironment.getEnvironment()) {

    fun separate(
        modelFile: File,
        config: ModelConfig,
        inputRaw: File,
        totalFrames: Long,
        outDir: File,
        segmentSamples: Int,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Map<Stem, File> {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        val session = env.createSession(modelFile.absolutePath, opts)
        try {
            return runSeparation(session, config, inputRaw, totalFrames, outDir, segmentSamples, onProgress, isCancelled)
        } finally {
            runCatching { session.close() }
            runCatching { opts.close() }
        }
    }

    private fun runSeparation(
        session: OrtSession,
        config: ModelConfig,
        inputRaw: File,
        totalFrames: Long,
        outDir: File,
        seg: Int,
        onProgress: (Float, String) -> Unit,
        isCancelled: () -> Boolean,
    ): Map<Stem, File> {
        val sr = config.sampleRate
        val nStems = config.stemOrder.size
        val fade = seg / FADE_DIVISOR
        val hop = seg - fade

        outDir.mkdirs()
        val stemIndex = HashMap<Stem, Int>()
        config.stemOrder.forEachIndexed { idx, name ->
            Stem.fromFileName(name)?.let { stemIndex[it] = idx }
        }

        val writers = LinkedHashMap<Stem, WavWriter>()
        Stem.entries.forEach { stem ->
            val idx = stemIndex[stem] ?: return@forEach
            writers[stem] = WavWriter.create(File(outDir, "${stem.fileName}.wav"), sr)
        }
        require(writers.isNotEmpty()) { "모델 출력에 유효한 스템이 없습니다" }

        // 이전 구간 꼬리(fade 샘플) 보관용: [stem][L/R][fade]
        val carry = FloatArray(nStems * 2 * fade)
        val inPlanar = FloatArray(2 * seg)
        val outArr = FloatArray(nStems * 2 * seg)
        val readBytes = ByteArray(seg * 4)
        val tmpL = FloatArray(seg)
        val tmpR = FloatArray(seg)
        val shortBuf = ShortArray(seg * 2)
        var chunkIdx = 0
        val inputName = session.inputNames.iterator().next()

        try {
            RandomAccessFile(inputRaw, "r").use { raf ->
                var pos = 0L
                while (pos < totalFrames) {
                    check(!isCancelled()) { "사용자가 취소했습니다" }
                    val len = minOf(seg.toLong(), totalFrames - pos).toInt()
                    val isLast = pos + len >= totalFrames

                    // ---- 입력 로드: interleaved s16le → planar float ----
                    java.util.Arrays.fill(inPlanar, 0f)
                    val byteLen = len * 4
                    raf.seek(pos * 4L)
                    var done = 0
                    while (done < byteLen) {
                        val n = raf.read(readBytes, done, byteLen - done)
                        if (n < 0) break
                        done += n
                    }
                    val bb = ByteBuffer.wrap(readBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (frame in 0 until len) {
                        inPlanar[frame] = bb.short / 32768f          // L
                        inPlanar[seg + frame] = bb.short / 32768f    // R
                    }

                    // ---- 구간별 정규화 (원본 demucs와 동일, 유효 구간만 계산) ----
                    var sum = 0.0
                    var sumSq = 0.0
                    for (frame in 0 until len) {
                        val l = inPlanar[frame].toDouble()
                        val r = inPlanar[seg + frame].toDouble()
                        sum += l + r
                        sumSq += l * l + r * r
                    }
                    val nValid = len * 2.0
                    var mean = (sum / nValid).toFloat()
                    var std = sqrt((sumSq / nValid - mean * mean).coerceAtLeast(0.0)).toFloat()
                    if (std < STD_EPSILON) { // 무음/거의 무음 구간 가드
                        mean = 0f; std = 1f
                    } else {
                        for (frame in 0 until len) {
                            inPlanar[frame] = (inPlanar[frame] - mean) / std
                            inPlanar[seg + frame] = (inPlanar[seg + frame] - mean) / std
                        }
                    }

                    // ---- 추론 ----
                    OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(inPlanar), longArrayOf(1, 2, seg.toLong())
                    ).use { tensor ->
                        session.run(mapOf(inputName to tensor)).use { results ->
                            val outTensor = results.get(0) as OnnxTensor
                            val fb = outTensor.floatBuffer
                            fb.rewind()
                            fb.get(outArr, 0, minOf(outArr.size, fb.remaining()))
                        }
                    }

                    // ---- 오버랩-애드 기록 ----
                    // 출력 텐서 레이아웃은 [stem][channel][seg] 이므로 stride는 len이 아니라 seg.
                    val writable = when {
                        !isLast -> hop
                        chunkIdx > 0 -> maxOf(len, fade) // 이전 구간 캐리 끝까지 플러시
                        else -> len
                    }
                    for ((stem, writer) in writers) {
                        val si = stemIndex.getValue(stem)
                        val baseL = si * 2 * seg
                        val baseR = baseL + seg
                        val co = si * 2 * fade
                        for (j in 0 until writable) {
                            var vl = 0f
                            var vr = 0f
                            if (j < len) {
                                val w = weightAt(j, len, chunkIdx == 0, isLast, fade)
                                vl = (outArr[baseL + j] * std + mean) * w
                                vr = (outArr[baseR + j] * std + mean) * w
                            }
                            if (chunkIdx > 0 && j < fade) {
                                vl += carry[co + j]
                                vr += carry[co + fade + j]
                            }
                            tmpL[j] = vl
                            tmpR[j] = vr
                        }
                        if (!isLast && len > hop) {
                            // 다음 구간과 겹치는 영역은 가중치 적용 후 캐리로 보관
                            for (j in hop until len) {
                                val w = weightAt(j, len, chunkIdx == 0, false, fade)
                                carry[co + (j - hop)] = (outArr[baseL + j] * std + mean) * w
                                carry[co + fade + (j - hop)] = (outArr[baseR + j] * std + mean) * w
                            }
                        }
                        var f = 0
                        while (f < writable) {
                            shortBuf[f * 2] = clamp(tmpL[f])
                            shortBuf[f * 2 + 1] = clamp(tmpR[f])
                            f++
                        }
                        writer.writeShorts(shortBuf, writable * 2)
                    }

                    pos += hop
                    chunkIdx++
                    onProgress((pos.toFloat() / totalFrames).coerceIn(0f, 1f), "분리 중… ${chunkIdx}구간")
                }
            }
        } finally {
            writers.values.forEach { runCatching { it.close() } }
        }
        return writers.keys.associateWith { File(outDir, "${it.fileName}.wav") }
    }

    private fun clamp(v: Float): Short =
        (v.coerceIn(-1f, 0.9999f) * 32767f).toInt().toShort()

    internal companion object {
        internal const val FADE_DIVISOR = 4
        private const val STD_EPSILON = 1e-4f

        /**
         * 크로스페이드 가중치.
         *  - 첫 구간: 램프인 없음(1로 시작)
         *  - 마지막 구간: 램프아웃 없음(1로 끝남)
         *  - 중간 구간: 앞뒤 [fade] 샘플씩 선형 램프 — 인접 구간 가중치 합 = 1
         */
        internal fun weightAt(j: Int, len: Int, isFirst: Boolean, isLast: Boolean, fade: Int): Float {
            if (fade == 0) return 1f
            var w = 1f
            if (!isFirst && j < fade) w *= j.toFloat() / fade
            if (!isLast) {
                val dEnd = len - 1 - j
                if (dEnd < fade) w *= dEnd.toFloat() / fade
            }
            return w.coerceIn(0f, 1f)
        }
    }
}
