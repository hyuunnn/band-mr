# AGENTS.md — AI 에이전트용 프로젝트 가이드

밴드 연습용 MR 제거 앱 (Android, Kotlin + Compose). 곡에서 보컬/드럼/베이스/기타를 제거한 반주로 합주 연습.

## 빌드 / 테스트

```bash
# homebrew openjdk@17는 삭제됨 → Temurin 17 사용 (시스템 기본 java 26은 Gradle 8.9가 거부)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home

./gradlew :app:testDebugUnitTest      # 단위 테스트 (30개)
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
```

- Android SDK: `/opt/homebrew/share/android-commandlinetools` (local.properties의 `sdk.dir` 참조, 커밋 금지). adb/sdkmanager는 `/opt/homebrew/bin`에 있음
- compileSdk/targetSdk 35, minSdk 31, AGP 8.7.3, Kotlin 2.0.21, ORT Android 1.18.0 (media3/ExoPlayer는 제거됨 — 아래 아키텍처 참조)
- 실기기(SM-S931N, Android 16)가 adb로 연결되면 실기기 검증 가능. 그 외 런타임 검증은 빌드+JVM 단위테스트

## 검증 규칙 (중요)

- 로직 수정 후 반드시 `testDebugUnitTest` 실행. DSP/WAV/청크 수학은 전부 JVM 순수 Kotlin이라 유닛테스트로 검증한다
- 신규 DSP 코드는 "원본 구현과 수치 비교" 테스트를 함께 추가할 것 (과거 WavReader FOURCC 버그, SpectralStage 크래시, WavWriter 빅엔디안 버그가 모두 테스트로 발견됨)

## 아키텍처 요약

```
audio/       AI OFF: SourceWavPlayer(원본 WAV 캐시 재생 + DspChain 실시간 적용) / AI ON: StemMixPlayer(스템 믹서)
             MixCache: 원본을 44.1kHz 스테레오 PCM16 WAV로 디코딩해 filesDir/mixcache에 보관
separation/  AudioDecode(MediaCodec→44.1k raw) → DemucsSeparator(ONNX) → 스템 WAV 캐시
playback/    PlaybackService(백그라운드 재생 + 알림 컨트롤)
export/      믹스/스템 WAV 내보내기
data/        Room(Song), DataStore(설정)
ui/          Compose (라이브러리/플레이어/설정)
tools/       모델 변환 스크립트 (아래参照)
```

핵심 불변식:
- **AI OFF 재생은 압축 원본 스트리밍 금지 — 반드시 MixCache의 WAV를 재생한다.** 일부 기기(SM-S931N, Android 16 펌웨어)에서 MediaCodec 비동기 스트리밍 디코딩이 무음/노이즈로 깨진다(비동기 큐잉 강제 비활성화로도 불가 확인). 캐시는 곡 추가/앱 시작 때 백그라운드로 만들고, 없으면 재생 시점에 준비 후 자동 이어재생(preparingSongId 상태로 UI 표시)
- **PlayerController.release()는 코루틴 스코프를 절대 cancel하지 않는다.** 싱글턴 컨트롤러의 스코프를 취소하면 이후 캐시 준비가 조용히 무시되어 "준비 중" 문구가 영구 노출된다(실제 발생한 버그)
- **DemucsSeparator는 항상 고정 길이 세그먼트**(`Tier.segmentSamples`)로 추론. 마지막 청크는 0 패딩. ONNX 모델도 고정 shape로 export됨 — 동적 축 쓰면 안 됨
- 오디오 처리 좌우로 interleaved stereo PCM16이 기본. 모노는 DspChain/SpectralStage에서 chCount=1 분기
- WAV I/O는 little-endian. FOURCC('RIFF' 등)은 LE int로 읽음 (`WavIo.kt` 상수 참조)
- PlayerController가 오디오 포커스·이어폰 분리(BECOMING_NOISY)를 관리
- 시크/마스크 변경 시에는 DspChain 상태를 반드시 리셋할 것(SourceWavPlayer.seekToFrame, muteMask setter). SpectralStage FIFO 잔여분이 시크 직후 잡음으로 붙는다
- PitchShifter는 0반음일 때 패스스루다(지연 제거). 비율 분기 로직 건드릴 때 주의
- ModelManager 다운로드는 Range 이어받기를 한다 — 부분 파일(.tmp)은 네트워크 실패 시 보존하고 무결성 실패 시에만 삭제

## AI 모델 (GitHub Releases 호스팅)

- 3종 모두 fp32, 세그먼트만 다름: light 131072 / balanced 262144 / quality 344064 (약 236MB씩)
- URL: `github.com/hyuunnn/band-mr2/releases/download/model-v1/*.onnx` — 저장소 public이라 익명 다운로드 됨
- 라이선스: 가중치는 Meta의 demucs(MIT)에서 파생 — 고지는 `THIRD_PARTY_NOTICES.md` 유지할 것
- `ModelCatalog.kt`에 SHA-256 핀. **모델을 다시 올리면 해시 3개 반드시 갱신**
- 원본 PyTorch 대비 활성 구간 corr=1.0000 확인 완료

## htdemucs ONNX 변환 주의사항 (tools/export_demucs_onnx.py)

그대로는 export 불가 — 아래 우회가 모두 필요:
1. `torch.stft/istft` complex 반환 → `demucs.htdemucs.spectro/ispectro`를 re/im 쌍 텐서 버전으로 교체
   - STFT: Conv1D(stride=hop) 투영 ×`win_length^-0.5`, iSTFT: DFT 행렬곱 + gather OLA ×`√win_length` (torch 배율 실험 확인값)
2. `get_model()` 반환은 BagOfModels 감싸개 → `.models[0]` 사용 + `use_train_segment=False`
3. `nn.MultiheadAttention`은 융합 연산자 때문에 수동 분해 버전으로 교체
4. cac=True 경로의 `_magnitude/_mask`는 view_as_real/complex만 대체하면 됨
5. opset 18 필요(col2im 등), `do_constant_folding=False` 권장
6. int8 동적 양자화는 활성 범위 큰 입력에서 심각하게 깨짐(corr 0.01대) → 사용하지 말 것. fp16 컨버터(onnxruntime/onnxconverter_common)도 이 그래프에선 dtype 불일치 발생 → **fp32 그대로 사용**
7. 검증 시 무음 패딩 구간이 corr을 망가뜨리므로 **활성 구간만** 비교할 것

환경: python venv는 임시 폴더라 사라졌을 수 있음. 재구성: `python3 -m venv && pip install torch torchaudio demucs onnx onnxruntime onnxscript onnxconverter-common`

## Git

- 커밋 작성자: 저장소 로컬 설정으로 `hyuunnn <15611739+hyuunnn@users.noreply.github.com>` 지정됨 (전역 설정 아님)
- 원격: `origin = github.com/hyuunnn/band-mr2` (public)
