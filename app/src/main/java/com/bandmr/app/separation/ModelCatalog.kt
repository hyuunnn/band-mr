package com.bandmr.app.separation

import com.bandmr.app.audio.PIPELINE_SAMPLE_RATE
import com.bandmr.app.data.Stem

/**
 * 온디바이스 분리 모델의 스템 구성.
 *
 * 4스템은 공식 `htdemucs` 또는 SCNet XL IHF, 6스템은 `htdemucs_6s`. 출력 순서는
 * export 로그의 `model.sources`와 일치해야 한다.
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

    /** ONNX 텐서 축 순서. 화면 목록에는 [displayStems]를 쓴다. */
    val stems: List<Stem> get() = stemOrder.map { name ->
        Stem.fromFileName(name) ?: error("알 수 없는 스템: $name")
    }

    /**
     * 플레이어 목록. [Stem.entries]와 같이 보컬→드럼→베이스→기타→피아노→그 외.
     * 레이아웃에 없는 스템은 뺀다. AI OFF 체크 순서와 맞춘다.
     */
    val displayStems: List<Stem> get() {
        val have = stemOrder.toSet()
        return Stem.entries.filter { it.fileName in have }
    }
}

/**
 * 설정 화면의 모델 묶음. Demucs 4/6과 SCNet은 가중치 가족이 달라 같은 레이아웃
 * 아래에 섞지 않는다.
 */
enum class ModelFamily(
    val id: String,
    val label: String,
    val description: String,
) {
    HTDEMUCS_4(
        "htdemucs-4s", "Demucs 4스템",
        "보컬·드럼·베이스·그 외. 코어 분리가 더 깨끗합니다",
    ),
    HTDEMUCS_6(
        "htdemucs-6s", "Demucs 6스템",
        "기타·피아노를 따로 줄일 수 있습니다",
    ),
    SCNET_XL_IHF(
        "scnet-xl-ihf", "SCNet XL IHF",
        "4스템. 보컬·고역이 Demucs 4스보다 낫습니다 (11초 세그먼트)",
    ),
}

/** 세그먼트 길이로만 나눈 품질 등급. Demucs 가중치는 레이아웃마다 하나다. */
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
 * Demucs 4스템(`htdemucs`)·6스템(`htdemucs_6s`)·SCNet XL IHF는 모두
 * GitHub Releases `model-v3`. 6스템 균형형/품질 id는 예전 `balanced`/`quality`를
 * 그대로 써서, 이미 받은 파일과 DB `separatedTier`가 살아 있게 한다.
 *
 * 모델을 다시 올리면 SHA-256을 반드시 갱신할 것 (export 스크립트가 해시 출력).
 */
enum class Tier(
    val id: String,
    val family: ModelFamily,
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
        "4s-balanced", ModelFamily.HTDEMUCS_4, StemLayout.FOUR, Quality.BALANCED,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v3/htdemucs4s-balanced-fp32.onnx",
        "46da05af0844769ff1f095a5ed473ed9987cf09ddaf425a1944af296911720a5",
        approxSizeMb = 236,
    ),
    S4_QUALITY(
        "4s-quality", ModelFamily.HTDEMUCS_4, StemLayout.FOUR, Quality.QUALITY,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v3/htdemucs4s-quality-fp32.onnx",
        "a523c02ba1819986ab9b3d75508a63d3d608b47a8c6019733a1afd5e4ed22dde",
        approxSizeMb = 236,
    ),
    S6_BALANCED(
        "balanced", ModelFamily.HTDEMUCS_6, StemLayout.SIX, Quality.BALANCED,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v3/htdemucs6s-balanced-fp32.onnx",
        "d2d04ceaeaa865dd6ab35c41526baecfd7d4353e532b87fe134dc0873a66c597",
    ),
    S6_QUALITY(
        "quality", ModelFamily.HTDEMUCS_6, StemLayout.SIX, Quality.QUALITY,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v3/htdemucs6s-quality-fp32.onnx",
        "f743870066a9ea71656df9acddc8988f17f97b7056994eac3aa0221d7b44c71e",
    ),
    /**
     * ZFTurbo SCNet XL IHF (MUSDB-only). 세그먼트는 학습 청크 485100.
     * SHA는 `tools/export_scnet_onnx.py`가 출력한 값을 핀한다.
     */
    S4_SCNET_XL_IHF(
        "scnet-xl-ihf", ModelFamily.SCNET_XL_IHF, StemLayout.FOUR, Quality.BALANCED,
        "https://github.com/hyuunnn/band-mr/releases/download/model-v3/scnetxl-ihf-fp32.onnx",
        "9c05acc47b908562d2792351952a13dfd1353932e108832528ac94cd3136c98c",
        approxSizeMb = 287,
    );

    val label: String get() = when (family) {
        ModelFamily.SCNET_XL_IHF -> family.label
        else -> "${layout.label} ${quality.label}"
    }
    val description: String get() = when (family) {
        ModelFamily.SCNET_XL_IHF -> family.description
        else -> quality.description
    }
    val cardTitle: String get() = when (family) {
        ModelFamily.SCNET_XL_IHF -> "XL IHF"
        else -> quality.label
    }
    val segmentSamples: Int get() = when (family) {
        ModelFamily.SCNET_XL_IHF -> SCNET_XL_IHF_SEGMENT
        else -> quality.segmentSamples
    }
    val stemOrder: List<String> get() = layout.stemOrder
    val stems: List<Stem> get() = layout.stems
    val displayStems: List<Stem> get() = layout.displayStems
    val fileName: String get() = when (family) {
        ModelFamily.SCNET_XL_IHF -> "model-scnet-xl-ihf.onnx"
        ModelFamily.HTDEMUCS_6 -> "model-6s.onnx"
        else -> "model-4s.onnx"
    }

    companion object {
        /** SCNet XL IHF 학습·추론 청크. 44.1kHz × 11초. */
        const val SCNET_XL_IHF_SEGMENT = 485_100

        fun fromId(id: String?): Tier = when (id) {
            // 경량(light) 폐지. 이미 받은 파일은 ModelManager가 지우고, 설정·DB는 6스템 균형형으로
            "light", "balanced", null, "" -> S6_BALANCED
            "quality" -> S6_QUALITY
            else -> entries.firstOrNull { it.id == id } ?: S6_BALANCED
        }

        /** Demucs 레이아웃×품질. SCNet은 같은 FOUR/BALANCED를 쓰므로 제외한다. */
        fun of(layout: StemLayout, quality: Quality): Tier =
            entries.first {
                it.family != ModelFamily.SCNET_XL_IHF &&
                    it.layout == layout && it.quality == quality
            }
    }
}

/** 모델 입출력 규격. [stemOrder]는 선택한 [Tier]에서 가져온다. */
data class ModelConfig(
    val sampleRate: Int = PIPELINE_SAMPLE_RATE,
    val stemOrder: List<String>,
)
