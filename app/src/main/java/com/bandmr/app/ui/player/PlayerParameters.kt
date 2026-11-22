package com.bandmr.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bandmr.app.audio.PlaybackSpeed
import com.bandmr.app.data.Song
import com.bandmr.app.data.Stem

/** 화면에서 편집하는 값. 저장 중이거나 볼륨 드래그 중이면 Room 행보다 앞서 있을 수 있다. */
internal class PlayerParameters(song: Song?) {
    var stemGainsPacked by mutableLongStateOf(song?.stemGainsPacked ?: Stem.DEFAULT_PACKED)
    var semitones by mutableIntStateOf(song?.semitones ?: 0)
    var speed by mutableFloatStateOf(PlaybackSpeed.snap(song?.speed ?: PlaybackSpeed.DEFAULT))
    var loopStartMs by mutableStateOf(song?.loopStartMs)
    var loopEndMs by mutableStateOf(song?.loopEndMs)
}

/**
 * 곡을 처음 열 때만 저장값으로 초기화한다. 같은 곡의 분리 완료·AI 전환·해제 후 재준비는
 * 현재 편집값으로 엔진을 열어, 아직 저장하지 않은 드래그를 이전 Room 값으로 되돌리지 않는다.
 */
@Composable
internal fun rememberPlayerParameters(
    song: Song?,
    aiOn: Boolean,
    releaseEpoch: Int,
    load: (Song, PlayerParameters) -> Unit,
): PlayerParameters {
    val parameters = remember(song?.id) { PlayerParameters(song) }
    LaunchedEffect(song?.id, song?.separatedTier, song?.stemsDir, aiOn, releaseEpoch) {
        song?.let { load(it, parameters) }
    }
    return parameters
}
