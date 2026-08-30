# AGENTS.md — AI 에이전트용 프로젝트 가이드

밴드 연습용 MR 제거 앱 (Android, Kotlin + Compose). 곡에서 보컬/드럼/베이스/기타/피아노/그외를 줄이거나 제거한 반주로 합주 연습.

## 빌드 / 테스트

```bash
# JDK 17: temurin-17(/Library/Java) 사용. homebrew openjdk@17 경로는 현재 존재하지 않음(2026-08 확인)
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # → /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # local.properties가 없으면 필수

./gradlew :app:testDebugUnitTest      # 단위 테스트 (119개)
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
```

- Android SDK: `/opt/homebrew/share/android-commandlinetools` (`ANDROID_HOME` 또는 local.properties의 `sdk.dir`로 지정, local.properties는 커밋 금지). adb/sdkmanager는 `/opt/homebrew/bin`에 있음
- compileSdk 37 / targetSdk 36, minSdk 31, Gradle 9.5.0, AGP 9.3.2(빌트인 Kotlin — kotlin.android 플러그인 없음), Kotlin 2.4.10, KSP 2.3.11(Kotlin과 독립 버전), ORT Android 1.29.0 (media3/ExoPlayer는 제거됨 — 아래 아키텍처 참조)
- 실기기(SM-S931N, Android 16)가 adb로 연결되면 실기기 검증 가능. 그 외 런타임 검증은 빌드+JVM 단위테스트

### 패키징 (APK 크기)

- **`abiFilters = arm64-v8a` 단일 ABI 고정.** ONNX Runtime 네이티브가 ABI당 23~38MB라 4종을 넣으면 APK가 100MB 이상 불어난다. armeabi-v7a는 분리 추론의 3GB대 네이티브 힙을 담을 수 없고, x86/x86_64는 에뮬레이터 전용이다(Apple Silicon 에뮬레이터도 arm64-v8a라 개발에 지장 없음)
- **`material-icons-extended`를 다시 넣지 말 것.** AAR이 34MB인데 쓰는 아이콘은 12개뿐이고 `isMinifyEnabled=false`라 R8이 걷어내지 못해 전량이 dex에 실린다. core에 없는 글리프(pause · music_note · link · forward/replay 5·10)는 `res/drawable` 벡터를 `painterResource`로 쓴다 — 알림(`Notification.Action`·`PlaybackState.CustomAction`)이 쓰는 것과 같은 파일이다
- 현재 debug APK 약 71MB (arm64 .so 32MB + 미축소 dex 37MB). 더 줄이려면 R8을 켜야 하는데 NewPipeExtractor/rhino keep 규칙이 필요하다

## 검증 규칙 (중요)

- 로직 수정 후 반드시 `testDebugUnitTest` 실행. DSP/WAV/청크 수학은 전부 JVM 순수 Kotlin이라 유닛테스트로 검증한다
- 신규 DSP 코드는 "원본 구현과 수치 비교" 테스트를 함께 추가할 것 (과거 WavReader FOURCC 버그, SpectralStage 크래시, WavWriter 빅엔디안 버그가 모두 테스트로 발견됨)

## 아키텍처 요약

