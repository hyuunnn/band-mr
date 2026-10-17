package com.bandmr.app.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackChangesTest {
    @Test
    fun `재생 중 제목부터 비워도 마지막 재생 상태 변경으로 해제를 관찰한다`() = runTest {
        val playing = MutableStateFlow(true)
        val title = MutableStateFlow<String?>("song")
        val observed = mutableListOf<Pair<String?, Boolean>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPlaybackChanges(playing, title) { observed += title.value to playing.value }
        }
        runCurrent()
        title.value = null
        runCurrent()
        assertEquals(null to true, observed.last())
        playing.value = false
        runCurrent()
        assertEquals(null to false, observed.last())
    }

    @Test
    fun `재생부터 멈추거나 두 값을 연달아 비워도 최종 해제를 관찰한다`() = runTest {
        for (separateUpdates in listOf(true, false)) {
            val playing = MutableStateFlow(true)
            val title = MutableStateFlow<String?>("song")
            val observed = mutableListOf<Pair<String?, Boolean>>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                collectPlaybackChanges(playing, title) { observed += title.value to playing.value }
            }
            runCurrent()
            playing.value = false
            if (separateUpdates) runCurrent()
            title.value = null
            runCurrent()
            assertEquals(null to false, observed.last())
            observer.cancel()
        }
    }
}
