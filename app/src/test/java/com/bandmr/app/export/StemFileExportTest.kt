package com.bandmr.app.export

import com.bandmr.app.data.Stem
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

class StemFileExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `출력 스트림이 null이면 현재 빈 파일만 지우고 성공으로 세지 않는다`() = runTest {
        val files = Files()
        try {
            files.export(open = { if (it.name == "drums.wav") null else it.outputStream() })
            fail("열기 실패가 전파되어야 한다")
        } catch (failure: IOException) {
            assertEquals("저장 위치를 열 수 없습니다", failure.message)
        }
        files.assertOnlyVocalSaved()
    }

    @Test
    fun `복사 중 실패하면 현재 부분 파일만 지우고 앞서 저장한 스템은 보존한다`() = runTest {
        val files = Files()
        val writeFailure = IOException("disk full")
        var partialSize = 0L
        try {
            files.export(
                open = { target ->
                    if (target.name != "drums.wav") target.outputStream()
                    else object : OutputStream() {
                        val output = target.outputStream()
                        var remaining = 128
                        override fun write(value: Int) {
                            if (remaining-- <= 0) throw writeFailure
                            output.write(value)
                        }
                        override fun close() = output.close()
                    }
                },
                discard = { partialSize = it.length(); check(it.delete()) },
            )
            fail("복사 실패가 전파되어야 한다")
        } catch (failure: IOException) {
            assertSame(writeFailure, failure)
        }
        assertEquals(128L, partialSize)
        files.assertOnlyVocalSaved()
    }

    @Test
    fun `스트림 close가 실패해도 현재 파일을 성공으로 세지 않는다`() = runTest {
        val files = Files()
        val closeFailure = IOException("close failed")
        try {
            files.export(open = { target ->
                if (target.name != "drums.wav") target.outputStream()
                else object : OutputStream() {
                    val output = target.outputStream()
                    override fun write(value: Int) = output.write(value)
                    override fun close() {
                        output.close()
                        throw closeFailure
                    }
                }
            })
            fail("close 실패가 전파되어야 한다")
        } catch (failure: IOException) {
            assertSame(closeFailure, failure)
        }
        files.assertOnlyVocalSaved()
    }

    @Test
    fun `목적지 생성 실패를 건너뛰고 저장 완료로 보고하지 않는다`() = runTest {
        val files = Files()
        try {
            files.export(create = { if (it == Stem.DRUMS) null else files.create(it) })
            fail("문서 생성 실패가 전파되어야 한다")
        } catch (failure: IOException) {
            assertEquals("저장 파일을 만들 수 없습니다", failure.message)
        }
        files.assertOnlyVocalSaved()
    }

    @Test
    fun `복사 중 취소하면 현재 문서만 정리하고 다음 스템을 만들지 않는다`() = runTest {
        val files = Files()
        val task = launch {
            val owner = currentCoroutineContext()[Job]!!
            files.export(open = { target ->
                if (target.name != "drums.wav") target.outputStream()
                else object : OutputStream() {
                    val output = target.outputStream()
                    override fun write(value: Int) {
                        output.write(value)
                        owner.cancel()
                    }
                    override fun close() = output.close()
                }
            })
        }
        task.join()
        assertTrue(task.isCancelled)
        files.assertOnlyVocalSaved()
    }

    @Test
    fun `존재하는 스템만 그대로 복사하고 닫힌 파일 개수를 반환한다`() = runTest {
        val files = Files()
        assertTrue(File(files.source, "drums.wav").delete())
        assertEquals(2, files.export())
        assertEquals(listOf(Stem.VOCAL.label, Stem.BASS.label), files.saved)
        for (name in listOf("vocals.wav", "bass.wav")) {
            assertArrayEquals(File(files.source, name).readBytes(), File(files.destination, name).readBytes())
        }
        assertFalse(File(files.destination, "drums.wav").exists())
    }

    private inner class Files {
        val source = temp.newFolder("source")
        val destination = temp.newFolder("destination")
        val saved = mutableListOf<String>()

        init {
            for (stem in listOf(Stem.VOCAL, Stem.DRUMS, Stem.BASS)) {
                File(source, "${stem.fileName}.wav").writeBytes(ByteArray(1024) { (it + stem.ordinal).toByte() })
            }
        }

        fun create(stem: Stem): File = File(destination, "${stem.fileName}.wav").also {
            check(it.createNewFile())
        }

        suspend fun export(
            open: (File) -> OutputStream? = { it.outputStream() },
            create: (Stem) -> File? = ::create,
            discard: suspend (File) -> Unit = { check(it.delete()) },
        ): Int = copyStemFiles(source, create, open, discard) { saved += it }

        fun assertOnlyVocalSaved() {
            assertEquals(listOf(Stem.VOCAL.label), saved)
            assertArrayEquals(File(source, "vocals.wav").readBytes(), File(destination, "vocals.wav").readBytes())
            assertEquals(listOf("vocals.wav"), destination.list()!!.toList())
        }
    }
}
