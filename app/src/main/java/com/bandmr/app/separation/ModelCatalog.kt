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
     * 직접 변환·호스팅한 뒤 `shasum -a 256 <파일>` 등으로 구해 채워 넣을 것.
     * null이면 크기 기반의 느슨한 검증만 수행.
     */
    val sha256: String? = null,
) {
    LIGHT(
        "light", "경량 우선",
        "빠르고 저전력·저발열. 품질은 보통",
        30, 131_072,
        "https://huggingface.co/bandmr-models/models/resolve/main/htdemucs-int8.onnx",
    ),
    BALANCED(
        "balanced", "균형형",
        "속도와 품질의 균형 (권장)",
        56, 262_144,
        "https://huggingface.co/bandmr-models/models/resolve/main/htdemucs-fp16.onnx",
    ),
    QUALITY(
        "quality", "품질 우선",
        "최고 품질. 시간·배터리·메모리 많이 사용",
        110, 344_064,
        "https://huggingface.co/bandmr-models/models/resolve/main/htdemucs-fp32.onnx",
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
