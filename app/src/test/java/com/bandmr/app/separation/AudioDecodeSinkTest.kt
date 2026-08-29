package com.bandmr.app.separation

import com.bandmr.app.audio.DspChain
import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioDecode]의 버퍼 재사용·청크 방출 경로 검증.
 *
 * 이 경로는 MediaCodec 뒤에 있어 계측 없이는 확인이 안 되는데, 정작 최근 두 번의 최적화가
 * 여기를 건드렸다: 디코드 출력 배열 재사용(유효 길이를 [count]로 전달)과 샘플 단위 방출 →
 * 청크 방출(ShortSink). 둘 다 틀리면 **예외 없이 조용히 잡음/무음**이 되는 종류라,
 * MediaCodec을 뺀 순수 JVM 부분만 떼어 계약을 고정한다.
 *
 *  1. 재사용 버퍼의 [count] 밖 잔여 데이터가 출력에 새지 않는다
 *  2. 청크를 어떻게 쪼개도(싱크 버퍼 경계 포함) 출력이 바이트 단위로 같다
 */
class AudioDecodeSinkTest {

    @Test
    fun `재사용 버퍼의 잔여 데이터는 출력에 섞이지 않는다`() {
        val chunks = split(pcm(frames = 5000, channels = 2), chunkShorts = 2048)

        // 정확히 잘라 넘긴 경우 vs 큰 버퍼를 재사용하며 유효 길이만 넘긴 경우
        val exact = runSession(chunks, channels = 2, inRate = PIPELINE_SAMPLE_RATE, garbageTail = 0)
        val reused = runSession(chunks, channels = 2, inRate = PIPELINE_SAMPLE_RATE, garbageTail = 4096)

        assertTrue("출력이 비어 있으면 비교가 무의미하다", exact.isNotEmpty())
        assertArrayEquals("count 밖 잔여 데이터가 출력에 섞였다", exact, reused)
    }

    @Test
    fun `잔여 데이터 자체는 실제로 섞일 수 있는 값이다`() {
        // 무의미화 방지: 잔여 표식을 count 안까지 넣으면 결과가 달라져야 한다
        // (안 달라지면 위 테스트가 아무것도 검증하지 못한다)
        val clean = pcm(frames = 2000, channels = 2)
        val dirty = clean.copyOf().also { for (i in it.size - 400 until it.size) it[i] = GARBAGE }

        val a = runSession(split(clean, 2048), 2, PIPELINE_SAMPLE_RATE, garbageTail = 0)
        val b = runSession(split(dirty, 2048), 2, PIPELINE_SAMPLE_RATE, garbageTail = 0)
        assertTrue("표식 값이 출력에 영향을 주지 않으면 비교가 무의미", !a.contentEquals(b))
    }

    @Test
    fun `청크 크기가 달라도 출력이 같다 - 같은 레이트`() {
        assertSplitInvariant(inRate = PIPELINE_SAMPLE_RATE)
    }

    @Test
    fun `청크 크기가 달라도 출력이 같다 - 다운샘플`() {
        assertSplitInvariant(inRate = 48_000)
    }

    @Test
    fun `청크 크기가 달라도 출력이 같다 - 업샘플`() {
        assertSplitInvariant(inRate = 22_050)
    }

    @Test
    fun `싱크 버퍼 경계를 넘겨도 방출 내용이 보존된다`() {
        // SINK_BUF보다 긴 입력을 넣어 flush가 여러 번 일어나게 한다
        val total = AudioDecode.SINK_BUF * 2 + 777
        val expected = ShortArray(total) { ((it * 37) % 30000 - 15000).toShort() }

        val got = ArrayList<Short>(total)
        val sink = AudioDecode.ShortSink { buf, n ->
            assertTrue("싱크는 유효 길이만 넘겨야 한다", n in 1..buf.size)
            for (i in 0 until n) got.add(buf[i])
        }
        expected.forEach { sink.add(it) }
        sink.flush()

        assertEquals(total, got.size)
        assertArrayEquals(expected, got.toShortArray())
    }

