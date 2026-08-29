package com.bandmr.app.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bandmr.app.audio.DspChain
import com.bandmr.app.audio.MixCache
import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.audio.PitchShifter
import com.bandmr.app.audio.WavReader
import com.bandmr.app.audio.WavWriter
import com.bandmr.app.data.Song
import com.bandmr.app.data.Stem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Exporter"

/** 가공 믹스 및 스템 개별 파일 내보내기 */
class Exporter(private val context: Context) {

    /** 현재 설정(스템 유지 퍼센트 + 반음)으로 재생 중인 오디오를 WAV로 저장. 배속은 연습용이라 넣지 않는다 */
    suspend fun exportMix(
        song: Song,
        stemGainsPacked: Long,
        semitones: Int,
        aiOn: Boolean,
        dest: Uri,
        vocalStrength: Float = 1f,
        onProgress: (Float) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        if (aiOn && song.isSeparated) {
            exportMixFromStems(song, stemGainsPacked, semitones, dest, onProgress)
        } else {
            exportMixFromOriginal(
                song,
                Stem.muteMaskFromPacked(stemGainsPacked),
                semitones,
                vocalStrength,
                dest,
                onProgress,
            )
        }
    }

    private suspend fun exportMixFromStems(
        song: Song,
        stemGainsPacked: Long,
        semitones: Int,
        dest: Uri,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = File(song.stemsDir!!)
        val readers = HashMap<Stem, WavReader>()
        // 길이 기준은 재생(StemMixPlayer)과 동일하게 가장 긴 스템 — 짧은 스템은 뒷부분이 무음으로 섞인다
        var total = 0L
        Stem.entries.forEach { stem ->
            val f = File(dir, "${stem.fileName}.wav")
            if (f.exists()) runCatching {
                val r = WavReader(f)
                if (r.sampleRate == PIPELINE_SAMPLE_RATE) {
                    readers[stem] = r
                    total = maxOf(total, r.totalFrames)
                } else {
                    // 파이프라인 레이트 불일치 스템은 프레임 수학이 어긋나므로 제외한다
                    Log.w(TAG, "샘플레이트 불일치 스템 제외: ${f.name} (${r.sampleRate}Hz)")
                    runCatching { r.close() }
                }
            }
        }
        if (readers.isEmpty()) error("분리된 스템이 없습니다")

        val tmp = File(context.cacheDir, "export_mix.wav")
        tmp.delete()
        try {
            WavWriter.create(tmp, PIPELINE_SAMPLE_RATE).use { writer ->
                try {
                    val shifter = PitchShifter().also { it.semitones = semitones }
                    val gains = Stem.gainArrayFromPacked(stemGainsPacked)
                    val stemShort = ShortArray(CHUNK * 2)
                    val mixed = FloatArray(CHUNK * 2)
                    val outShort = ShortArray(CHUNK * 2)
                    var pos = 0L
                    while (pos < total) {
                        val frames = minOf(CHUNK.toLong(), total - pos).toInt()
                        java.util.Arrays.fill(mixed, 0, frames * 2, 0f)
                        readers.forEach { (stem, reader) ->
                            val g = gains[stem.ordinal]
                            if (g <= 0f) return@forEach
                            // 짧은 스템은 끝을 지나면 0프레임을 돌려주므로 더해지지 않는다(뒷부분 무음)
                            val got = reader.read(pos, stemShort, CHUNK)
                            for (i in 0 until got * 2) mixed[i] += stemShort[i] / 32768f * g
                        }
                        for (i in 0 until frames) {
                            shifter.process(mixed[i * 2], mixed[i * 2 + 1])
                            outShort[i * 2] = DspChain.clampShort(shifter.outL)
                            outShort[i * 2 + 1] = DspChain.clampShort(shifter.outR)
                        }
                        writer.writeShorts(outShort, frames * 2)
                        pos += frames
                        onProgress(pos.toFloat() / total)
                    }
                } finally {
                    readers.values.forEach { runCatching { it.close() } }
                }
            }
            copyTmpToDest(tmp, dest)
        } finally {
            // 실패해도 수십 MB짜리 중간 파일을 캐시에 남기지 않는다(성공 시엔 이미 지워졌다)
            tmp.delete()
        }
    }

