package com.bandmr.app.separation

import kotlinx.coroutines.flow.MutableStateFlow

sealed interface SepState {
    data object Idle : SepState
    data class Running(val songId: Long, val stage: String, val progress: Float) : SepState
    data object Done : SepState
    data class Error(val songId: Long, val message: String) : SepState
}

/** 서비스와 UI 사이의 상태 버스 */
object SepBus {
    val state = MutableStateFlow<SepState>(SepState.Idle)
}
