package com.bandmr.app.audio

import android.util.Log
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
    private var scratch: ByteArray = ByteArray(0)

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
        require(bb.int == MAGIC_RIFF) { "RIFF 시그니처 없음" }   // 'RIFF' (LE로 읽음)
        bb.int // 전체 크기
        require(bb.int == MAGIC_WAVE) { "WAVE 시그니처 없음" }     // 'WAVE' (LE로 읽음)

        var fmtFound = false
        while (true) {
            val head = ByteArray(8)
            if (raf.read(head) < 8) break
            val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val id = hb.int
            val size = hb.int
            when (id) {
                // FOURCC는 파일상 ASCII이므로 LE로 읽으면 아래 값과 일치한다
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
        val byteLen = toRead * frameBytes
        if (scratch.size < byteLen) scratch = ByteArray(byteLen)
        synchronized(raf) {
            raf.seek(dataOffset + framePos * frameBytes)
            var done = 0
            while (done < byteLen) {
                val n = raf.read(scratch, done, byteLen - done)
                if (n < 0) break
                done += n
            }
        }
        val bbuf = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until toRead * channels) out[i] = bbuf.short
        return toRead
    }

    override fun close() {
        raf.close()
    }

    companion object {
        /** 'RIFF' 4바이트를 little-endian int로 읽은 값 */
        internal const val MAGIC_RIFF = 0x46464952
        /** 'WAVE' */
        internal const val MAGIC_WAVE = 0x45564157
        /** 'fmt ' */
        internal const val CHUNK_FMT = 0x20746D66
        /** 'data' */
        internal const val CHUNK_DATA = 0x61746164
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

    // writeShorts 호출마다 재할당하지 않도록 재사용 (MixCache 준비는 수천 회 호출)
    private var scratch: ByteArray = ByteArray(0)

    init {
        // WAV 숫자 필드는 모두 little-endian. DataOutputStream은 big-endian이므로
        // 헤더는 직접 LE 바이트 배열로 작성한다.
        val h = ByteArray(44)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(WavReader.MAGIC_RIFF)              // 'RIFF'
        b.putInt(0)                                 // 파일 크기 (close에서 패치)
        b.putInt(WavReader.MAGIC_WAVE)              // 'WAVE'
        b.putInt(WavReader.CHUNK_FMT)               // 'fmt '
        b.putInt(16)                                // fmt 청크 크기
        b.putShort(1)                               // PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * 2)         // byte rate
        b.putShort((channels * 2).toShort())        // block align
        b.putShort(16)                              // bits per sample
        b.putInt(WavReader.CHUNK_DATA)              // 'data'
        b.putInt(0)                                 // data 크기 (close에서 패치)
        raw.write(h)
    }

    /** interleaved shorts 중 [n]개(short 단위) 기록 */
    @Synchronized
    fun writeShorts(data: ShortArray, n: Int) {
        if (n <= 0) return
        val byteLen = n * 2
        if (scratch.size < byteLen) scratch = ByteArray(byteLen)
        // WAV는 little-endian
        var i = 0
        while (i < n) {
            val v = data[i].toInt()
            scratch[i * 2] = (v and 0xFF).toByte()
            scratch[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        raw.write(scratch, 0, byteLen)
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
        } catch (e: Exception) {
            // 헤더 패치 실패 시 파일 크기 필드가 틀어져 재생/파싱이 깨질 수 있다
            Log.w("WavWriter", "WAV 헤더 패치 실패: $path", e)
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
