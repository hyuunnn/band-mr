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

/**
 * Demucs ONNX 모델로 스템 분리.
 * 입력: 44.1kHz 스테레오 PCM16 raw 파일 / 출력: 스템별 WAV + 오버랩 크로스페이드.
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

        val carry = FloatArray(nStems * 2 * fade)
        val inPlanar = FloatArray(2 * seg)
        val outArr = FloatArray(nStems * 2 * seg)
        val readBytes = ByteArray(seg * 4)
        val tmpL = FloatArray(seg)
        val tmpR = FloatArray(seg)
        val shortBuf = ShortArray(hop * 2)
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
                    val writable = if (isLast) len else hop
                    for ((stem, writer) in writers) {
                        val si = stemIndex.getValue(stem)
                        for (j in 0 until len) {
                            val lIdx = (si * 2) * len + j
                            val rIdx = (si * 2 + 1) * len + j
                            val w = weightAt(j, len, fade)
                            var vl = outArr[lIdx] * w
                            var vr = outArr[rIdx] * w
                            val co = si * 2 * fade
                            if (!isLast && j < fade && chunkIdx > 0) {
                                vl += carry[co + j]
                                vr += carry[co + fade + j]
                            }
                            if (isLast) {
                                tmpL[j] = vl; tmpR[j] = vr
                            } else if (j < hop) {
                                tmpL[j] = vl; tmpR[j] = vr
                            } else {
                                // 다음 청크와 겹치는 영역은 캐리로 보관
                                carry[co + (j - hop)] = vl
                                carry[co + fade + (j - hop)] = vr
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

    private fun weightAt(j: Int, len: Int, fade: Int): Float {
        val d = minOf(j, len - 1 - j)
        return if (d >= fade || fade == 0) 1f else d.toFloat() / fade
    }

    private fun clamp(v: Float): Short =
        (v.coerceIn(-1f, 0.9999f) * 32767f).toInt().toShort()

    companion object {
        private const val FADE_DIVISOR = 4
    }
}
