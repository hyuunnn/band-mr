package com.bandmr.app.audio

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MixCache가 예전에 쓰던 2패스 경로(raw 파일 → 헤더 붙여 복사)의 **참조 구현**.
 * 프로덕션은 1패스([WavWriter]로 직접 쓰기)로 바뀌었고, 이 구현은
 * "1패스 출력이 2패스 출력과 바이트 동일"을 고정하는 비교 대상으로만 남긴다.
 * 여기 코드는 절대 프로덕션에서 쓰지 않는다.
 */
internal object RawWavReference {

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

    /** 44바이트 표준 헤더 (크기 필드를 실제 값으로 미리 확정) */
    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
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

    /** interleaved shorts → LE raw 파일 */
    fun writeRaw(file: File, data: ShortArray) {
        val bb = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) bb.putShort(v)
        file.writeBytes(bb.array())
    }
}
