# AGENTS.md — AI 에이전트용 프로젝트 가이드

밴드 연습용 MR 제거 앱 (Android, Kotlin + Compose). 곡에서 보컬/드럼/베이스/기타/피아노/그외를 줄이거나 제거한 반주로 합주 연습.

> 이 파일은 **매 세션 로드되므로 "모르면 틀리는 것"만 적는다.** 버전·경로처럼 파일 하나 열면 나오는 사실, 이유를 길게 설명해야 하는 배경은 넣지 않는다(전자는 `app/build.gradle.kts`, 후자는 해당 코드의 KDoc과 커밋 메시지에 있다).

## 빌드 / 테스트

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)                    # temurin-17. homebrew openjdk@17 경로는 없음
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools    # local.properties가 없으면 필수

./gradlew :app:testDebugUnitTest      # 단위 테스트 (119개)
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties`는 커밋 금지. adb/sdkmanager는 `/opt/homebrew/bin`
- AGP가 Kotlin을 내장하므로 `kotlin.android` 플러그인이 없다. KSP는 Kotlin과 버전 체계가 독립이라 함께 올리면 깨진다
- 실기기(SM-S931N, Android 16)가 adb로 붙으면 실기기 검증 가능. 그 외에는 빌드 + JVM 단위테스트가 유일한 검증 수단

## 검증 규칙

- **로직 수정 후 반드시 `testDebugUnitTest`.** DSP/WAV/청크 수학은 전부 JVM 순수 Kotlin이라 유닛테스트로 잡힌다
- **신규·수정 DSP에는 "기존 구현과 수치 비교" 테스트를 함께 추가할 것.** 과거 WavReader FOURCC·SpectralStage 크래시·WavWriter 빅엔디안 버그가 모두 이 방식으로 발견됐고, 성능 리팩터링(1패스 캐시·DSP 제자리 리셋·디코더 버퍼 재사용)은 전부 "바이트 동일" 테스트로 고정돼 있다

## 코드 지도

```
audio/       AudioTrackEngine  재생 공통 베이스(오디오 스레드 루프·시크·A-B·배속)
             SourceWavPlayer   AI OFF: MixCache WAV + DspChain 실시간
             StemMixPlayer     AI ON: 스템 6개 믹서
             PlayerController  엔진 2개를 active(=aiMode ? mixer : source) 하나로 다룸.
                               오디오 포커스·이어폰 분리(BECOMING_NOISY)도 여기서 관리
             StemWavSet        스템 WAV 열기 규칙 — 재생·내보내기 공용
             MixCache          원본 → 44.1k 스테레오 PCM16 WAV (filesDir/mixcache)
             WaveformPeaks     막대 RMS(곡 내 최댓값 정규화) → WaveformBar
             PlaybackSpeed / PlaybackSkip(±5·±10초) / PlaybackLoop(최소 0.5초)
io/          FilePromote       .part/.tmp → 정식 경로 승격 (rename 실패 시 copy 폴백)
separation/  MixCache WAV → DemucsSeparator(ONNX) → stems/<songId>/
             AudioDecode는 MixCache 전용. 분리·내보내기는 MixCache WAV를 읽는다
             SepBus는 "마지막 시도 결과"(진행/오류)만 담고, 완료는 Song.isSeparated가 갖는다
playback/    PlaybackService   백그라운드 재생 FGS + MediaSession(OS 미디어 카드)
             SkipButton enum   점프 4종의 유일한 정의 — 알림 액션과 커스텀 액션이 같은 표를 쓴다
