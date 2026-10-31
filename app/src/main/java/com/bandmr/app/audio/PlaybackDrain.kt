package com.bandmr.app.audio

/**
 * AudioTrack에 쓴 스테레오 프레임과 실제 재생 헤드의 차이. 접근은 엔진의 stateLock 안에서 한다.
 * flush는 헤드를 0으로 되돌리므로 출력 수와 세대도 함께 초기화한다. 이전 세대의 렌더·종료
 * 대기는 시크/일시정지 뒤의 출력에 섞지 않는다. 헤드는 unsigned 32bit로 약 27시간마다 돈다.
 */
internal class PlaybackDrain {
    @Volatile
    var epoch = 0L
        private set
    private var writtenFrames = 0L

    fun reset() {
        epoch++
        writtenFrames = 0L
    }

    fun recordWrite(shorts: Int) {
        writtenFrames += shorts / 2
    }

    fun pendingFrames(playbackHead: Int): Long =
        (writtenFrames - (playbackHead.toLong() and 0xFFFF_FFFFL)) and 0xFFFF_FFFFL
}