    @Test
    fun `모노 입력은 좌우 같은 값으로 복제된다`() {
        val frames = 3000
        val mono = ShortArray(frames) { ((it * 53) % 20000 - 10000).toShort() }
        val out = runSession(listOf(mono), channels = 1, inRate = PIPELINE_SAMPLE_RATE, garbageTail = 0)

        assertTrue(out.isNotEmpty())
        assertEquals("스테레오 interleaved로 나와야 한다", 0, out.size % 2)
        for (i in out.indices step 2) {
            assertEquals("i=$i", out[i], out[i + 1])
        }
    }

    // ---------- 헬퍼 ----------

    private fun assertSplitInvariant(inRate: Int) {
        val data = pcm(frames = 6000, channels = 2)
        // 청크 크기: 프레임 정렬만 지키고 크기를 섞는다(MediaCodec 출력도 일정하지 않다)
        val a = runSession(split(data, 2048), 2, inRate, garbageTail = 0)
        val b = runSession(split(data, 512), 2, inRate, garbageTail = 0)
        val c = runSession(split(data, 8192), 2, inRate, garbageTail = 0)

        assertTrue(a.isNotEmpty())
        assertArrayEquals("inRate=$inRate: 청크 2048 vs 512", a, b)
        assertArrayEquals("inRate=$inRate: 청크 2048 vs 8192", a, c)
    }

    /**
     * 실제 디코딩 루프를 그대로 재현한다: 리샘플러·스테레오 버퍼·싱크를 세션 하나로 공유하고,
     * [garbageTail]>0이면 MediaCodec 경로처럼 큰 버퍼를 재사용하며 유효 길이만 넘긴다.
     */
    private fun runSession(
        chunks: List<ShortArray>,
        channels: Int,
        inRate: Int,
        garbageTail: Int,
    ): ShortArray {
        val out = ArrayList<Short>()
        val sink = AudioDecode.ShortSink { buf, n -> for (i in 0 until n) out.add(buf[i]) }
        val resampler = AudioDecode.LinearResampler(inRate, PIPELINE_SAMPLE_RATE)
        val mix = AudioDecode.StereoMixBuf()

        var reuse = ShortArray(0)
        chunks.forEach { chunk ->
            val need = chunk.size + garbageTail
            if (reuse.size < need) reuse = ShortArray(need)
            reuse.fill(GARBAGE) // 이전 청크 잔여를 흉내
            System.arraycopy(chunk, 0, reuse, 0, chunk.size)
            AudioDecode.emitStereo44k(reuse, chunk.size, channels, resampler, sink, mix)
        }
        // 프로덕션과 동일한 마무리: 리샘플 지연분 플러시 → 싱크 플러시
        resampler.flush { l, r ->
            sink.add(DspChain.clampShort(l))
            sink.add(DspChain.clampShort(r))
        }
        sink.flush()
        return out.toShortArray()
    }

    /** interleaved 테스트 신호(결정적). 좌우를 다르게 해 채널 뒤바뀜도 잡는다 */
    private fun pcm(frames: Int, channels: Int): ShortArray =
        ShortArray(frames * channels) { i ->
            val f = i / channels
            if (channels == 1 || i % 2 == 0) ((f * 41) % 24000 - 12000).toShort()
            else ((f * -29) % 18000 + 3000).toShort()
        }

    /** [chunkShorts] 단위로 자른다(프레임 정렬 유지 — 스테레오는 짝수) */
    private fun split(data: ShortArray, chunkShorts: Int): List<ShortArray> {
        val out = ArrayList<ShortArray>()
        var i = 0
        while (i < data.size) {
            val n = minOf(chunkShorts, data.size - i)
            out.add(data.copyOfRange(i, i + n))
            i += n
        }
        return out
    }

    private companion object {
        /** 재사용 버퍼에 남은 이전 청크를 흉내는 표식(무음이 아니어야 새는 걸 잡는다) */
        const val GARBAGE: Short = 0x5A5A
    }
}
