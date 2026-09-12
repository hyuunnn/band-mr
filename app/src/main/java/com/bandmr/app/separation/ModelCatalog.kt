package com.bandmr.app.separation

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.data.Stem

/**
 * 온디바이스 분리 모델의 스템 구성.
 *
 * 4스템은 공식 `htdemucs`, 6스템은 `htdemucs_6s`. 출력 순서는 export 로그의
 * `model.sources`와 일치해야 한다.
 */
enum class StemLayout(
    val id: String,
    val label: String,
    val description: String,
    val stemOrder: List<String>,
) {
    FOUR(
        "4s", "4스템",
        "보컬·드럼·베이스·그 외. 코어 분리가 더 깨끗합니다",
        listOf("drums", "bass", "other", "vocals"),
    ),
    SIX(
        "6s", "6스템",
        "기타·피아노를 따로 줄일 수 있습니다",
        listOf("drums", "bass", "other", "vocals", "guitar", "piano"),
    );

    val stems: List<Stem> get() = stemOrder.map { name ->
        Stem.fromFileName(name) ?: error("알 수 없는 스템: $name")
    }
}

/** 세그먼트 길이로만 나눈 품질 등급. 가중치는 레이아웃마다 하나다. */
enum class Quality(
    val id: String,
    val label: String,
    val description: String,
    val segmentSamples: Int,
) {
    BALANCED(
        "balanced", "균형형",
        "속도와 품질의 균형 (권장)",
        262_144,
    ),
    QUALITY(
        "quality", "품질 우선",
        "긴 세그먼트로 최고 품질. 시간·메모리 많이 사용",
        344_064,
    ),
}

/**
 * 다운로드 가능한 온디바이스 분리 모델.
 *
 * 4스템(`htdemucs`)·6스템(`htdemucs_6s`)을 균형형/품질 세그먼트로 export해
 * GitHub Releases에 호스팅한다. 6스템 균형형/품질 id는 예전 `balanced`/`quality`를
 * 그대로 써서, 이미 받은 파일과 DB `separatedTier`가 살아 있게 한다.
 *
 * 모델을 다시 올리면 SHA-256을 반드시 갱신할 것 (`tools/export_demucs_onnx.py`가 해시 출력).
 */
enum class Tier(
    val id: String,
    val layout: StemLayout,
    val quality: Quality,
    val url: String,
    /**
     * 모델 파일 SHA-256 해시(64자 hex). 다운로드 후 무결성 검증에 사용.
     * 모델을 다시 업로드하면 반드시 갱신할 것.
     */
    val sha256: String,
    val approxSizeMb: Int = 178,
) {
    S4_BALANCED(
        "4s-balanced", StemLayout.FOUR, Quality.BALANCED,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v3/htdemucs4s-balanced-fp32.onnx",
        "46da05af0844769ff1f095a5ed473ed9987cf09ddaf425a1944af296911720a5",
        approxSizeMb = 236,
    ),
    S4_QUALITY(
        "4s-quality", StemLayout.FOUR, Quality.QUALITY,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v3/htdemucs4s-quality-fp32.onnx",
        "a523c02ba1819986ab9b3d75508a63d3d608b47a8c6019733a1afd5e4ed22dde",
        approxSizeMb = 236,
    ),
    S6_BALANCED(
        "balanced", StemLayout.SIX, Quality.BALANCED,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v2/htdemucs6s-balanced-fp32.onnx",
        "fd5ae237bd6ade5589323e71b5ba47a19d0e2d1f2e1da0f0819a8328e308c477",
    ),
    S6_QUALITY(
        "quality", StemLayout.SIX, Quality.QUALITY,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v2/htdemucs6s-quality-fp32.onnx",
        "d9f222f4015c720408e368b49b54982b5ba437c22cfc94dda370a438c8ceadc7",
    );

    val label: String get() = "${layout.label} ${quality.label}"
    val description: String get() = quality.description
    val segmentSamples: Int get() = quality.segmentSamples
    val stemOrder: List<String> get() = layout.stemOrder
    val stems: List<Stem> get() = layout.stems
    val fileName: String get() = if (layout == StemLayout.SIX) "model-6s.onnx" else "model-4s.onnx"

    companion object {
        fun fromId(id: String?): Tier = when (id) {
            // 경량(light) 폐지. 이미 받은 파일은 ModelManager가 지우고, 설정·DB는 6스템 균형형으로
            "light", "balanced", null, "" -> S6_BALANCED
            "quality" -> S6_QUALITY
            else -> entries.firstOrNull { it.id == id } ?: S6_BALANCED
        }

        fun of(layout: StemLayout, quality: Quality): Tier =
            entries.first { it.layout == layout && it.quality == quality }
    }
}

/** 모델 입출력 규격. [stemOrder]는 선택한 [Tier]에서 가져온다. */
data class ModelConfig(
    val sampleRate: Int = PIPELINE_SAMPLE_RATE,
    val stemOrder: List<String>,
)