export/      믹스·스템 WAV 내보내기. 피치는 PitchShifter.renderTo 공용
youtube/     NewPipeExtractor로 오디오 추출·다운로드(filesDir/sources) → Song(file://) 등록
             → 기존 MixCache 파이프라인. 임포트·모델 다운로드는 appScope(FGS 아님)
data/        Room(Song v4) + DataStore. muteMask 컬럼은 v3 이전 마이그레이션 전용(읽지 않음)
ui/          Compose (라이브러리/플레이어/설정)
tools/       모델 변환 스크립트 — 절차는 tools/README.md
```

## 불변식

되돌리면 회귀하는 결정들. 상세한 이유는 각 항목이 가리키는 코드의 KDoc에 있다.

**패키징**
- **`abiFilters = arm64-v8a` 단일 ABI.** ONNX 네이티브가 ABI당 23~38MB. armeabi-v7a는 분리의 3GB대 네이티브 힙을 못 담고, x86은 에뮬레이터 전용(Apple Silicon 에뮬레이터도 arm64)
- **`material-icons-extended`를 다시 넣지 말 것.** AAR 34MB인데 쓰는 아이콘은 12개, `isMinifyEnabled=false`라 R8이 못 걷어낸다. core에 없는 글리프는 `res/drawable` 벡터를 `painterResource`로 — 알림이 쓰는 것과 같은 파일이다
- 현재 debug APK 약 71MB. 더 줄이려면 R8을 켜야 하고 NewPipeExtractor/rhino keep 규칙이 필요하다

**재생**
- **AI OFF는 압축 원본을 스트리밍하지 않는다 — 반드시 MixCache WAV.** 일부 기기(SM-S931N/Android 16)에서 MediaCodec 스트리밍 디코딩이 무음·노이즈로 깨진다(비동기 큐잉 비활성화로도 불가). 유튜브 임포트 원본도 압축 파일일 뿐 같은 규칙
- **`PlayerController.release()`는 코루틴 스코프를 cancel하지 않는다.** 싱글턴이라 취소하면 이후 캐시 준비가 조용히 무시되고 "준비 중"이 영구 노출된다(실제 발생)
- **재생 종료 경로는 `release()` 하나.** 알림 지우기·최근 앱 치우기(`onTaskRemoved`)·곡 삭제가 모두 지난다. 홈으로 나가는 건 종료가 아니다(FGS는 태스크가 사라져도 살아남는다)
- **`release()`는 `releaseEpoch`를 올려 화면이 엔진을 재준비하게 하고, 종료 절차 중에는 `PlaybackService.stopping`이 알림 재등록을 막는다.** 신호가 없으면 재생 버튼이 영구 무반응, 가드가 없으면 방금 지운 알림이 되살아난다. 화면이 열려 있는 동안 엔진 해제는 사실상 무효 — 의도된 트레이드오프다(`PlayerController.releaseEpoch` KDoc)
- **알림·잠금화면·블루투스는 `setPlaying(Boolean)`(절대 명령)으로 받는다.** 상태를 읽어 토글하면 그 사이에 낀 자동 일시정지(포커스 상실·이어폰 분리)가 명령을 뒤집는다. 상태를 읽는 곳은 화면 버튼용 `playPause()` 한 곳뿐
- **`AudioTrackEngine.release()` 이후 곡끝 통보는 막힌다(`released`).** 확인은 post 시점이 아니라 **콜백 실행 시점**. 플래그는 `stopEngine`이 아니라 `release`에서만 세울 것
- **`StemMixPlayer.renderChunk`는 0 이하를 돌려주지 않는다.** 엔진이 `produced <= 0`을 곡 끝으로 읽는다 → 읽을 게 없으면 무음, 닫힌 리더 예외는 스템 단위로 흡수(`loop()`에 catch가 없어 오디오 스레드가 죽는다)
- **A-B 랩은 오디오 스레드에서만.** UI 폴링이면 백그라운드에서 끊긴다. 곡 전환 때는 `setLoop(..., apply=false)` 후 새 엔진에 적용(이전 곡 엔진에 먼저 걸면 안 됨)
- 배속은 `AudioTrack.setPlaybackParams(speed, pitch=1)`만 쓴다(오프라인 타임스트레치 금지). 시크·재생 재개 때 다시 걸 것 — 일시정지 중 적용이 실패하는 기기가 있다

**DSP**
- **시크는 재할당이 아니라 제자리 리셋.** `seekToFrame`이 `processorsDirty`만 세우고 오디오 스레드가 렌더 직전에 소비한다. SpectralStage는 스레드 안전하지 않아 UI 스레드 reset이면 FIFO 인덱스가 음수가 되어 죽고, 소비가 `framePos`·곡끝 판정보다 뒤면 방금 비운 체인에 시크 이전 오디오가 들어간다. muteMask 변경만 객체 교체(`chain`은 `@Volatile`)
- **`SpectralStage.reset()`은 magHist까지 비운다.** `histPos/histFill`은 인스턴스 단위인데 증가는 채널마다 일어나 안 쓴 슬롯을 읽는다. `DspChainResetTest`가 "리셋 출력 == 새 체인 출력"을 고정
- `PitchShifter`는 0반음일 때 패스스루(지연 제거) — 비율 분기 건드릴 때 주의
- 오디오는 interleaved stereo PCM16 기본. 모노는 DspChain/SpectralStage에서 `chCount=1` 분기
- WAV I/O는 little-endian. FOURCC('RIFF' 등)도 LE int로 읽는다(`WavIo.kt` 상수)
- **파이프라인은 44.1kHz(`PIPELINE_SAMPLE_RATE`) 고정.** MixCache·스템이 모두 이 레이트고 불일치 스템은 제외된다. PlayerController의 ms↔프레임 수학도 여기 묶여 있다

**파일·캐시**
- **임시 산출물 승격은 반드시 `FilePromote`.** MixCache WAV·스템 디렉터리·모델·유튜브 원본이 "완성 뒤에만 정식 이름으로 공개" 규약을 공유한다. 복사 폴백이 도중에 실패하면 목적지를 지운다 — 이 앱은 파일 존재를 완성 신호로 쓰므로 잘린 결과가 남으면 손상 캐시가 재생에 쓰인다. 분리 결과는 `stems/<songId>.part` → 성공 시에만 승격(정식 디렉터리를 먼저 지우면 실패 시 스템 없는 곡이 된다)
- **MixCache 준비는 1패스.** `decodeTo44kStereo` → `WavWriter`가 `.part`에 직접(헤더 크기는 close 때 패치). 중간 raw를 만들면 쓰기량·피크가 2배. 승격은 반드시 close 뒤
- **빈 디코딩 결과는 승격하지 않는다.** `decodeTo44kStereo`가 0프레임을 돌려주면 `prepare`가 던진다 — 헤더만 있는 44바이트 WAV는 `FilePromote`(크기를 보지 않는다)와 `WavReader` 파싱을 **둘 다 통과**해서, 한 번 공개되면 아무도 못 잡고 `play()`가 조용히 no-op이 된다(재생 버튼 영구 무반응). `MixCacheWavTest`가 "기존 안전장치로는 못 막는다"를 고정한다
- **쓸 수 없는 캐시 WAV는 즉시 버린다(`openSourceOrDiscardCache`).** `prepare`가 `exists()`만 보므로 열기 실패를 "캐시 없음"으로 흘리면 준비→실패→준비가 영구히 겉돈다. `MixCache.delete`로 wav·peaks를 함께 지울 것(파형 캐시 검사는 원본 **크기** 기준이라 재생성본이 같은 크기면 손상본 막대가 살아남는다). 길이 0 분기는 일회성 마이그레이션 — 위 검사가 없던 버전이 남긴 파일만 해당하고 그 경로가 사라지면 지워도 된다
- **파형 막대는 `mixcache/<songId>.peaks`에 캐시.** 계산은 WAV 전체 스캔인데 결과는 1.9KB다. 막대 수·원본 크기를 함께 저장해 불일치·손상 시 재계산. 표시 전용이라 실패해도 예외를 던지지 않는다(`FilePromote`를 쓰지 않는 유일한 산출물)
- 파형 데이터는 **songId 기준 remember**. `preparingSongId`가 바뀔 때 null 하면 슬라이더가 깜빡인다. 캐시가 없으면 `MixCache.awaitReady`로 기다린다(파일 폴링 금지)

**분리**
- **`OrtSession`은 분리 1회마다 열고 닫는다(캐시 금지).** ORT 아레나가 3GB대 네이티브 힙을 세션 닫을 때까지 OS에 반환하지 않는다(실측 3.17GB → 닫으면 0.03GB). 오픈은 1초, 분리는 곡당 수 분이라 재사용 이득이 없다
- **항상 고정 길이 세그먼트**(`Tier.segmentSamples`)로 추론, 마지막 청크는 0 패딩. ONNX가 고정 shape로 export됐다 — 동적 축 금지
- **취소 판정은 코루틴 자신의 Job으로**(`currentCoroutineContext()[Job]`). 서비스 필드를 읽으면 대입 전 null을 취소로 오판하고, 새 작업이 필드를 덮어써 이전 작업이 안 죽는다. 새 분리는 이전 Job을 `join`한 뒤 시작(세션 수 GB가 겹치면 OOM)
- 분리는 MixCache WAV를 입력으로 쓴다 — 별도 raw 디코딩을 다시 만들지 말 것
- ModelManager는 Range 이어받기를 한다. 부분 파일(.tmp)은 네트워크 실패 시 보존하고 무결성 실패 시에만 삭제

**데이터·내보내기**
- **Song 저장은 컬럼별 UPDATE만**(`updateStemLevels`/`Semitones`/`Speed`/`Loop`/`Separation`). `get→copy→update`로 쓰면 먼저 쓴 필드가 날아간다
- **스템 볼륨의 기준은 `stemGainsPacked`.** `muteMask`는 `Stem.muteMaskFromPacked`(0%만 ON)로 파생. AI ON은 `gainArrayFromPacked`(0~1), AI OFF는 체크(0/100) + 보컬 제거 강도
- **내보내기에 배속·A-B를 넣지 않는다.** 연습용 배속·구간과 저장 파일(원곡 템포·전체 길이)을 섞지 말 것

## AI 모델 (GitHub Releases 호스팅)

- htdemucs_6s 6스템 — `ModelConfig.stemOrder`가 `drums/bass/other/vocals/guitar/piano` 순서와 일치해야 한다
- 3종 모두 fp32, 세그먼트만 다름: light 131072 / balanced 262144 / quality 344064 (약 178MB씩). 온디바이스 파일명은 `model-6s.onnx`
- `github.com/hyuunnn/band-mr/releases/download/model-v2/*.onnx` (public이라 익명 다운로드)
- `ModelCatalog.kt`에 SHA-256 핀 — **모델을 다시 올리면 해시 3개 반드시 갱신**
- 가중치는 Meta demucs(MIT) 파생, NewPipeExtractor는 GPL-3.0 → `THIRD_PARTY_NOTICES.md` 고지 유지
- 재export 절차·우회 목록은 `tools/README.md`
