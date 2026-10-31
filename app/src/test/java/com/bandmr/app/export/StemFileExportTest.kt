package com.bandmr.app.export

import com.bandmr.app.data.Stem
import com.bandmr.app.io.FilePromote
import com.bandmr.app.separation.withStemMutation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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

    @Test
    fun `4스템 저장 중 6스템 재분리가 끝나도 처음 연 4스템을 그대로 저장한다`() = runTest {
        val files = Files()
        File(files.source, "other.wav").writeBytes(byteArrayOf(7, 8, 9))
        val previous = files.source.listFiles()!!.associate { it.name to it.readBytes() }
        val next = temp.newFolder("next.part")
        Stem.entries.forEach { File(next, "${it.fileName}.wav").writeBytes(byteArrayOf(99)) }

        val count = files.export(onEach = {
            if (files.saved.size == 1) {
                val promotion = launch(start = CoroutineStart.UNDISPATCHED) {
                    withStemMutation { FilePromote.directory(next, files.source) }
                }
                assertTrue("출력 복사 중에는 확정 잠금을 잡고 있으면 안 된다", promotion.isCompleted)
            }
        })

        assertEquals(4, count)
        assertEquals(previous.keys, files.destination.list()!!.toSet())
        for ((name, bytes) in previous) assertArrayEquals(bytes, File(files.destination, name).readBytes())
        assertArrayEquals(byteArrayOf(99), File(files.source, "vocals.wav").readBytes())
    }

    @Test
    fun `입력 열기에 실패하면 먼저 연 입력을 모두 닫고 출력은 만들지 않는다`() = runTest {
        val files = Files()
        val closed = mutableSetOf<String>()
        try {
            files.export(openSource = { file ->
                if (file.name == "bass.wav") throw IOException("source open failed")
                object : FilterInputStream(file.inputStream()) {
                    override fun close() { super.close(); closed += file.name }
                }
            })
            fail("입력 열기 실패가 전파되어야 한다")
        } catch (failure: IOException) {
            assertEquals("source open failed", failure.message)
        }
        assertEquals(setOf("vocals.wav", "drums.wav"), closed)
        assertTrue(files.destination.list()!!.isEmpty())
    }

    @Test
    fun `출력 열기에 실패해도 아직 복사하지 않은 입력까지 모두 닫는다`() = runTest {
        val files = Files()
        val closed = mutableSetOf<String>()
        try {
            files.export(
                open = { if (it.name == "drums.wav") null else it.outputStream() },
                openSource = { file ->
                    object : FilterInputStream(file.inputStream()) {
                        override fun close() { super.close(); closed += file.name }
                    }
                },
            )
            fail("출력 열기 실패가 전파되어야 한다")
        } catch (_: IOException) { }
        assertEquals(setOf("vocals.wav", "drums.wav", "bass.wav"), closed)
        files.assertOnlyVocalSaved()
    }

    @Test
    fun `입력 close 실패는 빈 문서를 지우고 원래 오류를 보존한다`() = runTest {
        val files = Files()
        val closeFailure = IOException("source close failed")
        try {
            files.export(openSource = { file ->
                object : FilterInputStream(file.inputStream()) {
                    override fun close() {
                        super.close()
                        if (file.name == "vocals.wav") throw closeFailure
                    }
                }
            })
            fail("입력 close 실패가 전파되어야 한다")
        } catch (failure: IOException) {
            assertSame(closeFailure, failure)
        }
        assertTrue(files.saved.isEmpty())
        assertTrue(files.destination.list()!!.isEmpty())
    }

    @Test
    fun `입력 확보 중 취소되어도 열린 입력을 모두 닫고 출력은 만들지 않는다`() = runTest {
        val files = Files()
        val closed = mutableSetOf<String>()
        val task = launch {
            val owner = currentCoroutineContext()[Job]!!
            files.export(openSource = { file ->
                if (file.name == "vocals.wav") owner.cancel()
                object : FilterInputStream(file.inputStream()) {
                    override fun close() { super.close(); closed += file.name }
                }
            })
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(setOf("vocals.wav", "drums.wav", "bass.wav"), closed)
        assertTrue(files.destination.list()!!.isEmpty())
    }

    @Test
    fun `6스템 저장 중 4스템으로 교체해도 삭제된 기타 피아노까지 원래 결과로 저장한다`() = runTest {
        val files = Files()
        for (stem in listOf(Stem.GUITAR, Stem.PIANO, Stem.OTHER)) {
            File(files.source, "${stem.fileName}.wav").writeBytes(byteArrayOf(stem.ordinal.toByte()))
        }
        val previous = files.source.listFiles()!!.associate { it.name to it.readBytes() }
        val next = temp.newFolder("four.part")
        for (stem in listOf(Stem.VOCAL, Stem.DRUMS, Stem.BASS, Stem.OTHER)) {
            File(next, "${stem.fileName}.wav").writeBytes(byteArrayOf(99))
        }

        val count = files.export(onEach = {
            if (files.saved.size == 1) {
                val promotion = launch(start = CoroutineStart.UNDISPATCHED) {
                    withStemMutation { FilePromote.directory(next, files.source) }
                }
                assertTrue(promotion.isCompleted)
            }
        })

        assertEquals(6, count)
        assertFalse(File(files.source, "guitar.wav").exists())
        assertFalse(File(files.source, "piano.wav").exists())
        for ((name, bytes) in previous) assertArrayEquals(bytes, File(files.destination, name).readBytes())
    }

    @Test
    fun `교체가 확정 중이면 끝날 때까지 입력을 열지 않고 새 결과 전체를 확보한다`() = runTest {
        val files = Files()
        val next = temp.newFolder("next.part")
        for (stem in listOf(Stem.VOCAL, Stem.DRUMS, Stem.BASS)) {
            File(next, "${stem.fileName}.wav").writeBytes(byteArrayOf(99))
        }
        val locked = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val promotion = launch(start = CoroutineStart.UNDISPATCHED) {
            withStemMutation {
                locked.complete(Unit)
                finish.await()
                FilePromote.directory(next, files.source)
            }
        }
        locked.await()
        var opened = 0
        val exporting = async(start = CoroutineStart.UNDISPATCHED) {
            files.export(openSource = { opened++; it.inputStream() })
        }
        try {
            assertEquals(0, opened)
            assertFalse(exporting.isCompleted)
            finish.complete(Unit)
            assertEquals(3, exporting.await())
            for (file in files.destination.listFiles()!!) assertArrayEquals(byteArrayOf(99), file.readBytes())
        } finally {
            finish.complete(Unit)
            promotion.join()
            exporting.cancelAndJoin()
        }
    }

    @Test
    fun `저장 도중 원본 스템 폴더를 삭제해도 이미 확보한 입력으로 저장을 마친다`() = runTest {
        val files = Files()
        val previous = files.source.listFiles()!!.associate { it.name to it.readBytes() }
        val count = files.export(onEach = {
            if (files.saved.size == 1) {
                val deletion = launch(start = CoroutineStart.UNDISPATCHED) {
                    withStemMutation { check(files.source.deleteRecursively()) }
                }
                assertTrue(deletion.isCompleted)
            }
        })
        assertEquals(3, count)
        assertFalse(files.source.exists())
        for ((name, bytes) in previous) assertArrayEquals(bytes, File(files.destination, name).readBytes())
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
            openSource: (File) -> InputStream = { it.inputStream() },
            onEach: (String) -> Unit = {},
        ): Int = copyStemFiles(source, create, open, discard, openSource) { saved += it; onEach(it) }

        fun assertOnlyVocalSaved() {
            assertEquals(listOf(Stem.VOCAL.label), saved)
            assertArrayEquals(File(source, "vocals.wav").readBytes(), File(destination, "vocals.wav").readBytes())
            assertEquals(listOf("vocals.wav"), destination.list()!!.toList())
        }
    }
}
