# AGENTS.md — AI 에이전트용 프로젝트 가이드

밴드 연습용 MR 제거 앱 (Android, Kotlin + Compose). 곡에서 보컬/드럼/베이스/기타/피아노/그외를 줄이거나 제거한 반주로 합주 연습.

## 빌드 / 테스트

```bash
# JDK 17: temurin-17(/Library/Java) 사용. homebrew openjdk@17 경로는 현재 존재하지 않음(2026-08 확인)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # → /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # local.properties가 없으면 필수

./gradlew :app:testDebugUnitTest      # 단위 테스트 (84개)
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
```

- Android SDK: `/opt/homebrew/share/android-commandlinetools` (`ANDROID_HOME` 또는 local.properties의 `sdk.dir`로 지정, local.properties는 커밋 금지). adb/sdkmanager는 `/opt/homebrew/bin`에 있음
- compileSdk 37 / targetSdk 36, minSdk 31, Gradle 9.5.0, AGP 9.3.2(빌트인 Kotlin — kotlin.android 플러그인 없음), Kotlin 2.4.10, KSP 2.3.11(Kotlin과 독립 버전), ORT Android 1.29.0 (media3/ExoPlayer는 제거됨 — 아래 아키텍처 참조)
- 실기기(SM-S931N, Android 16)가 adb로 연결되면 실기기 검증 가능. 그 외 런타임 검증은 빌드+JVM 단위테스트

## 검증 규칙 (중요)

- 로직 수정 후 반드시 `testDebugUnitTest` 실행. DSP/WAV/청크 수학은 전부 JVM 순수 Kotlin이라 유닛테스트로 검증한다
- 신규 DSP 코드는 "원본 구현과 수치 비교" 테스트를 함께 추가할 것 (과거 WavReader FOURCC 버그, SpectralStage 크래시, WavWriter 빅엔디안 버그가 모두 테스트로 발견됨)

## 아키텍처 요약

```
audio/       재생 골격은 AudioTrackEngine(오디오 스레드 루프·A-B·시크·배속 공통 베이스)
             AI OFF: SourceWavPlayer(원본 WAV 캐시 재생 + DspChain 실시간 적용) / AI ON: StemMixPlayer(스템 믹서)
             배속은 PlaybackSpeed → AudioTrack PlaybackParams (키와 독립, 곡마다 Song.speed 저장)
             점프는 PlaybackSkip(±5/±10초) → PlayerController.skipBy (0~duration 클램프)
             A-B는 PlaybackLoop(최소 0.5초) → 엔진이 B에서 A로 seek. Song.loopStartMs/EndMs 저장, 내보내기 제외
             파형은 WaveformPeaks(MixCache WAV 피크) → WaveformBar. 캐시 없으면 슬라이더
             MixCache: 원본을 44.1kHz 스테레오 PCM16 WAV로 디코딩해 filesDir/mixcache에 보관
separation/  MixCache WAV → DemucsSeparator(ONNX) → 스템 WAV 캐시
             AudioDecode는 MixCache·내보내기용(MediaCodec→44.1k). 분리 전용 raw는 만들지 않음
             OrtModelCache: 모델 파일당 OrtSession 1개를 프로세스 동안 재사용(등급 변경 시에만 재오픈)
playback/    PlaybackService(백그라운드 재생 + 알림 컨트롤)
export/      믹스/스템 WAV 내보내기. 믹스는 스템 게인+키만 반영하고 배속·A-B는 넣지 않음
youtube/     유튜브 링크로 곡 추가: NewPipeExtractor로 오디오 스트림 추출·다운로드(filesDir/sources)
             → Song(file:// URI) 등록 → 기존 MixCache 파이프라인 그대로 사용
             임포트·모델 다운로드는 appScope(FGS 아님). 화면을 열어 둔 채 받아야 함(앱을 내리면 끊길 수 있음)
data/        Room(Song v4: stemGainsPacked·muteMask/키/배속/loopStartMs/EndMs), DataStore(설정)
             stemGainsPacked가 기준(악기별 0~100%). muteMask는 0%만 비트 ON으로 파생 저장. AI OFF DSP가 읽음
ui/          Compose (라이브러리/플레이어/설정). 파형 시크는 WaveformBar
tools/       모델 변환 스크립트 (아래 참조)
```

