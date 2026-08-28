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
        /** 스템을 원음량으로 유지하는 퍼센트 */
        const val GAIN_FULL = 100

        /**
         * 스템별 유지 퍼센트(0..100)를 바이트 단위로 담은 Long.
         * 하위 바이트부터 [Stem.entries] 순서. 기본은 전부 100.
         */
        const val DEFAULT_PACKED = 0x646464646464L

        fun fromFileName(name: String): Stem? = entries.firstOrNull { it.fileName == name }

        fun packPercents(percents: IntArray): Long {
            var packed = 0L
            for (i in entries.indices) {
                val p = percents.getOrElse(i) { GAIN_FULL }.coerceIn(0, GAIN_FULL).toLong()
                packed = packed or (p shl (i * 8))
            }
            return packed
        }

        fun unpackPercents(packed: Long): IntArray =
            IntArray(entries.size) { i ->
                ((packed ushr (i * 8)) and 0xFF).toInt().coerceIn(0, GAIN_FULL)
            }

        fun percentOf(packed: Long, stem: Stem): Int =
            ((packed ushr (stem.ordinal * 8)) and 0xFF).toInt().coerceIn(0, GAIN_FULL)

        fun withPercent(packed: Long, stem: Stem, percent: Int): Long {
            val p = percent.coerceIn(0, GAIN_FULL).toLong()
            val shift = stem.ordinal * 8
            return (packed and (0xFFL shl shift).inv()) or (p shl shift)
        }

        fun packedFromMuteMask(muteMask: Int): Long {
            val percents = IntArray(entries.size) { i ->
                if (muteMask and entries[i].bit != 0) 0 else GAIN_FULL
            }
            return packPercents(percents)
        }

        /** 0%인 스템만 제거 비트로 켠다 (AI OFF 체크박스와 동기화) */
        fun muteMaskFromPacked(packed: Long): Int {
            var mask = 0
            entries.forEach { stem ->
                if (percentOf(packed, stem) == 0) mask = mask or stem.bit
            }
            return mask
        }

        fun gainArrayFromPacked(packed: Long): FloatArray {
            val percents = unpackPercents(packed)
            return FloatArray(entries.size) { i -> percents[i] / GAIN_FULL.toFloat() }
        }
    }
}
