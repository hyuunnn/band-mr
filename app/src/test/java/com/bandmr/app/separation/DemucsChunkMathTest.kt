package com.bandmr.app.separation

import com.bandmr.app.audio.RawWavReference
import com.bandmr.app.audio.WavReader
import com.bandmr.app.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DemucsChunkMathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val seg = 1024
    private val fade = seg / DemucsSeparator.FADE_DIVISOR
    private val hop = seg - fade

    @Test
    fun `첫 구간은 램프인 없이 1로 시작`() {
        for (j in 0 until fade) {
            assertEquals(1f, DemucsSeparator.weightAt(j, seg, isFirst = true, isLast = false, fade), 1e-6f)
        }
    }

    @Test
    fun `마지막 구간은 램프아웃 없이 1로 끝난다`() {
        for (j in maxOf(0, seg - fade) until seg) {
            assertEquals(1f, DemucsSeparator.weightAt(j, seg, isFirst = false, isLast = true, fade), 1e-6f)
        }
    }

    @Test
    fun `중간 구간은 양끝 램프`() {
        assertEquals(0f, DemucsSeparator.weightAt(0, seg, false, false, fade), 1e-6f)
        assertEquals(fade.toFloat() / fade, DemucsSeparator.weightAt(fade, seg, false, false, fade), 1e-6f)
        val dEnd0 = DemucsSeparator.weightAt(seg - 1, seg, false, false, fade)
        assertEquals(0f, dEnd0, 1e-6f)
    }

    @Test
    fun `인접 구간 가중치 합이 1에 수렴`() {
        // 이전 구간 캐리 영역 j∈[hop,seg) ↔ 다음 구간 머리 j'=j-hop∈[0,fade)
        for (jp in 0 until fade) {
            val j = jp + hop
            val wPrev = DemucsSeparator.weightAt(j, seg, isFirst = false, isLast = false, fade)
            val wNext = DemucsSeparator.weightAt(jp, seg, isFirst = false, isLast = false, fade)
            val sum = wPrev + wNext
            assertTrue("jp=$jp sum=$sum", sum in 0.98f..1.02f)
        }
    }

    @Test
    fun `단일 구간(첫+마지막)은 전부 가중치 1`() {
        val len = 700
        for (j in 0 until len) {
            assertEquals(
                1f,
                DemucsSeparator.weightAt(j, len, isFirst = true, isLast = true, fade),
                1e-6f,
            )
        }
    }

    @Test
    fun `fade 0이면 항상 1`() {
        for (j in 0 until 16) {
            assertEquals(1f, DemucsSeparator.weightAt(j, 16, false, false, 0), 1e-6f)
        }
    }

    @Test
    fun `interleaved s16 planar 변환은 예전 raw ByteBuffer 경로와 같다`() {
        val frames = 8
        val shorts = ShortArray(frames * 2) { i ->
            if (i % 2 == 0) (i * 111).toShort() else (i * -77).toShort()
        }
        val bytes = java.nio.ByteBuffer.allocate(shorts.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) bytes.putShort(s)

        val expected = FloatArray(2 * seg)
        bytes.rewind()
        val bb = bytes.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            expected[frame] = bb.short / 32768f
            expected[seg + frame] = bb.short / 32768f
        }

        val actual = FloatArray(2 * seg)
        DemucsSeparator.interleavedS16ToPlanar(shorts, frames, seg, actual)
        expected.indices.forEach { i ->
            assertEquals("i=$i", expected[i], actual[i], 0f)
        }
        // 마지막 청크 0 패딩: frames 이후 L/R 모두 0
        for (i in frames until seg) {
            assertEquals(0f, actual[i], 0f)
            assertEquals(0f, actual[seg + i], 0f)
        }
    }

    @Test
    fun `MixCache WAV에서 읽은 입력이 raw 바이트 경로와 같다`() {
        val frames = 64
        val shorts = ShortArray(frames * 2) { i -> ((i * 31) - 400).toShort() }
        val raw = tmp.newFile("in.raw")
        val wav = tmp.newFile("in.wav")
        RawWavReference.writeRaw(raw, shorts)
        // MixCache와 동일하게 WavWriter로 쓴다
        WavWriter.create(wav, 44100).use { it.writeShorts(shorts, shorts.size) }

        val fromWav = FloatArray(2 * seg)
        WavReader(wav).use { r ->
            val buf = ShortArray(frames * 2)
            assertEquals(frames, r.read(0, buf, frames))
            DemucsSeparator.interleavedS16ToPlanar(buf, frames, seg, fromWav)
        }

        val fromRaw = FloatArray(2 * seg)
        val bb = java.nio.ByteBuffer.wrap(raw.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            fromRaw[frame] = bb.short / 32768f
            fromRaw[seg + frame] = bb.short / 32768f
        }
        fromWav.indices.forEach { i ->
            assertEquals("i=$i", fromRaw[i], fromWav[i], 0f)
        }
    }
}