```
audio/       재생 골격은 AudioTrackEngine(오디오 스레드 루프·A-B·시크·배속 공통 베이스)
             AI OFF: SourceWavPlayer(원본 WAV 캐시 재생 + DspChain 실시간 적용) / AI ON: StemMixPlayer(스템 믹서)
             PlayerController는 모드별 엔진을 active(=aiMode ? mixer : source) 하나로 다룬다.
             재생/시크/위치는 active, 파라미터(키·배속·A-B·게인)는 eachEngine으로 양쪽에 걸어 모드 전환 후에도 유지
             스템 WAV 열기는 StemWavSet 공용(누락 스킵·44.1k 불일치 제외·최장 스템 기준 길이) — 재생과 내보내기가 같은 규칙
             배속은 PlaybackSpeed → AudioTrack PlaybackParams (키와 독립, 곡마다 Song.speed 저장)
             점프는 PlaybackSkip(±5/±10초) → PlayerController.skipBy (0~duration 클램프)
             A-B는 PlaybackLoop(최소 0.5초) → 엔진이 B에서 A로 seek. Song.loopStartMs/EndMs 저장, 내보내기 제외
             파형은 WaveformPeaks(MixCache WAV 막대 RMS, 곡 내 최댓값 정규화 — 리미터 음원도 윤곽 보이게) → WaveformBar. 캐시 없으면 슬라이더, MixCache.prepare 승격 뒤 awaitReady로 자동 전환
             MixCache: 원본을 44.1kHz 스테레오 PCM16 WAV로 filesDir/mixcache에 보관. 완료는 CacheReadyGate 신호(폴링 아님)
io/          FilePromote: .part/.tmp → 정식 경로 승격(rename 실패 시 copy 폴백). MixCache·스템·모델·유튜브 원본이 모두 이걸 지난다
separation/  MixCache WAV → DemucsSeparator(ONNX) → 스템 WAV 캐시
             AudioDecode는 MixCache 전용(MediaCodec→44.1k 스트림). 분리·내보내기는 MixCache WAV를 읽는다
             SepBus는 "마지막 시도 결과"만 담는다(진행/오류). 완료는 Song.isSeparated가 갖고, 성공 시 Idle로 되돌린다
playback/    PlaybackService(백그라운드 재생 FGS + MediaSession 알림)
             알림은 OS 미디어 카드로 그려진다 — 카드 버튼은 알림 액션이 아니라 PlaybackState의
             커스텀 액션에서 나오고, 슬롯 순서가 [custom0, prev, 재생, next, custom1]이라 등록 순서를 역산해야 한다
             점프 4종은 SkipButton enum 한 곳에 정의(액션·아이콘·라벨·이동량) — 알림 액션과 커스텀 액션이 같은 표를 쓴다
             앱에서 시크하면 PlayerController.seekEpoch로 진행바를 갱신(안 하면 옛 위치에 남음)
export/      믹스/스템 WAV 내보내기. 믹스는 스템 게인+키만 반영하고 배속·A-B는 넣지 않음
             피치 적용은 PitchShifter.renderTo 공용 — 재생과 내보내기가 같은 순서·클램프를 지나야 결과가 같다
youtube/     유튜브 링크로 곡 추가: NewPipeExtractor로 오디오 스트림 추출·다운로드(filesDir/sources)
             → Song(file:// URI) 등록 → 기존 MixCache 파이프라인 그대로 사용
             임포트·모델 다운로드는 appScope(FGS 아님). 화면을 열어 둔 채 받아야 함(앱을 내리면 끊길 수 있음)
data/        Room(Song v4: stemGainsPacked·muteMask/키/배속/loopStartMs/EndMs), DataStore(설정)
             muteMask 컬럼은 v3 이전 데이터 마이그레이션용 — 재생·내보내기는 이 값을 읽지 않는다(불변식 참조)
ui/          Compose (라이브러리/플레이어/설정). 파형 시크는 WaveformBar
tools/       모델 변환 스크립트 (절차는 tools/README.md)
```

