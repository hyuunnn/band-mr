package com.bandmr.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val uri: String,
    val durationMs: Long,
    val addedAt: Long = System.currentTimeMillis(),
    /** 체크된(제거할) 스템 비트마스크 */
    val muteMask: Int = 0,
    /** -12 ~ +12 반음 */
    val semitones: Int = 0,
    /** 분리 완료 모델 등급. null이면 미분리 */
    val separatedTier: String? = null,
    /** 분리된 스템 wav가 있는 디렉터리 */
    val stemsDir: String? = null,
) {
    /** AI 분리 결과가 존재하여 스템 믹싱이 가능한 상태 */
    val isSeparated: Boolean
        get() = separatedTier != null && stemsDir != null
}
