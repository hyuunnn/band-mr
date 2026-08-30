package com.bandmr.app.separation

import kotlinx.coroutines.flow.MutableStateFlow

sealed interface SepState {
    data object Idle : SepState
    data class Running(val songId: Long, val stage: String, val progress: Float) : SepState
    data class Error(val songId: Long, val message: String) : SepState
}

/**
 * 서비스와 UI 사이의 상태 버스.
 *
 * 의미는 "마지막 분리 시도의 결과"다 — 진행 중이면 [SepState.Running], 실패면 [SepState.Error].
 * 성공은 [SepState.Idle]로 되돌린다(완료 상태를 따로 두지 않는다 — 분리 완료 여부는
 * `Song.isSeparated`가 갖고 있어서 별도 신호가 필요 없다).
 *
 * 오류는 다음 시도(Running)나 성공(Idle)까지 남는다. 화면 진입/이탈로 지우면,
 * 분리가 몇 분 걸리는 동안 사용자가 다른 화면에 있다가 돌아왔을 때 실패 이유를 못 보게 된다.
 * 곡 구분은 [SepState.Error.songId]로 하므로 다른 곡 화면에 남의 오류가 보이지는 않는다.
 */
object SepBus {
    val state = MutableStateFlow<SepState>(SepState.Idle)
}