핵심 불변식:
- **AI OFF 재생은 압축 원본 스트리밍 금지 — 반드시 MixCache의 WAV를 재생한다.** 일부 기기(SM-S931N, Android 16 펌웨어)에서 MediaCodec 비동기 스트리밍 디코딩이 무음/노이즈로 깨진다(비동기 큐잉 강제 비활성화로도 불가 확인). 캐시는 곡 추가/앱 시작 때 백그라운드로 만들고, 없으면 재생 시점에 준비 후 자동 이어재생(preparingSongId 상태로 UI 표시)
- 유튜브 임포트 원본도 압축 파일(file:// URI)일 뿐 동일 규칙 적용 — sources/<videoId>.<ext>를 두고 MixCache WAV로만 재생. NewPipeExtractor는 GPL-3.0이라 배포 시 THIRD_PARTY_NOTICES.md 고지 필수
- **PlayerController.release()는 코루틴 스코프를 절대 cancel하지 않는다.** 싱글턴 컨트롤러의 스코프를 취소하면 이후 캐시 준비가 조용히 무시되어 "준비 중" 문구가 영구 노출된다(실제 발생한 버그)
- **DemucsSeparator는 항상 고정 길이 세그먼트**(`Tier.segmentSamples`)로 추론. 마지막 청크는 0 패딩. ONNX 모델도 고정 shape로 export됨 — 동적 축 쓰면 안 됨
- 오디오 처리 좌우로 interleaved stereo PCM16이 기본. 모노는 DspChain/SpectralStage에서 chCount=1 분기
- WAV I/O는 little-endian. FOURCC('RIFF' 등)은 LE int로 읽음 (`WavIo.kt` 상수 참조)
- PlayerController가 오디오 포커스·이어폰 분리(BECOMING_NOISY)를 관리
- **재생 종료 경로는 `PlayerController.release()` 하나다.** 알림 지우기(deleteIntent)·최근 앱에서 앱 치우기(`onTaskRemoved`)·곡 삭제가 모두 이걸 지난다. 홈으로 나가는 것은 종료가 아니다(포그라운드 서비스는 태스크가 사라져도 살아남으므로 명시적으로 끊어줘야 한다)
- **`release()`는 `releaseEpoch`를 올려 화면이 엔진을 다시 준비하게 한다.** 알림으로 종료해도 플레이어 화면의 로드 조건(곡 id·모드)은 그대로여서, 이 신호가 없으면 재생 버튼이 영구 무반응이 된다(`release()`가 `currentSong`을 비워 `setPlaying`이 곧바로 빠져나간다). 반대로 종료 절차 중에는 `PlaybackService.stopping` 가드로 알림 재등록을 막는다(안 그러면 방금 지운 알림이 되살아난다)
  - 트레이드오프: 화면이 열려 있으면 종료 직후 엔진이 곧바로 다시 만들어진다 — 즉 "종료"가 실제로 끝내는 것은 재생·알림·서비스이고, 엔진 해제는 화면이 보이는 동안 무효다. 파일 핸들 1개와 약 180KB만 다시 잡히고(스레드·AudioTrack은 `play()` 때 생긴다) 그 대가로 파형 시크·위치 표시가 계속 살아 있으므로 의도한 선택이다. `nowPlayingTitle`이 되살아나는 것도 여기서 나온다
- **알림·잠금화면·블루투스의 재생/일시정지는 `PlayerController.setPlaying(Boolean)`(절대 명령)으로 받는다.** 상태를 읽어 `playPause()`로 토글하면 읽는 순간과 실행 사이에 자동 일시정지(포커스 상실·이어폰 분리)가 끼면 명령이 뒤집힌다. 현재 상태를 읽는 지점은 화면 버튼용 `playPause()` 한 곳뿐이어야 한다
- **`AudioTrackEngine.release()`는 이후의 곡끝 통보를 막는다(`released` 플래그).** `finish()`가 `mainHandler`로 올린 콜백이 해제 뒤에 도착하면, 콜백에 어느 엔진이 보냈는지가 없어서 이미 교체된 새 엔진의 재생을 UI에서 일시정지로 뒤집고 오디오 포커스까지 반납한다. 확인은 post 시점이 아니라 **콜백 실행 시점**에 해야 그 사이 해제를 잡는다. 플래그는 `stopEngine`이 아니라 `release`에서만 세울 것 — `stopEngine`은 `StemMixPlayer.load`가 인스턴스 재사용을 위해 부르므로, 거기서 세우면 이후 정상 종료를 영구히 못 알린다
- **`StemMixPlayer.renderChunk`는 0 이하를 돌려주지 않는다.** 엔진은 `produced <= 0`을 곡 끝으로 읽는다. 진행 중인 렌더 바퀴가 teardown과 겹치면 `stems`가 null이거나 리더가 닫혀 있을 수 있는데, 그때 0을 돌려주면 위의 늦은 통보 문제가 그대로 재현된다 — 읽을 게 없으면 무음을 내고, 닫힌 리더의 읽기 예외는 스템 단위로 흡수한다(`loop()`에 catch가 없어 그대로 두면 오디오 스레드가 죽는다)
- 시크/마스크 변경 시에는 DspChain 상태를 반드시 리셋할 것. **시크는 재할당이 아니라 제자리 리셋이다** — `seekToFrame`이 `processorsDirty` 플래그만 세우고, 오디오 스레드가 렌더 직전에 `shifter.reset()` + `resetProcessors()`(→ `DspChain.reset()`)로 소비한다. SpectralStage는 스레드 안전하지 않아 UI 스레드에서 reset하면 FIFO 인덱스가 음수가 되어 죽고(파형 스크럽은 초당 10회 시크), 플래그 소비가 `framePos`·곡끝 판정보다 뒤에 오면 방금 비운 체인에 시크 이전 오디오가 들어간다. muteMask 변경만 예외로 객체 교체(공개 전에 마스크를 걸어 경쟁 없음, `chain`은 `@Volatile`)
- **`SpectralStage.reset()`은 magHist까지 비워야 한다.** histPos/histFill은 인스턴스 단위인데 증가는 채널마다 일어나서, 워밍업 중 해당 채널이 안 쓴 슬롯을 읽는다 — 잔여값이 남으면 새 인스턴스와 출력이 달라진다. `DspChainResetTest`("리셋 출력 == 새 체인 출력, 바이트 동일")가 이 계약을 지킨다
- **스템 볼륨의 기준은 `stemGainsPacked`.** UI·저장·내보내기는 퍼센트만 바꾸고, `muteMask`는 `Stem.muteMaskFromPacked`(0%만 ON)로 파생한다. AI ON 믹서는 `gainArrayFromPacked`(0~1), AI OFF는 체크(0/100) + 보컬 제거 강도
- PitchShifter는 0반음일 때 패스스루다(지연 제거). 비율 분기 로직 건드릴 때 주의
- 재생 배속은 AudioTrack.setPlaybackParams(speed, pitch=1)만 사용한다. 오프라인 WSOLA/타임스트레치는 쓰지 않음. 시크·재생 재개 때 배속을 다시 걸 것(일시정지 중 적용이 실패하는 기기 있음)
- **A-B 랩은 오디오 스레드에서만 한다.** UI 폴링으로 B→A 하면 백그라운드에서 끊긴다. 시크/점프는 `PlaybackLoop.clampSeek`로 구간 안에 가둔다. 곡 전환 때는 `setLoop(..., apply=false)` 후 새 엔진에 적용할 것(이전 곡 엔진에 먼저 걸면 안 됨)
- **파형 데이터는 songId 기준 remember.** `preparingSongId`가 바뀔 때 null 하면 슬라이더가 깜빡인다. 캐시가 아직 없으면 `MixCache.awaitReady`로 rename 완료 신호만 기다린다(파일 폴링 금지)
- **Song 저장은 컬럼별 UPDATE만 쓴다**(`updateStemLevels`/`Semitones`/`Speed`/`Loop`/`Separation`). 볼륨·키·배속·A-B·분리 완료를 `get→copy→update`로 쓰면 먼저 쓴 필드가 날아간다
- **내보내기는 배속·A-B를 넣지 않는다.** 연습용 배속/구간과 저장 파일(원곡 템포·전체 길이)을 섞지 말 것
- ModelManager 다운로드는 Range 이어받기를 한다 — 부분 파일(.tmp)은 네트워크 실패 시 보존하고 무결성 실패 시에만 삭제
- **스템 분리는 MixCache WAV를 입력으로 쓴다.** 캐시가 있으면 원본을 다시 디코딩하지 않는다. 내보내기(AI OFF 믹스)도 같은 캐시 WAV를 읽는다 — 별도 raw 디코딩을 다시 만들지 말 것
- **MixCache 준비는 1패스다.** `AudioDecode.decodeTo44kStereo`가 청크 싱크로 흘려보내고 `WavWriter`가 `.part`에 바로 쓴다(헤더 크기는 close 때 패치). 중간 raw 파일을 만들면 디스크 쓰기와 피크 사용량이 2배가 된다(4분 곡 80MB vs 40MB). 승격은 반드시 close 뒤 — 그 전에 공개하면 헤더 크기가 0인 WAV가 재생에 쓰인다. 1패스 출력이 옛 2패스와 바이트 동일함은 `MixCacheWavTest`가 고정한다
- **열 수 없는 캐시 WAV는 즉시 버린다(`PlayerController.openSourceOrDiscardCache`).** `MixCache.prepare`가 `exists()`만 보고 반환하므로, 열기 실패를 "캐시 없음"으로만 흘리면 준비 → 실패 → 준비가 영구히 겉돈다(표시도 실패 알림도 없이 재생 버튼만 무반응). 파일이 있는데 `WavReader`가 거부하면 `MixCache.delete`로 wav·peaks를 함께 지우고 재생성한다 — 파형 캐시 검사는 원본 **크기** 기준인데 재생성 결과가 원본과 바이트 수까지 같을 수 있어(실측 확인) 손상본 기준 막대가 살아남는다. 준비 직후의 열기 실패는 `prepareFailedSongId`로 노출한다
- **파형 막대는 `mixcache/<songId>.peaks`에 캐시한다.** 결과가 480 float(1.9KB)인데 계산은 WAV 전체 스캔이라 플레이어 재진입마다 훑을 이유가 없다. `WaveformPeaks.fromWavCached`가 막대 수·원본 크기를 함께 저장해 불일치·손상 시 다시 계산한다. 파일명이 `<songId>.peaks`라 `MixCache.delete`와 `cleanUpOrphans`의 `substringBefore('.')` 규칙에 그대로 걸린다
- **분리 결과는 `stems/<songId>.part`에 쓰고 성공했을 때만 `stems/<songId>`로 승격한다(`FilePromote.directory`).** 정식 디렉터리를 먼저 지우면 취소·실패 시 DB의 분리 완료 표시(stemsDir)만 남아 스템 없는 곡이 된다
- **임시 산출물 승격은 반드시 `FilePromote`를 쓴다.** MixCache WAV·스템 디렉터리·모델 파일·유튜브 원본이 전부 "완성된 뒤에만 정식 이름으로 공개" 규약을 공유한다. `renameTo`만 쓰면 목적지가 이미 있거나 같은 마운트인데도 false를 돌려주는 기기에서 갈라진다(과거 MixCache만 폴백이 없었다). **복사 폴백이 도중에 실패하면 목적지를 반드시 지운다** — rename은 원자적이지만 복사는 잘린 결과를 남길 수 있고, 이 앱은 파일 존재를 완성 신호로 쓰므로 손상 캐시가 재생에 쓰인다(`FilePromoteTest`는 "실패한 승격은 목적지를 남기지 않는다"는 결과만 고정한다 — 복사가 중간에 끊기는 상황 자체는 유닛테스트로 주입할 수 없다)
- **분리 취소 판정은 코루틴 자신의 Job으로 한다(`currentCoroutineContext()[Job]`).** 서비스 필드를 읽으면 대입 전 null을 취소로 오판하고, 취소 직후 새 작업이 필드를 덮어써 이전 작업이 영원히 안 죽는다. 새 분리는 이전 Job을 `join`한 뒤 시작(ONNX 세션 수 GB가 겹치면 OOM). 서비스 종료는 `stopSelf(lastStartId)` + 현재 Job일 때만
- **오디오 파이프라인은 44.1kHz(`PIPELINE_SAMPLE_RATE`) 고정 가정.** MixCache WAV·Demucs 스템 모두 이 레이트로 생성되며, 불일치 스템은 재생·내보내기에서 제외된다. PlayerController의 ms↔프레임 수학도 이 값에 묶인다
- **OrtSession은 분리 1회마다 열고 닫는다(캐시 금지).** ORT 아레나는 추론 중 3GB대 네이티브 힙을 잡고 세션을 닫을 때까지 OS에 반환하지 않는다(SM-S931N 실측: 분리 중 3.17GB → 완료 후에도 3.17GB 유지, 세션 닫으면 0.03GB). 세션 오픈은 1초 남짓인데 분리는 곡당 수 분이라 재사용 이득이 없고, 재생·내보내기는 세션을 쓰지 않는다. 모델 파일 경로 기반 캐시는 "모델 삭제 후 재다운로드 시 옛 세션 재사용" 버그도 만든다

## AI 모델 (GitHub Releases 호스팅)

- htdemucs_6s (6스템: drums/bass/other/vocals/guitar/piano — `ModelConfig.stemOrder`가 이 순서와 일치해야 함)
- 3종 모두 fp32, 세그먼트만 다름: light 131072 / balanced 262144 / quality 344064 (약 178MB씩)
- URL: `github.com/hyuunnn/band-mr/releases/download/model-v2/*.onnx` — 저장소 public이라 익명 다운로드 됨
- 라이선스: 가중치는 Meta의 demucs(MIT)에서 파생 — 고지는 `THIRD_PARTY_NOTICES.md` 유지할 것
- `ModelCatalog.kt`에 SHA-256 핀. **모델을 다시 올리면 해시 3개 반드시 갱신**
- 온디바이스 모델 파일명은 `model-6s.onnx`
- 원본 PyTorch 대비 활성 구간 corr=1.0000 확인 완료

## htdemucs_6s ONNX 변환

절차·우회 목록은 `tools/README.md` 참조 (모델을 다시 export할 때만 필요).

## Git

- 커밋 작성자: 저장소 로컬 설정으로 `hyuunnn <15611739+hyuunnn@users.noreply.github.com>` 지정됨 (전역 설정 아님)
- 원격: `origin = github.com/hyuunnn/band-mr` (public)
