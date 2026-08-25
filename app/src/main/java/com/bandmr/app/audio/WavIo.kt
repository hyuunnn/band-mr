package com.bandmr.app.audio

import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16bit PCM WAV 파일 읽기 (랜덤 액세스) */
class WavReader(private val file: File) : Closeable {

    var sampleRate: Int = 44100
        private set
    var channels: Int = 2
        private set
    var totalFrames: Long = 0
        private set

    private val raf: RandomAccessFile = RandomAccessFile(file, "r")
    private var dataOffset: Long = 0
    private var frameBytes: Int = 4

    init {
        try {
            parseHeader()
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    private fun parseHeader() {
        raf.seek(0)
        val magic = ByteArray(12)
        raf.readFully(magic)
        val bb = ByteBuffer.wrap(magic).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.int == 0x52494646) { "RIFF 시그니처 없음" } // 'RIFF'
        bb.int // 전체 크기
        require(bb.int == 0x57415645) { "WAVE 시그니처 없음" } // 'WAVE'

        var fmtFound = false
        while (true) {
            val head = ByteArray(8)
            if (raf.read(head) < 8) break
            val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val id = hb.int
            val size = hb.int
            when (id) {
                CHUNK_FMT -> {
                    val body = ByteArray(minOf(size, 40))
                    raf.readFully(body)
                    val fb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = fb.short.toInt() and 0xFFFF
                    channels = fb.short.toInt()
                    sampleRate = fb.int
                    fb.int // byte rate
                    fb.short // block align
                    val bits = fb.short.toInt()
                    require(audioFormat == 1 && bits == 16) {
                        "지원하지 않는 WAV 포맷 (fmt=$audioFormat bits=$bits)"
                    }
                    frameBytes = channels * 2
                    fmtFound = true
                }
                CHUNK_DATA -> {
                    dataOffset = raf.filePointer
                    totalFrames = (size.toLong() and 0xFFFFFFFFL) / frameBytes.toLong()
                    if (!fmtFound) throw IOException("fmt 청크가 data보다 먼저 필요합니다: ${file.name}")
                    return
                }
                else -> raf.seek(raf.filePointer + ((size.toLong() and 0xFFFFFFFFL) + (size and 1)))
            }
        }
        throw IOException("WAV 파싱 실패: ${file.name}")
    }

    /**
     * [framePos] 프레임부터 [frames]개 만큼 읽어 interleaved short로 채운다.
     * @return 실제 읽은 프레임 수 (EOF 시 더 작음)
     */
    fun read(framePos: Long, out: ShortArray, frames: Int): Int {
        if (framePos >= totalFrames) return 0
        val toRead = minOf(frames.toLong(), totalFrames - framePos).toInt()
        val bytes = ByteArray(toRead * frameBytes)
        synchronized(raf) {
            raf.seek(dataOffset + framePos * frameBytes)
            var done = 0
            while (done < bytes.size) {
                val n = raf.read(bytes, done, bytes.size - done)
                if (n < 0) break
                done += n
            }
        }
        val bbuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until toRead * channels) out[i] = bbuf.short
        return toRead
    }

    override fun close() {
        raf.close()
    }

    companion object {
        private const val CHUNK_FMT = 0x666D7420   // 'fmt '
        private const val CHUNK_DATA = 0x64617461  // 'data'
    }
}

/** 파일 경로 기반 스트리밍 WAV 작성기 (16bit PCM, close() 시 크기 패치 보장) */
class WavWriter private constructor(
    filePath: String,
    out: java.io.OutputStream,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val raw: DataOutputStream = DataOutputStream(out.buffered(BUFFER))
    private var framesWritten: Long = 0
    private val path: String = filePath

    init {
        raw.writeInt(0x52494646)          // RIFF
        raw.writeInt(0)                   // 패치됨
        raw.writeInt(0x57415645)          // WAVE
        raw.writeInt(0x666D7420)          // fmt
        raw.writeInt(16)
        raw.writeShort(1)                 // PCM
        raw.writeShort(channels)
        raw.writeInt(sampleRate)
        raw.writeInt(sampleRate * channels * 2)
        raw.writeShort(channels * 2)
        raw.writeShort(16)
        raw.writeInt(0x64617461)          // data
        raw.writeInt(0)                   // 패치됨
    }

    /** interleaved shorts 중 [n]개(short 단위) 기록 */
    @Synchronized
    fun writeShorts(data: ShortArray, n: Int) {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(data[i])
        raw.write(bb.array())
        framesWritten += n / channels
    }

    override fun close() {
        raw.close()
        try {
            RandomAccessFile(path, "rw").use { f ->
                val dataBytes = framesWritten * channels * 2L
                f.seek(4)
                f.writeIntLe(((36 + dataBytes).toInt()))
                f.seek(40)
                f.writeIntLe((dataBytes and 0xFFFFFFFFL).toInt())
            }
        } catch (_: Exception) {
        }
    }

    private fun RandomAccessFile.writeIntLe(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    companion object {
        private const val BUFFER = 64 * 1024

        fun create(file: File, sampleRate: Int, channels: Int = 2): WavWriter =
            WavWriter(file.absolutePath, file.outputStream(), sampleRate, channels)
    }
}
