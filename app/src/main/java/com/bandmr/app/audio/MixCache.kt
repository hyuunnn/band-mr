package com.bandmr.app.audio

import android.content.Context
import android.net.Uri
import com.bandmr.app.separation.AudioDecode
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 오디오 파이프라인 전체(캐시·분리·재생·내보내기)가 공유하는 샘플레이트.
 * MixCache WAV와 Demucs 스템 WAV는 모두 이 레이트로 생성되며, 불일치 입력은
 * 재생([StemMixPlayer])·내보내기([Exporter])에서 제외된다 — 프레임 수학 불일치 방지.
 */
const val PIPELINE_SAMPLE_RATE = 44_100

/**
 * 원본 곡의 디코딩 캐시. 압축 원본(content://)을 그 자리에서 스트리밍하지 않고,
 * 일부 기기(MediaCodec 비동기 경로가 불안정한 Android 16 펌웨어 등)에서도 재생이 보장되도록
 * 가져온 시점/첫 재생 시 44.1kHz 스테레오 PCM16 WAV로 변환해 앱 내부 저장소에 둔다.
 */
object MixCache {

    private const val DIR = "mixcache"

    fun cacheFile(context: Context, songId: Long): File =
        File(File(context.filesDir, DIR), "$songId.wav")

    fun delete(context: Context, songId: Long) {
        cacheFile(context, songId).delete()
    }

    // 같은 곡을 앱 시작 프리캐시와 재생 시점 준비가 동시에 만들지 않도록 곡 단위 직렬화
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>()

    /**
     * [sourceUri]를 디코딩해 `mixcache/<songId>.wav`로 저장한다.
     * 완료까지 수 초가 걸릴 수 있으므로 반드시 IO 디스패처에서 호출할 것.
     * 이미 캐시가 있으면(또는 다른 스레드가 방금 만들었으면) 즉시 반환한다.
     *
     * @return 캐시된 WAV 파일 (총 프레임 수는 [WavReader]로 확인)
     */
    fun prepare(context: Context, songId: Long, sourceUri: Uri): File {
        val lock = locks.computeIfAbsent(songId) { Any() }
        synchronized(lock) {
            val final = cacheFile(context, songId)
            if (final.exists()) return final
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val raw = File(dir, "$songId.raw")
            val part = File(dir, "${final.name}.part")
            try {
                AudioDecode.decodeToRaw44k(context, sourceUri, raw)
                RawToWav.convert(raw, part, AudioDecode.TARGET_SR)
                if (final.exists()) final.delete()
                check(part.renameTo(final)) { "캐시 파일 이동 실패: $part" }
                return final
            } finally {
                raw.delete()
                // 성공 시 part는 이미 final로 rename됨. 실패 시 남은 부분 파일을
                // final로 승격하면 손상 캐시가 재생에 쓰이므로 반드시 삭제만 한다.
                part.delete()
            }
        }
    }
}

/** raw PCM16 interleaved(stereo LE) 파일에 RIFF/WAVE 헤더를 붙여 WAV로 만든다. 순수 JVM. */
internal object RawToWav {

    /** @return 기록된 프레임 수 */
    fun convert(raw: File, target: File, sampleRate: Int, channels: Int = 2): Long {
        val dataBytes = raw.length()
        require(dataBytes % (channels * 2) == 0L) { "raw 크기가 프레임 정렬을 벗어남: $dataBytes" }
        val frames = dataBytes / (channels * 2)
        FileInputStream(raw).use { ins ->
            target.outputStream().use { out ->
                out.write(header(sampleRate, channels, dataBytes))
                ins.copyTo(out, 64 * 1024)
            }
        }
        return frames
    }

    /** WavWriter와 동일한 44바이트 표준 헤더 (크기 필드를 실제 값으로 미리 확정) */
    internal fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val h = ByteArray(44)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(WavReader.MAGIC_RIFF)                          // 'RIFF'
        b.putInt((36 + dataBytes).toInt())                      // 전체 크기 - 8
        b.putInt(WavReader.MAGIC_WAVE)                          // 'WAVE'
        b.putInt(WavReader.CHUNK_FMT)                           // 'fmt '
        b.putInt(16)                                            // fmt 청크 크기
        b.putShort(1)                                           // PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * 2)                     // byte rate
        b.putShort((channels * 2).toShort())                    // block align
        b.putShort(16)                                          // bits per sample
        b.putInt(WavReader.CHUNK_DATA)                          // 'data'
        b.putInt(dataBytes.toInt())
        return h
    }
}