핵심 불변식:
- **AI OFF 재생은 압축 원본 스트리밍 금지 — 반드시 MixCache의 WAV를 재생한다.** 일부 기기(SM-S931N, Android 16 펌웨어)에서 MediaCodec 비동기 스트리밍 디코딩이 무음/노이즈로 깨진다(비동기 큐잉 강제 비활성화로도 불가 확인). 캐시는 곡 추가/앱 시작 때 백그라운드로 만들고, 없으면 재생 시점에 준비 후 자동 이어재생(preparingSongId 상태로 UI 표시)
- 유튜브 임포트 원본도 압축 파일(file:// URI)일 뿐 동일 규칙 적용 — sources/<videoId>.<ext>를 두고 MixCache WAV로만 재생. NewPipeExtractor는 GPL-3.0이라 배포 시 THIRD_PARTY_NOTICES.md 고지 필수
- **PlayerController.release()는 코루틴 스코프를 절대 cancel하지 않는다.** 싱글턴 컨트롤러의 스코프를 취소하면 이후 캐시 준비가 조용히 무시되어 "준비 중" 문구가 영구 노출된다(실제 발생한 버그)
- **DemucsSeparator는 항상 고정 길이 세그먼트**(`Tier.segmentSamples`)로 추론. 마지막 청크는 0 패딩. ONNX 모델도 고정 shape로 export됨 — 동적 축 쓰면 안 됨
- 오디오 처리 좌우로 interleaved stereo PCM16이 기본. 모노는 DspChain/SpectralStage에서 chCount=1 분기
- WAV I/O는 little-endian. FOURCC('RIFF' 등)은 LE int로 읽음 (`WavIo.kt` 상수 참조)
- PlayerController가 오디오 포커스·이어폰 분리(BECOMING_NOISY)를 관리
- 시크/마스크 변경 시에는 DspChain 상태를 반드시 리셋할 것(AudioTrackEngine.seekToFrame이 `resetProcessors()` 훅을 호출하고 SourceWavPlayer가 chain을 재생성, muteMask setter는 rebuildChain). SpectralStage FIFO 잔여분이 시크 직후 잡음으로 붙는다
- **스템 볼륨의 기준은 `stemGainsPacked`.** UI·저장·내보내기는 퍼센트만 바꾸고, `muteMask`는 `Stem.muteMaskFromPacked`(0%만 ON)로 파생한다. AI ON 믹서는 `gainArrayFromPacked`(0~1), AI OFF는 체크(0/100) + 보컬 제거 강도
- PitchShifter는 0반음일 때 패스스루다(지연 제거). 비율 분기 로직 건드릴 때 주의
- 재생 배속은 AudioTrack.setPlaybackParams(speed, pitch=1)만 사용한다. 오프라인 WSOLA/타임스트레치는 쓰지 않음. 시크·재생 재개 때 배속을 다시 걸 것(일시정지 중 적용이 실패하는 기기 있음)
- **A-B 랩은 오디오 스레드에서만 한다.** UI 폴링으로 B→A 하면 백그라운드에서 끊긴다. 시크/점프는 `PlaybackLoop.clampSeek`로 구간 안에 가둔다. 곡 전환 때는 `setLoop(..., apply=false)` 후 새 엔진에 적용할 것(이전 곡 엔진에 먼저 걸면 안 됨)
- **파형 피크는 songId 기준 remember.** `preparingSongId`가 바뀔 때 null 하면 슬라이더가 깜빡인다
- **내보내기는 배속·A-B를 넣지 않는다.** 연습용 배속/구간과 저장 파일(원곡 템포·전체 길이)을 섞지 말 것
- ModelManager 다운로드는 Range 이어받기를 한다 — 부분 파일(.tmp)은 네트워크 실패 시 보존하고 무결성 실패 시에만 삭제
- **스템 분리는 MixCache WAV를 입력으로 쓴다.** 캐시가 있으면 원본을 다시 디코딩하지 않는다.
- **오디오 파이프라인은 44.1kHz(`PIPELINE_SAMPLE_RATE`) 고정 가정.** MixCache WAV·Demucs 스템 모두 이 레이트로 생성되며, 불일치 스템은 재생·내보내기에서 제외된다. PlayerController의 ms↔프레임 수학도 이 값에 묶인다
- **OrtSession은 곡마다 닫지 않는다.** `OrtModelCache`가 같은 모델 경로면 재사용한다. 경량/균형/품질을 바꾸면 그때만 닫고 다시 연다

## AI 모델 (GitHub Releases 호스팅)

- htdemucs_6s (6스템: drums/bass/other/vocals/guitar/piano — `ModelConfig.stemOrder`가 이 순서와 일치해야 함)
- 3종 모두 fp32, 세그먼트만 다름: light 131072 / balanced 262144 / quality 344064 (약 178MB씩)
- URL: `github.com/hyuunnn/band-mr/releases/download/model-v2/*.onnx` — 저장소 public이라 익명 다운로드 됨
- 라이선스: 가중치는 Meta의 demucs(MIT)에서 파생 — 고지는 `THIRD_PARTY_NOTICES.md` 유지할 것
- `ModelCatalog.kt`에 SHA-256 핀. **모델을 다시 올리면 해시 3개 반드시 갱신**
- 온디바이스 모델 파일명은 `model-6s.onnx`
- 원본 PyTorch 대비 활성 구간 corr=1.0000 확인 완료

## htdemucs_6s ONNX 변환 주의사항 (tools/export_demucs_onnx.py)

사용법: `python export_demucs_onnx.py <출력폴더>` (htdemucs_6s 전용)

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
- 원격: `origin = github.com/hyuunnn/band-mr` (public)
