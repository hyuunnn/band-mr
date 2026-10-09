package com.bandmr.app.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubeImportStateTest {
    private lateinit var main: TestDispatcher
    private lateinit var importMutex: Mutex
    private var holdsMutex = false
    private val url = "https://youtu.be/dQw4w9WgXcQ"

    @Before
    fun setUp() {
        main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        YouTubeImport.cancel()
        main.scheduler.runCurrent()
        // 취소된 이전 작업이 read/정리 중인 조건을 만든다. 새 작업이 이 락을 기다리므로
        // 실제 start/cancel과 StateFlow를 검증하면서 Android·추출기·네트워크를 실행하지 않는다.
        importMutex = YouTubeImport::class.java.getDeclaredField("importMutex").run {
            isAccessible = true
            get(null) as Mutex
        }
        holdsMutex = importMutex.tryLock()
        assertTrue(holdsMutex)
    }

    @After
    fun tearDown() {
        try {
            YouTubeImport.cancel()
            main.scheduler.runCurrent()
        } finally {
            if (holdsMutex) importMutex.unlock()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `재시도가 이전 정리를 기다려도 시작 즉시 화면 상태를 발행한다`() {
        val states = mutableListOf<ImportState>()
        val observer = CoroutineScope(UnconfinedTestDispatcher(main.scheduler)).launch {
            YouTubeImport.state.collect { states += it }
        }
        try {
            assertTrue(YouTubeImport.start(url))
            assertTrue(YouTubeImport.isRunning())
            assertEquals(listOf(ImportState.Idle, ImportState.Resolving), states)
            main.scheduler.runCurrent()
            assertEquals(ImportState.Resolving, YouTubeImport.state.value)
            assertFalse(YouTubeImport.start(url))
            assertEquals(2, states.size)

            YouTubeImport.cancel()
            main.scheduler.runCurrent()
            assertFalse(YouTubeImport.isRunning())
            assertEquals(ImportState.Idle, states.last())
            assertTrue(importMutex.isLocked)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun `대기 재시도를 취소하고 다시 시작해도 진행 상태를 발행한다`() {
        assertTrue(YouTubeImport.start(url))
        main.scheduler.runCurrent()
        YouTubeImport.cancel()
        main.scheduler.runCurrent()
        assertEquals(ImportState.Idle, YouTubeImport.state.value)

        assertTrue(YouTubeImport.start(url))
        main.scheduler.runCurrent()
        assertTrue(YouTubeImport.isRunning())
        assertEquals(ImportState.Resolving, YouTubeImport.state.value)
    }

    @Test
    fun `대기 중 다이얼로그를 닫아도 진행 상태를 지우지 않는다`() {
        assertTrue(YouTubeImport.start(url))
        main.scheduler.runCurrent()
        YouTubeImport.dismiss()
        assertTrue(YouTubeImport.isRunning())
        assertEquals(ImportState.Resolving, YouTubeImport.state.value)
    }

    @Test
    fun `빈 입력은 시작 상태를 발행하지 않는다`() {
        assertFalse(YouTubeImport.start("  "))
        assertFalse(YouTubeImport.isRunning())
        assertEquals(ImportState.Idle, YouTubeImport.state.value)
    }
}