    private suspend fun exportMixFromOriginal(
        song: Song,
        muteMask: Int,
        semitones: Int,
        vocalStrength: Float,
        dest: Uri,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // 재생(SourceWavPlayer)과 같은 소스를 쓴다 — 캐시가 있으면 원본을 다시 디코딩하지 않는다.
        // 캐시가 이미 있으면 디코딩 구간이 없으므로 진행률 전체를 렌더에 준다(0 → 0.4 점프 방지)
        val decodeShare = if (MixCache.cacheFile(context, song.id).exists()) 0f else DECODE_SHARE
        val source = MixCache.prepare(context, song.id, song.uri.toUri()) { p ->
            onProgress(p * decodeShare)
        }

        val chain = DspChain(PIPELINE_SAMPLE_RATE, 2).also {
            it.muteMask = muteMask
            it.vocalStrength = vocalStrength
        }
        val shifter = PitchShifter().also { it.semitones = semitones }
        val tmp = File(context.cacheDir, "export_mix.wav")
        tmp.delete()
        try {
            WavWriter.create(tmp, PIPELINE_SAMPLE_RATE).use { writer ->
                WavReader(source).use { reader ->
                    renderDspChunks(reader, chain, shifter, writer, decodeShare, onProgress)
                }
                chain.drain { buf, cnt -> writer.writeShorts(buf, cnt) }
            }
            copyTmpToDest(tmp, dest)
        } finally {
            // 실패해도 수십 MB짜리 중간 파일을 캐시에 남기지 않는다(성공 시엔 이미 지워졌다)
            tmp.delete()
        }
    }

    private fun renderDspChunks(
        reader: WavReader,
        chain: DspChain,
        shifter: PitchShifter,
        writer: WavWriter,
        decodeShare: Float,
        onProgress: (Float) -> Unit,
    ) {
        val totalFrames = reader.totalFrames
        val buf = ShortArray(CHUNK * 2)
        var pos = 0L
        while (pos < totalFrames) {
            val frames = reader.read(pos, buf, CHUNK)
            if (frames == 0) break
            // 재생 경로(SourceWavPlayer)와 동일한 순서: 피치시프트 → 제거 체인
            for (i in 0 until frames) {
                shifter.process(buf[i * 2] / 32768f, buf[i * 2 + 1] / 32768f)
                buf[i * 2] = DspChain.clampShort(shifter.outL)
                buf[i * 2 + 1] = DspChain.clampShort(shifter.outR)
            }
            chain.processInPlace(buf, frames * 2)
            writer.writeShorts(buf, frames * 2)
            pos += frames
            onProgress(decodeShare + (1f - decodeShare) * (pos.toFloat() / totalFrames))
        }
    }

    /** 분리된 스템을 사용자가 고른 폴더에 각각 저장 */
    suspend fun exportStems(
        song: Song,
        treeUri: Uri,
        onEach: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val dir = File(song.stemsDir ?: error("분리된 스템이 없습니다"))
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("저장 폴더를 열 수 없습니다")
        val base = safeName(song.title)
        var count = 0
        Stem.entries.forEach { stem ->
            val f = File(dir, "${stem.fileName}.wav")
            if (!f.exists()) return@forEach
            val target = tree.createFile("audio/wav", "${base}_${stem.fileName}.wav")
                ?: return@forEach
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                f.inputStream().use { input -> input.copyTo(out) }
            }
            onEach(stem.label)
            count++
        }
        count
    }

    private fun copyTmpToDest(tmp: File, dest: Uri) {
        context.contentResolver.openOutputStream(dest, "wt")?.use { out ->
            tmp.inputStream().use { input -> input.copyTo(out) }
        } ?: error("저장 위치를 열 수 없습니다")
        tmp.delete()
    }

    companion object {
        private const val CHUNK = 8192

        /** 캐시를 새로 만들어야 할 때 디코딩이 차지하는 진행률 비중 */
        private const val DECODE_SHARE = 0.4f

        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifEmpty { "track" }
    }
}
