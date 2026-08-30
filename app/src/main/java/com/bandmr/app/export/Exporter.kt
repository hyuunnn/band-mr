package com.bandmr.app.export

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bandmr.app.audio.DspChain
import com.bandmr.app.audio.MixCache
import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.audio.PitchShifter
import com.bandmr.app.audio.StemWavSet
import com.bandmr.app.audio.WavReader
import com.bandmr.app.audio.WavWriter
import com.bandmr.app.data.Song
import com.bandmr.app.data.Stem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        val shifter = PitchShifter().also { it.semitones = semitones }
        val gains = Stem.gainArrayFromPacked(stemGainsPacked)
        val stemShort = ShortArray(CHUNK * 2)
        val mixed = FloatArray(CHUNK * 2)
        val outShort = ShortArray(CHUNK * 2)
        writeMixTo(dest) { writer ->
            // 열기 규칙(누락·레이트 불일치 제외, 최장 스템 기준 길이)은 재생과 공유한다.
            // use를 writer 안쪽에 두어 리더 6개가 dest 복사(수십 MB) 전에 닫히게 한다
            StemWavSet.open(File(song.stemsDir!!)).use { stems ->
                if (stems.isEmpty) error("분리된 스템이 없습니다")
                val total = stems.totalFrames
                var pos = 0L
                while (pos < total) {
                    val frames = minOf(CHUNK.toLong(), total - pos).toInt()
                    java.util.Arrays.fill(mixed, 0, frames * 2, 0f)
                    // 재생(StemMixPlayer)과 같은 접근자·같은 순서로 합산한다
                    for (ordinal in Stem.entries.indices) {
                        val g = gains[ordinal]
                        if (g <= 0f) continue
                        val reader = stems.readerAt(ordinal) ?: continue
                        // 짧은 스템은 끝을 지나면 0프레임을 돌려주므로 더해지지 않는다(뒷부분 무음)
                        val got = reader.read(pos, stemShort, CHUNK)
                        for (i in 0 until got * 2) mixed[i] += stemShort[i] / 32768f * g
                    }
                    shifter.renderTo(mixed, frames, outShort)
                    writer.writeShorts(outShort, frames * 2)
                    pos += frames
                    onProgress(pos.toFloat() / total)
                }
            }
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
        // 디코딩이 실제로 일어났는지는 콜백으로 관찰한다: 미리 exists()로 판정하면, 다른
        // 작업(앱 시작의 preCacheMixes)이 같은 곡을 만드는 중일 때 곡 단위 락에서 대기하다
        // 캐시가 생긴 뒤 진입해 onProgress 없이 반환하고, 렌더가 0.4부터 시작해 0 → 40%로 튄다.
        var sawDecode = false
        val source = MixCache.prepare(context, song.id, song.uri.toUri()) { p ->
            sawDecode = true
            onProgress(p * DECODE_SHARE)
        }
        val decodeShare = if (sawDecode) DECODE_SHARE else 0f

        val chain = DspChain(PIPELINE_SAMPLE_RATE, 2).also {
            it.muteMask = muteMask
            it.vocalStrength = vocalStrength
        }
        val shifter = PitchShifter().also { it.semitones = semitones }
        writeMixTo(dest) { writer ->
            WavReader(source).use { reader ->
                renderDspChunks(reader, chain, shifter, writer, decodeShare, onProgress)
            }
            chain.drain { buf, cnt -> writer.writeShorts(buf, cnt) }
        }
    }

    /**
     * 캐시의 임시 WAV에 [render]로 쓴 뒤 [dest]로 복사한다.
     *
     * SAF 목적지에 곧바로 쓰지 못하는 이유: [WavWriter]는 close 때 헤더의 크기 필드를 되짚어
     * 패치하므로 랜덤 액세스가 필요하다. 실패해도 수십 MB짜리 중간 파일을 캐시에 남기지 않는다.
     */
    private fun writeMixTo(dest: Uri, render: (WavWriter) -> Unit) {
        val tmp = File(context.cacheDir, "export_mix.wav")
        tmp.delete()
        try {
            WavWriter.create(tmp, PIPELINE_SAMPLE_RATE).use(render)
            copyTmpToDest(tmp, dest)
        } finally {
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
            shifter.renderTo(buf, frames, buf)
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
