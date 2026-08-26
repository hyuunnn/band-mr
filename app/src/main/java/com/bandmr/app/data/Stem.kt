package com.bandmr.app.data

/** 분리/제거 대상 스템. AI 모델(Demucs) 출력 파일명과 대응된다. */
enum class Stem(val fileName: String, val label: String, val dspHint: String) {
    VOCAL("vocals", "보컬", "중앙 성분 마스킹 (저역 보존)"),
    DRUMS("drums", "드럼", "HPSS 타악 억제"),
    BASS("bass", "베이스", "f0 배음 노칭 + 하이패스"),
    GUITAR("other", "기타/키보드", "중역대 손실 (실험적)");

    val bit: Int get() = 1 shl ordinal

    companion object {
        fun fromFileName(name: String): Stem? = entries.firstOrNull { it.fileName == name }

        /** 제거 마스크에 대응하는 스템별 게인 (제거=0, 유지=1) */
        fun gainArray(muteMask: Int): FloatArray =
            FloatArray(entries.size) { i -> if (muteMask and entries[i].bit != 0) 0f else 1f }
    }
}
