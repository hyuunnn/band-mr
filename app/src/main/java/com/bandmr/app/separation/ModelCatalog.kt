package com.bandmr.app.separation

/**
 * 다운로드 가능한 온디바이스 분리 모델 등급.
 *
 * 모델 파일은 htdemucs(Demucs v4)를 ONNX로 변환한 것으로 가정한다.
 * URL은 예시이므로 README의 안내에 따라 직접 호스팅한 주소로 교체할 것.
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
        236, 131_072,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v1/htdemucs-light-fp32.onnx",
        "d486eb86ad20de8df6e7da6704438861e4fa671463753c907aee4b4cf295a0b2",
    ),
    BALANCED(
        "balanced", "균형형",
        "속도와 품질의 균형 (권장)",
        236, 262_144,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v1/htdemucs-balanced-fp32.onnx",
        "294309e0fc580d82c67ceaf338442645d66616a27064b4e4c79e9bb0ea67b92d",
    ),
    QUALITY(
        "quality", "품질 우선",
        "긴 세그먼트로 최고 품질. 시간·메모리 많이 사용",
        236, 344_064,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v1/htdemucs-quality-fp32.onnx",
        "aba991a16e25d23d9591073cb6173649932e6de3eba528bc12ca4ef304acc459",
    );

    companion object {
        fun fromId(id: String?): Tier = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

/** 모델 입출력 규격 */
data class ModelConfig(
    val sampleRate: Int = 44_100,
    /** Demucs 표준 스템 출력 순서 */
    val stemOrder: List<String> = listOf("drums", "bass", "other", "vocals"),
)
