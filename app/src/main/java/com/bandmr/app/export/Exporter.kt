package com.bandmr.app.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bandmr.app.audio.DspChain
import com.bandmr.app.audio.PitchShifter
import com.bandmr.app.audio.WavReader
import com.bandmr.app.audio.WavWriter
import com.bandmr.app.data.Song
import com.bandmr.app.data.Stem
import com.bandmr.app.separation.AudioDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

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
        var total = Long.MAX_VALUE
        Stem.entries.forEach { stem ->
            val f = File(dir, "${stem.fileName}.wav")
            if (f.exists()) runCatching {
                val r = WavReader(f)
                if (r.sampleRate == AudioDecode.TARGET_SR) {
                    readers[stem] = r
                    total = minOf(total, r.totalFrames)
                } else {
                    // 파이프라인 레이트 불일치 스템은 프레임 수학이 어긋나므로 제외한다
                    Log.w(TAG, "샘플레이트 불일치 스템 제외: ${f.name} (${r.sampleRate}Hz)")
                    runCatching { r.close() }
                }
            }
        }
        if (readers.isEmpty()) error("분리된 스템이 없습니다")
        if (total == Long.MAX_VALUE) total = 0

        val tmp = File(context.cacheDir, "export_mix.wav")
        tmp.delete()
        val writer = WavWriter.create(tmp, AudioDecode.TARGET_SR)
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
            writer.close()
            readers.values.forEach { runCatching { it.close() } }
        }
        copyTmpToDest(tmp, dest)
    }

    private suspend fun exportMixFromOriginal(
        song: Song,
        muteMask: Int,
        semitones: Int,
        vocalStrength: Float,
        dest: Uri,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val raw = File(context.cacheDir, "export_mix.raw")
        raw.delete()
        try {
            val totalFrames = AudioDecode.decodeToRaw44k(
                context, song.uri.toUri(), raw,
            ) { p -> onProgress(p * 0.4f) }

            val chain = DspChain(AudioDecode.TARGET_SR, 2).also {
                it.muteMask = muteMask
                it.vocalStrength = vocalStrength
            }
            val shifter = PitchShifter().also { it.semitones = semitones }
            val tmp = File(context.cacheDir, "export_mix.wav")
            tmp.delete()
            WavWriter.create(tmp, AudioDecode.TARGET_SR).use { writer ->
                RandomAccessFile(raw, "r").use { raf ->
                    renderDspChunks(raf, totalFrames, chain, shifter, writer, onProgress)
                }
                chain.drain { buf, cnt -> writer.writeShorts(buf, cnt) }
            }
            copyTmpToDest(tmp, dest)
        } finally {
            raw.delete()
        }
    }

    private fun renderDspChunks(
        raf: RandomAccessFile,
        totalFrames: Long,
        chain: DspChain,
        shifter: PitchShifter,
        writer: WavWriter,
        onProgress: (Float) -> Unit,
    ) {
        val buf = ShortArray(CHUNK * 2)
        val bytes = ByteArray(CHUNK * 4)
        var pos = 0L
        while (pos < totalFrames) {
            val frames = minOf(CHUNK.toLong(), totalFrames - pos).toInt()
            val byteLen = frames * 4
            raf.seek(pos * 4L)
            var done = 0
            while (done < byteLen) {
                val n = raf.read(bytes, done, byteLen - done)
                if (n < 0) break
                done += n
            }
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames * 2) buf[i] = bb.short
            // 재생 경로(SourceWavPlayer)와 동일한 순서: 피치시프트 → 제거 체인
            for (i in 0 until frames) {
                shifter.process(buf[i * 2] / 32768f, buf[i * 2 + 1] / 32768f)
                buf[i * 2] = DspChain.clampShort(shifter.outL)
                buf[i * 2 + 1] = DspChain.clampShort(shifter.outR)
            }
            chain.processInPlace(buf, frames * 2)
            writer.writeShorts(buf, frames * 2)
            pos += frames
            onProgress(0.4f + 0.6f * (pos.toFloat() / totalFrames))
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

        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifEmpty { "track" }
    }
}
