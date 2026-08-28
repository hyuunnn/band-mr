package com.bandmr.app.separation

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE

/**
 * 다운로드 가능한 온디바이스 분리 모델 등급.
 *
 * 모델 파일은 htdemucs_6s(Demucs v4, 6스템)를 ONNX로 변환해 이 저장소의
 * GitHub Releases(model-v2)에 호스팅한다. 모델을 다시 업로드하면
 * SHA-256 해시 3개를 반드시 갱신할 것 (tools/export_demucs_onnx.py가 해시 출력).
 */
enum class Tier(
    val id: String,
    val label: String,
    val description: String,
    val approxSizeMb: Int,
    /** 추론 세그먼트 길이(샘플). 메모리 사용량과 비례 */
    val segmentSamples: Int,
    val url: String,
    /**
     * 모델 파일 SHA-256 해시(64자 hex). 다운로드 후 무결성 검증에 사용.
     * null이면 크기 기반의 느슨한 검증만 수행.
     */
    val sha256: String? = null,
) {
    LIGHT(
        "light", "경량 우선",
        "세그먼트가 짧아 빠르고 저발열. 품질은 보통",
        178, 131_072,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v2/htdemucs6s-light-fp32.onnx",
        "18b07c93b957b849e8dadae29ef37ad719fe06a54dde350a44cec3907c31596c",
    ),
    BALANCED(
        "balanced", "균형형",
        "속도와 품질의 균형 (권장)",
        178, 262_144,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v2/htdemucs6s-balanced-fp32.onnx",
        "fd5ae237bd6ade5589323e71b5ba47a19d0e2d1f2e1da0f0819a8328e308c477",
    ),
    QUALITY(
        "quality", "품질 우선",
        "긴 세그먼트로 최고 품질. 시간·메모리 많이 사용",
        178, 344_064,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v2/htdemucs6s-quality-fp32.onnx",
        "d9f222f4015c720408e368b49b54982b5ba437c22cfc94dda370a438c8ceadc7",
    );

    companion object {
        fun fromId(id: String?): Tier = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

/** 모델 입출력 규격 */
data class ModelConfig(
    val sampleRate: Int = PIPELINE_SAMPLE_RATE,
    /** htdemucs_6s 스템 출력 순서 (export 로그의 model.sources와 일치해야 함) */
    val stemOrder: List<String> = listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
)
