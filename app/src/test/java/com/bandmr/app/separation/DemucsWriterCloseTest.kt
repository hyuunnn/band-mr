package com.bandmr.app.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.IOException

/**
 * 스템 WAV 닫기 실패(남은 버퍼 flush·헤더 패치)는 분리 실패여야 한다. 삼키면 잘린 스템이
 * 승격되어 분리 완료로 기록된다. 한 스템이 실패해도 나머지는 닫아 핸들을 남기지 않는다.
 */
class DemucsWriterCloseTest {

    private class Writer(private val failure: IOException? = null) : Closeable {
        var closed = 0
        override fun close() {
            closed++
            failure?.let { throw it }
        }
    }

    @Test
    fun `닫기 실패는 전부 닫은 뒤 첫 실패로 전파한다`() {
        val first = IOException("vocals")
        val writers = listOf(Writer(), Writer(first), Writer(IOException("bass")), Writer())
        try {
            DemucsSeparator.closeWriters(writers, rethrow = true)
            fail("닫기 실패를 삼키면 안 된다")
        } catch (thrown: IOException) {
            assertSame(first, thrown)
        }
        assertEquals(listOf(1, 1, 1, 1), writers.map { it.closed })
    }

    @Test
    fun `앞선 예외가 있으면 닫기 실패로 가리지 않는다`() {
        val writers = listOf(Writer(IOException("vocals")), Writer())
        DemucsSeparator.closeWriters(writers, rethrow = false)
        assertEquals(listOf(1, 1), writers.map { it.closed })
    }
}
