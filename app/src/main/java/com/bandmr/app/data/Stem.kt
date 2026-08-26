package com.bandmr.app.data

/**
 * 분리/제거 대상 스템. AI 모델(htdemucs_6s) 출력 파일명과 대응된다.
 * [aiOnly]=true인 스템은 AI OFF 실시간 DSP로는 근사조차 어려워 AI 분리 전용.
 */
enum class Stem(
    val fileName: String,
    val label: String,
    val dspHint: String,
    val aiOnly: Boolean = false,
) {
    VOCAL("vocals", "보컬", "중앙 성분 마스킹 (저역 보존)"),
    DRUMS("drums", "드럼", "HPSS 타악 억제"),
    BASS("bass", "베이스", "f0 배음 노칭 + 하이패스"),
    GUITAR("guitar", "기타", "중역대 손실 (실험적)"),
    PIANO("piano", "피아노/키보드", "AI 분리 전용", aiOnly = true),
    OTHER("other", "그 외 반주 (신스 등)", "AI 분리 전용", aiOnly = true);

    val bit: Int get() = 1 shl ordinal

    companion object {
        fun fromFileName(name: String): Stem? = entries.firstOrNull { it.fileName == name }

        /** 제거 마스크에 대응하는 스템별 게인 (제거=0, 유지=1) */
        fun gainArray(muteMask: Int): FloatArray =
            FloatArray(entries.size) { i -> if (muteMask and entries[i].bit != 0) 0f else 1f }
    }
}
