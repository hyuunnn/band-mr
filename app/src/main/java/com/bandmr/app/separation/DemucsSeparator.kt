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
 * htdemucs_6s는 모델 내부에서 크기 스펙트로그램을 자체 정규화하므로
 * 파형은 raw [-1,1] 값을 그대로 넣는다(원본 apply_model 경로와 동일).
 * 첫 구간은 램프인, 마지막 구간은 램프아웃을 생략한다(곡 시작/끝 페이드 방지).
 *
 * ONNX 세션은 [separate] 호출마다 열고 닫는다(캐시 금지). ORT 아레나가 수 GB 네이티브 힙을
 * 잡고 OS에 반환하지 않아서, 세션을 살려두면 분리 후에도 메모리가 그대로 유지된다.
 * 세션 오픈은 1초 남짓이고 분리는 곡당 수 분이므로 재사용 이득이 없다.
 */
class DemucsSeparator {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    fun separate(
        modelFile: File,
        config: ModelConfig,
        inputWav: File,
        outDir: File,
        segmentSamples: Int,
        onProgress: (Float, String) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Map<Stem, File> =
        OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(INTRA_OP_THREADS)
            env.createSession(modelFile.absolutePath, opts).use { session ->
                runSeparation(session, config, inputWav, outDir, segmentSamples, onProgress, isCancelled)
            }
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
                var written = 0L
                for (chunk in chunkPlan(totalFrames, seg)) {
                    // 취소는 오류가 아니므로 CancellationException으로 전파한다
                    // (SeparationService가 Idle 상태로 정리하고 UI에 오류를 띄우지 않음)
                    if (isCancelled()) throw java.util.concurrent.CancellationException("사용자가 취소했습니다")
                    val len = reader.read(chunk.pos, readShorts, chunk.len)
                    if (len == 0) break
                    // 헤더가 알리는 길이보다 파일이 짧으면(잘린 WAV) 읽힌 만큼만 기록하고 끝낸다
                    val truncated = len < chunk.len
                    val isLast = chunk.isLast || truncated
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
                    val writable = if (isLast) len else chunk.writable
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

                    written += writable
                    chunkIdx++
                    onProgress((written.toFloat() / totalFrames).coerceIn(0f, 1f), "분리 중… ${chunkIdx}구간")
                    if (truncated) break
                }
            }
        } finally {
            writers.values.forEach { runCatching { it.close() } }
        }
        return writers.keys.associateWith { File(outDir, "${it.fileName}.wav") }
    }

    internal companion object {
        internal const val FADE_DIVISOR = 4
        private const val INTRA_OP_THREADS = 4

        /** [chunkPlan]의 한 걸음. [pos]에서 [len]프레임을 읽어 [writable]프레임을 기록한다 */
        internal data class Chunk(
            val pos: Long,
            val len: Int,
            val writable: Int,
            val isLast: Boolean,
        )

        /**
         * 오버랩-애드 루프가 밟을 청크 순서. 루프와 테스트가 **같은 표**를 보게 하려고 분리했다.
         *
         * **기록 길이(writable)의 합은 [totalFrames]와 정확히 같아야 한다.** 어긋나면 스템이
         * 원본보다 길어져 AI ON 재생 길이가 AI OFF와 달라지고(StemWavSet이 가장 긴 스템을
         * 길이로 쓴다) 내보낸 파일도 함께 늘어난다.
         *
         * 예전 구현은 두 곳에서 초과 기록을 했다.
         *  - 남은 전체를 읽은 청크(`pos + len >= totalFrames`) 뒤에도 `pos += hop`이 아직
         *    끝보다 앞이면 루프가 한 바퀴 더 돌아 꼬리를 fade만큼 **다시 썼다**. 실기기 실측:
         *    3분35초 곡(9,486,336프레임)을 균형형으로 분리하면 9,551,872프레임(+1.49초)이
         *    나오고 마지막 약 1.1초가 중복 재생됐다. 길이·세그먼트 조합에 따라 약 1/3의 곡에서 발생
         *  - 마지막 청크를 `maxOf(len, fade)`로 기록해 남은 프레임이 fade보다 적으면 곡 끝을
         *    넘겼다. 그 구간의 캐리는 0 패딩된 입력에서 나온 것이라 버려야 한다
         *
         * 그래서 마지막 청크는 읽은 만큼만 기록하고 거기서 멈춘다.
         */
        internal fun chunkPlan(totalFrames: Long, seg: Int): List<Chunk> {
            val fade = seg / FADE_DIVISOR
            val hop = seg - fade
            val plan = ArrayList<Chunk>()
            var pos = 0L
            while (pos < totalFrames) {
                val len = minOf(seg.toLong(), totalFrames - pos).toInt()
                val isLast = pos + len >= totalFrames
                plan += Chunk(pos, len, if (isLast) len else hop, isLast)
                if (isLast) break
                pos += hop
            }
            return plan
        }

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
