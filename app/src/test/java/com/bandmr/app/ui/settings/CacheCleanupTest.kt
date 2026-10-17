package com.bandmr.app.ui.settings

import com.bandmr.app.audio.SourcePreparation
import com.bandmr.app.separation.AudioDecode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CacheCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 10_000)
    fun `사전 변환의 디코딩 취소와 임시 파일 정리가 끝나야 캐시를 삭제한다`() = runBlocking {
        val dir = temp.newFolder("mixcache")
        val cached = File(dir, "1.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val part = File(dir, "2.wav.part")
        val enteredDecode = CompletableDeferred<Unit>()
        val resumeDecode = CountDownLatch(1)
        var promoted = false
        var released = false
        val preCache = launch(Dispatchers.IO) {
            val decodeContext = currentCoroutineContext()
            try {
                part.writeBytes(byteArrayOf(4, 5))
                enteredDecode.complete(Unit)
                AudioDecode.decodeUntilEos({ decodeContext.ensureActive() }) {
                    check(resumeDecode.await(5, TimeUnit.SECONDS))
                    true
                }
                promoted = true
                part.renameTo(File(dir, "2.wav"))
            } finally {
                part.delete()
            }
        }
        enteredDecode.await()
        val clearing = async(start = CoroutineStart.UNDISPATCHED) {
            clearMixCache(preCache, dir) { released = true }
        }
        try {
            assertTrue(preCache.isCancelled)
            assertFalse(clearing.isCompleted)
            assertTrue(cached.exists())
            assertTrue(part.exists())

            resumeDecode.countDown()
            assertEquals(3L, clearing.await())
            assertTrue(preCache.isCompleted)
            assertTrue(released)
            assertFalse(promoted)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            resumeDecode.countDown()
            clearing.cancelAndJoin()
            preCache.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun `재생 준비가 곡 잠금을 잡고 있어도 양쪽을 취소한 뒤 캐시를 삭제한다`() = runBlocking {
        val dir = temp.newFolder("mixcache")
        val cached = File(dir, "1.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val part = File(dir, "2.wav.part")
        val songLock = Any()
        val enteredDecode = CompletableDeferred<Job>()
        val enteredPreCache = CompletableDeferred<Unit>()
        val resumeDecode = CountDownLatch(1)
        val preparation = SourcePreparation(this, Dispatchers.IO, Dispatchers.Unconfined)
        var callbackCalled = false
        preparation.start({
            val decodeContext = currentCoroutineContext()
            synchronized(songLock) {
                try {
                    part.writeBytes(byteArrayOf(4, 5))
                    enteredDecode.complete(decodeContext[Job]!!)
                    AudioDecode.decodeUntilEos({ decodeContext.ensureActive() }) {
                        check(resumeDecode.await(5, TimeUnit.SECONDS))
                        true
                    }
                    check(part.renameTo(File(dir, "2.wav")))
                } finally {
                    part.delete()
                }
            }
        }) { callbackCalled = true }
        val playbackJob = enteredDecode.await()
        val preCache = launch(Dispatchers.IO) {
            val preCacheContext = currentCoroutineContext()
            enteredPreCache.complete(Unit)
            synchronized(songLock) {
                preCacheContext.ensureActive()
                error("취소한 사전 변환은 디코딩을 시작하면 안 된다")
            }
        }
        enteredPreCache.await()
        val clearing = async(start = CoroutineStart.UNDISPATCHED) {
            clearMixCache(preCache, dir) { preparation.cancelAndJoin() }
        }
        try {
            assertTrue(preCache.isCancelled)
            assertTrue(playbackJob.isCancelled)
            assertFalse(clearing.isCompleted)
            assertTrue(cached.exists())
            assertTrue(part.exists())

            resumeDecode.countDown()
            assertEquals(3L, clearing.await())
            assertTrue(playbackJob.isCompleted)
            assertTrue(preCache.isCompleted)
            assertFalse(callbackCalled)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            resumeDecode.countDown()
            clearing.cancelAndJoin()
            preparation.cancelAndJoin()
            preCache.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun `캐시 비우기는 곡 추가 같은 다른 작업을 취소하거나 승격을 막지 않는다`() = runBlocking {
        val dir = temp.newFolder("mixcache")
        File(dir, "1.wav").writeBytes(byteArrayOf(1, 2))
        val part = File(dir, "2.wav.part").apply { writeBytes(byteArrayOf(3, 4)) }
        val finishImport = CompletableDeferred<Unit>()
        val imported = File(dir, "2.wav")
        val importing = launch(start = CoroutineStart.UNDISPATCHED) {
            finishImport.await()
            check(part.renameTo(imported))
        }
        val preCache = Job()
        try {
            assertEquals(2L, clearMixCache(preCache, dir) {})
            assertTrue(preCache.isCancelled)
            assertTrue(importing.isActive)
            assertTrue(part.exists())

            finishImport.complete(Unit)
            importing.join()
            assertArrayEquals(byteArrayOf(3, 4), imported.readBytes())
        } finally {
            finishImport.complete(Unit)
            importing.cancelAndJoin()
        }
    }

    @Test
    fun `사전 변환 작업이 없어도 재생을 해제하고 WAV와 파형을 지운다`() = runBlocking {
        val dir = temp.newFolder("mixcache")
        val wav = File(dir, "1.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(dir, "1.peaks").writeBytes(byteArrayOf(4, 5))
        var released = false

        val freed = clearMixCache(null, dir) {
            assertTrue(wav.exists())
            released = true
        }

        assertTrue(released)
        assertEquals(5L, freed)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
