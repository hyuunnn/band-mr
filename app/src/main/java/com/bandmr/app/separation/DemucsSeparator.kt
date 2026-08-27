package com.bandmr.app.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bandmr.app.audio.DspChain
import com.bandmr.app.audio.WavReader
import com.bandmr.app.audio.WavWriter
import com.bandmr.app.data.Stem
import java.io.File
import java.nio.FloatBuffer

/**
 * Demucs ONNX 모델로 스템 분리.
 * 입력: MixCache와 동일한 44.1kHz 스테레오 PCM16 WAV / 출력: 스템별 WAV + 오버랩 크로스페이드.
 *
 * htdemucs는 모델 내부에서 크기 스펙트로그램을 자체 정규화하므로
 * 파형은 raw [-1,1] 값을 그대로 넣는다(원본 apply_model 경로와 동일).
 * 첫 구간은 램프인, 마지막 구간은 램프아웃을 생략한다(곡 시작/끝 페이드 방지).
 * ONNX 세션은 [OrtModelCache]가 모델 파일 단위로 재사용한다.
 */
class DemucsSeparator(private val env: OrtEnvironment = OrtEnvironment.getEnvironment()) {

    fun separate(
        modelFile: File,
        config: ModelConfig,
        inputWav: File,
        outDir: File,
        segmentSamples: Int,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Map<Stem, File> {
        val session = OrtModelCache.sessionFor(modelFile, env)
        return runSeparation(session, config, inputWav, outDir, segmentSamples, onProgress, isCancelled)
    }

    private fun runSeparation(
        session: OrtSession,
        config: ModelConfig,
        inputWav: File,
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
        val readShorts = ShortArray(seg * 2)
        val tmpL = FloatArray(seg)
        val tmpR = FloatArray(seg)
        val shortBuf = ShortArray(seg * 2)
        var chunkIdx = 0
        val inputName = session.inputNames.iterator().next()

        try {
            WavReader(inputWav).use { reader ->
                require(reader.sampleRate == sr && reader.channels == 2) {
                    "분리 입력은 ${sr}Hz 스테레오 WAV여야 합니다 (got ${reader.sampleRate}Hz/${reader.channels}ch)"
                }
                val totalFrames = reader.totalFrames
                var pos = 0L
                while (pos < totalFrames) {
                    // 취소는 오류가 아니므로 CancellationException으로 전파한다
                    // (SeparationService가 Idle 상태로 정리하고 UI에 오류를 띄우지 않음)
                    if (isCancelled()) throw java.util.concurrent.CancellationException("사용자가 취소했습니다")
                    val want = minOf(seg.toLong(), totalFrames - pos).toInt()
                    val len = reader.read(pos, readShorts, want)
                    if (len == 0) break
                    val isLast = pos + len >= totalFrames
                    interleavedS16ToPlanar(readShorts, len, seg, inPlanar)

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
                                vl = outArr[baseL + j] * w
                                vr = outArr[baseR + j] * w
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
                                carry[co + (j - hop)] = outArr[baseL + j] * w
                                carry[co + fade + (j - hop)] = outArr[baseR + j] * w
                            }
                        }
                        var f = 0
                        while (f < writable) {
                            shortBuf[f * 2] = DspChain.clampShort(tmpL[f])
                            shortBuf[f * 2 + 1] = DspChain.clampShort(tmpR[f])
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

    internal companion object {
        internal const val FADE_DIVISOR = 4

        /**
         * interleaved stereo s16 → 모델 입력 planar float [L(seg) | R(seg)].
         * [frames] 이후는 0 패딩(마지막 청크).
         */
        internal fun interleavedS16ToPlanar(
            src: ShortArray,
            frames: Int,
            seg: Int,
            dst: FloatArray,
        ) {
            java.util.Arrays.fill(dst, 0f)
            var i = 0
            while (i < frames) {
                dst[i] = src[i * 2] / 32768f
                dst[seg + i] = src[i * 2 + 1] / 32768f
                i++
            }
        }

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

/**
 * 모델 파일당 ONNX 세션 1개를 프로세스 동안 유지한다.
 * 등급을 바꾸면 이전 세션을 닫고 새 파일을 연다.
 */
internal object OrtModelCache {
    private val lock = Any()
    private var cachedPath: String? = null
    private var session: OrtSession? = null
    private var opts: OrtSession.SessionOptions? = null

    fun sessionFor(modelFile: File, env: OrtEnvironment): OrtSession = synchronized(lock) {
        val path = modelFile.absolutePath
        session?.let { if (cachedPath == path) return it }
        closeLocked()
        val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        session = env.createSession(path, o)
        opts = o
        cachedPath = path
        session!!
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        runCatching { session?.close() }
        runCatching { opts?.close() }
        session = null
        opts = null
        cachedPath = null
    }
}
