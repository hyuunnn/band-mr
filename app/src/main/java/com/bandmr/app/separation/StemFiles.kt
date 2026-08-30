package com.bandmr.app.separation

import android.content.Context
import java.io.File

/**
 * 분리 스템의 저장 위치. 경로를 아는 곳이 여기 하나여야 한다 —
 * 분리(쓰기)·고아 정리·용량 집계·비우기가 모두 같은 규칙을 봐야 어긋나지 않는다.
 *
 * `<filesDir>/stems/<songId>` 가 정식 결과이고 `<songId>.part`가 진행 중 산출물이다.
 * 승격은 [com.bandmr.app.io.FilePromote]가 담당한다(성공 시에만 정식 이름으로 공개).
 */
object StemFiles {

    private const val DIR = "stems"

    /** 스템 루트 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 곡의 정식 스템 디렉터리 */
    fun songDir(context: Context, songId: Long): File = File(dir(context), "$songId")

    /** 분리 진행 중 산출물. 성공 시에만 [songDir]로 승격한다 */
    fun partDir(context: Context, songId: Long): File = File(dir(context), "$songId.part")
}
