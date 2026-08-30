package com.bandmr.app.io

import java.io.File

/**
 * 임시 산출물(`.part`/`.tmp`)을 정식 경로로 승격한다.
 *
 * 이 앱의 모든 생성물(MixCache WAV, 분리 스템 디렉터리, 모델 파일, 유튜브 원본)은
 * "다 만들어진 뒤에만 정식 이름으로 공개한다"는 같은 규약을 따른다 — 중간에 취소/실패해도
 * 부분 파일이 정식 파일로 보이면 안 되기 때문이다. 그 마지막 단계를 한곳에 모았다.
 *
 * rename만 쓰지 않는 이유: 목적지가 이미 있거나 같은 마운트인데도 `renameTo`가 false를
 * 돌려주는 기기가 있어서, 실패하면 복사로 대체하고 원본을 지운다.
 *
 * **복사 폴백이 도중에 실패하면 목적지를 반드시 지운다.** rename은 원자적이라 "절반만 옮겨진
 * 결과"가 없지만 복사는 있다. 이 앱은 파일 존재 여부를 곧 완성 신호로 쓰므로(예:
 * `MixCache.cacheFile().exists()`), 잘린 결과를 남기면 손상된 캐시가 재생에 쓰인다.
 *
 * 규약 밖: 표시 전용 캐시(`WaveformPeaks`의 `.peaks`)는 이 함수를 쓰지 않는다. 실패해도 재생에
 * 지장이 없어 **예외를 던지면 안 되고**(호출부가 파형 없이 계속 가야 한다), 1.9KB짜리 재생성
 * 가능한 파일에 copy 폴백 비용을 들일 이유도 없다. 그래서 그쪽은 rename 실패 시 조용히 포기한다.
 */
object FilePromote {

    /**
     * [part] 파일을 [dest]로 옮긴다. 복사 폴백이 실패하면 [dest]를 지우고 예외를 그대로 던진다.
     * @throws IllegalStateException 성공을 보고했는데 목적지가 없으면
     */
    fun file(part: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            try {
                part.copyTo(dest, overwrite = true)
            } catch (t: Throwable) {
                dest.delete() // 잘린 파일을 완성본으로 공개하지 않는다
                throw t
            }
            part.delete()
        }
        // "유효한 최소 크기"는 호출부 책임이다 — 기준이 호출부마다 다르다(WAV는 프레임 수,
        // 모델은 SHA-256, 유튜브 원본은 바이트 수). 여기서 그것까지 판단하면 이 함수가
        // 호출부의 포맷을 알아야 한다.
        //
        // 그와 별개로 **0바이트는 본다.** 이건 호출부 입력이 아니라 파일시스템이 옮겼다고
        // 보고했는데 결과가 비어 있는 경우다(위 KDoc대로 `renameTo`의 반환값을 못 믿는 기기가
        // 있어 copy 폴백을 두고 있다 — false를 못 믿으면 true도 못 믿는다). 이 앱은 파일 존재를
        // 완성 신호로 쓰므로 빈 껍데기를 남기면 손상 캐시가 재생에 쓰인다.
        if (!dest.exists() || dest.length() <= 0L) {
            dest.delete()
            error("파일 이동 실패: $part → $dest")
        }
    }

    /**
     * [part] 디렉터리를 [dest]로 옮긴다. 복사 폴백이 실패하면 [dest]를 지우고 예외를 그대로 던진다
     * (스템 일부만 있는 디렉터리가 남으면 분리 완료로 오인될 수 있다).
     */
    fun directory(part: File, dest: File) {
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!part.renameTo(dest)) {
            try {
                part.copyRecursively(dest, overwrite = true)
            } catch (t: Throwable) {
                dest.deleteRecursively()
                throw t
            }
            part.deleteRecursively()
        }
        check(dest.isDirectory) { "디렉터리 이동 실패: $part → $dest" }
    }
}
