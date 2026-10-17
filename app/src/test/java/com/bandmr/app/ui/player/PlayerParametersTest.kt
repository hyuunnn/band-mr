package com.bandmr.app.ui.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.bandmr.app.data.Song
import com.bandmr.app.data.Stem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerParametersTest {
    @Test
    fun `재분리 완료 시 아직 저장하지 않은 드래그 볼륨으로 엔진을 다시 연다`() = runTest {
        withPlayer { player ->
            val edited = Stem.withPercent(Stem.DEFAULT_PACKED, Stem.VOCAL, 37)
            player.parameters.stemGainsPacked = edited
            player.song.value = player.song.value!!.copy(separatedTier = "4s-balanced")
            player.recompose()

            assertEquals(edited, player.parameters.stemGainsPacked)
            assertEquals(edited, player.loads.last().packed)
            assertEquals(Stem.DEFAULT_PACKED, player.song.value!!.stemGainsPacked)
        }
    }

    @Test
    fun `같은 곡의 AI 전환과 해제 후 재준비는 현재 편집값을 유지한다`() = runTest {
        withPlayer { player ->
            val edited = Stem.withPercent(Stem.DEFAULT_PACKED, Stem.DRUMS, 22)
            player.parameters.stemGainsPacked = edited
            player.parameters.semitones = 5
            player.parameters.speed = 0.75f
            player.parameters.loopStartMs = 1000L
            player.parameters.loopEndMs = 5000L
            player.aiOn.value = false
            player.recompose()
            player.releaseEpoch.intValue++
            player.recompose()

            assertEquals(3, player.loads.size)
            for (load in player.loads.drop(1)) {
                assertEquals(edited, load.packed)
                assertEquals(5, load.semitones)
                assertEquals(0.75f, load.speed)
                assertEquals(1000L, load.loopStartMs)
                assertEquals(5000L, load.loopEndMs)
            }
        }
    }

    @Test
    fun `새 곡은 이전 편집값을 물려받지 않고 자기 저장값으로 연다`() = runTest {
        withPlayer { player ->
            player.parameters.stemGainsPacked = 0L
            player.parameters.semitones = 7
            player.parameters.speed = 1.5f
            player.parameters.loopStartMs = 2000L
            player.parameters.loopEndMs = 9000L
            val packed = Stem.withPercent(Stem.DEFAULT_PACKED, Stem.BASS, 63)
            player.song.value = song(id = 2).copy(stemGainsPacked = packed, semitones = -3, speed = 0.8f)
            player.recompose()

            assertEquals(2, player.loads.size)
            val load = player.loads.last()
            assertEquals(2L, load.songId)
            assertEquals(packed, load.packed)
            assertEquals(-3, load.semitones)
            assertEquals(0.8f, load.speed)
            assertNull(load.loopStartMs)
            assertNull(load.loopEndMs)
        }
    }

    @Test
    fun `첫 Room 행이 도착하면 저장값으로 초기화하고 경로만 바뀌어도 현재값으로 다시 연다`() = runTest {
        withPlayer(initial = null) { player ->
            assertTrue(player.loads.isEmpty())
            val packed = Stem.withPercent(Stem.DEFAULT_PACKED, Stem.GUITAR, 45)
            player.song.value = song().copy(stemGainsPacked = packed)
            player.recompose()
            assertEquals(packed, player.loads.single().packed)

            player.parameters.stemGainsPacked = 0L
            player.song.value = player.song.value!!.copy(stemsDir = "/stems/replaced")
            player.recompose()
            assertEquals(2, player.loads.size)
            assertEquals(0L, player.loads.last().packed)
        }
    }

    private suspend fun TestScope.withPlayer(initial: Song? = song(), test: suspend (Player) -> Unit) {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(System.nanoTime())
        }
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val player = Player(this, initial)
        try {
            composition.setContent {
                player.parameters = rememberPlayerParameters(
                    player.song.value, player.aiOn.value, player.releaseEpoch.intValue,
                ) { song, current ->
                    player.loads += Load(
                        song.id, current.stemGainsPacked, current.semitones,
                        current.speed, current.loopStartMs, current.loopEndMs,
                    )
                }
            }
            player.recompose()
            test(player)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.join()
        }
    }

    private class Player(val scope: TestScope, initial: Song?) {
        val song = mutableStateOf(initial)
        val aiOn = mutableStateOf(true)
        val releaseEpoch = mutableIntStateOf(0)
        lateinit var parameters: PlayerParameters
        val loads = mutableListOf<Load>()

        fun recompose() {
            Snapshot.sendApplyNotifications()
            scope.runCurrent()
        }
    }

    private data class Load(
        val songId: Long,
        val packed: Long,
        val semitones: Int,
        val speed: Float,
        val loopStartMs: Long?,
        val loopEndMs: Long?,
    )

    private fun song(id: Long = 1) = Song(
        id = id, title = "test", uri = "file:///song.wav", durationMs = 60_000,
        separatedTier = "balanced", stemsDir = "/stems/$id",
    )
}
