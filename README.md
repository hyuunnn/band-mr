# 밴드 MR (Band MR)

밴드 연습용 MR 제거 앱 (Android). 곡에서 **보컬 / 드럼 / 베이스 / 기타**를 체크해서 제거하고 남은 반주로 합주 연습을 할 수 있습니다.

## 주요 기능

| 기능 | 설명 |
|---|---|
| 스텝별 제거 | 보컬·드럼·베이스·기타(키보드 포함)를 체크하여 재생에서 제거 |
| AI ON/OFF | OFF: 실시간 신호처리(절전) / ON: 온디바이스 AI 분리(고품질) |
| 키 조절 | ±12반음 (옥타브 포함), 세로톤 유지 피치 시프트 |
| 내보내기 | ① 현재 설정으로 믹스 WAV 저장 ② 스템별 WAV 개별 저장 |
| 모델 3종 | 경량(약30MB) / 균형(약56MB) / 품질(약110MB) 선택 다운로드 |

## AI ON/OFF 동작 방식

```
AI OFF (실시간, 절전)
  원본 파일 ──▶ ExoPlayer ──▶ MrAudioProcessor(실시간 DSP)
                                ├ 보컬 제거: L-R 위상 상쇄
                                ├ 베이스 제거: 하이패스 2단 (110Hz)
                                ├ 드럼 제거: 트랜지언트 게이트 (실험적)
                                └ 기타 제거: 중역대 페킹 딥 (실험적)

AI ON (사전 분리 후 캐시, 고품질)
  원본 파일 ──▶ MediaCodec 디코딩 ──▶ Demucs ONNX 추론(4스템)
             ──▶ 스템별 WAV 캐시 ──▶ 커스텀 믹서로 동기 재생 + 게인/피치
```

- **AI OFF**: 즉시 반응하고 배터리를 거의 쓰지 않지만, 신호처리 특성상 완전히 분리되진 않습니다(특히 기타).
- **AI ON**: 곡당 한 번만 처리하면 캐시로 재사용되며, 체크한 스템이 정확히 제거됩니다.
  - 경량: 몇십 초 ~ 1분 내외 / 품질: 수분 소요, 발열 있음

## 빌드

요구사항: Android Studio (Koala 이상 권장), JDK 17+, Android SDK 35

```bash
# Android Studio에서 열거나
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

minSdk 31 (Android 12+) / targetSdk 35

## ⚠️ AI 모델 준비 (필수 작업)

라이선스 문제로 모델 파일은 APK에 포함되어 있지 않고, 첫 실행 시 다운로드합니다.
`app/src/main/java/com/bandmr/app/separation/ModelCatalog.kt`의 URL은 **예시 플레이스홀더**이므로,
아래 과정으로 직접 변환·호스팅한 뒤 URL을 교체해야 AI 기능이 동작합니다.

### 1) Demucs v4(htdemucs)를 ONNX로 변환

```python
import torch
from demucs.pretrained import get_model

m = get_model("htdemucs").cpu().eval()
SEG = 262144  # 균형형 세그먼트 (경량 131072 / 품질 344064 권장, 동적 축 지원)

torch.onnx.export(
    m, torch.randn(1, 2, SEG), "htdemucs-fp32.onnx",
    opset_version=17,
    input_names=["audio"], output_names=["stems"],
    dynamic_axes={"audio": {2: "samples"}, "stems": {3: "samples"}},
)
```

### 2) 등급별 변환

```bash
# 경량: int8 동적 양자화 (~30MB)
python -c "
from onnxruntime.quantization import quantize_dynamic
quantize_dynamic('htdemucs-fp32.onnx', 'htdemucs-int8.onnx')"

# 균형: fp16 (~56MB)
python -c "
from onnxruntime.transformers import float16
m = float16.convert_float_to_float16_model_path('htdemucs-fp32.onnx')
m.save_model_to_file('htdemucs-fp16.onnx')"
```

### 3) 호스팅 후 URL 교체

Hugging Face 등에 업로드하고 `ModelCatalog.kt`의 `LIGHT/BALANCED/QUALITY.url`을 실제 주소로 바꾸세요.

> 변환 시 `dynamic_axes` 없이 고정 길이로 export했다면, 각 등급의 `segmentSamples`를 export에 사용한 값과 일치시켜야 합니다.

## 프로젝트 구조

```
app/src/main/java/com/bandmr/app/
├── MainActivity.kt            # 네비게이션 (라이브러리/플레이어/설정)
├── BandMrApp.kt               # Application + 수동 DI(Locator)
├── audio/
│   ├── DspChain.kt            # 바이쿼드/트랜지언트 게이트 (실시간·오프라인 공용)
│   ├── MrAudioProcessor.kt    # Media3 AudioProcessor (AI OFF 실시간 DSP)
│   ├── PitchShift.kt          # ±12반음 피치 시프터
│   ├── StemMixPlayer.kt       # 스템 4개 동기 재생 믹서 (AudioTrack)
│   ├── PlayerController.kt    # 두 엔진 전환/위치 보존/파라미터 적용
│   └── WavIo.kt               # WAV 읽기/스트리밍 쓰기
├── separation/
│   ├── ModelCatalog.kt        # 모델 3종 정의 (URL 교체 필요)
│   ├── ModelManager.kt        # 다운로드/삭제/상태
│   ├── AudioDecode.kt         # MediaCodec → 44.1kHz raw PCM
│   ├── DemucsSeparator.kt     # ONNX 추론 + 오버랩 크로스페이드
│   ├── SeparationService.kt   # Foreground Service + 진행 알림
│   └── SepBus.kt              # 서비스↔UI 상태 버스
├── export/Exporter.kt         # 믹스/스템 내보내기
├── data/                      # Room(Song), DataStore(설정)
└── ui/                        # Compose 화면들
```

## 알려진 한계

- 비AI 모드의 기타/드럼 제거는 근사 처리입니다(정확한 분리는 AI 모드 사용).
- 피치 시프터는 실시간용 그레놀라 방식으로 ±5반음 이상에서 워블 아티팩트가 있을 수 있습니다.
- 품질 우선 모델은 메모리를 많이 사용하므로 RAM 4GB 이상 기기를 권장합니다.
