package com.bandmr.app.audio

import com.bandmr.app.data.Stem
import com.bandmr.app.io.FilePromote
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 재분리는 재생 중인 스템 디렉터리를 통째로 교체한다. 재생을 멈추지 않으려면 이미 열린 리더가
 * 교체 뒤에도 이전 내용을 끝까지 읽을 수 있어야 하고, 다시 연 묶음은 새 결과를 봐야 한다.
 */
class StemWavSetReplaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vocal = Stem.VOCAL.ordinal

    @Test
    fun `열린 스템 리더는 디렉터리 교체 뒤에도 이전 결과를 읽는다`() {
        val dest = tmp.newFolder("12")
        writeStem(dest, value = 100, frames = 8)
        val part = tmp.newFolder("12.part")
        writeStem(part, value = 200, frames = 4)

        StemWavSet.open(dest).use { playing ->
            FilePromote.directory(part, dest)

            val buf = ShortArray(16)
            assertEquals(8, playing.readerAt(vocal)!!.read(0, buf, 8))
            assertArrayEquals(ShortArray(16) { 100 }, buf)
        }
        StemWavSet.open(dest).use { reopened ->
            assertEquals(4L, reopened.totalFrames)
            val buf = ShortArray(8)
            assertEquals(4, reopened.readerAt(vocal)!!.read(0, buf, 4))
            assertArrayEquals(ShortArray(8) { 200 }, buf)
        }
    }

    private fun writeStem(dir: File, value: Short, frames: Int) {
        val pcm = ShortArray(frames * 2) { value }
        WavWriter.create(File(dir, "${Stem.VOCAL.fileName}.wav"), PIPELINE_SAMPLE_RATE).use {
            it.writeShorts(pcm, pcm.size)
        }
    }
}
