# AGENTS.md — 개발 규칙

Android · Kotlin · Compose 앱. 설정값은 소스, 구현 배경은 KDoc을 참고한다.
이 문서에는 검증 방법과 회귀 방지 규칙만 둔다.

## 빌드·검증

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

- 배포는 release APK. `local.properties`·서명 키·`keystore.properties`는 커밋하지 않는다. adb/sdkmanager는 `/opt/homebrew/bin`.
- AGP 내장 Kotlin을 사용한다. `kotlin.android`를 추가하거나 KSP를 Kotlin과 같은 버전으로 맞추지 않는다.
- 로직 수정 후 `testDebugUnitTest` 필수. DSP 수정은 기존 구현과 수치·바이트 비교 테스트를 추가한다.
- 연결된 SM-S931N(Android 16)에서 실기기 검증 가능. 미연결 시 빌드·JVM 테스트로 검증한다.

## 코드 지도

`app/src/main/java/com/bandmr/app/` 기준.

| 경로 | 담당 |
|---|---|
| `audio/` | AudioTrackEngine, SourceWavPlayer, StemMixPlayer, PlayerController, MixCache, DSP·WAV·파형 |
| `io/` | FilePromote, CacheStorage |
| `separation/` | 모델 관리, MixCache 입력 분리, StemFiles, SeparationCommit, SepBus |
| `playback/` | PlaybackService, MediaSession·알림, SkipButton 공통 정의 |
| `export/` | 믹스·스템 WAV 저장, writeMixWav |
| `youtube/` | NewPipeExtractor → 원본 파일 → Song → MixCache |
| `data/`, `ui/` | Room·DataStore, 라이브러리·플레이어·설정 |

모델 변환은 [tools/README.md](tools/README.md), 라이선스는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## 패키징·화면

- ABI는 `arm64-v8a`만. `material-icons-extended`는 추가하지 않고 필요한 아이콘은 벡터 리소스로 둔다.
- R8을 켜면 NewPipeExtractor/rhino keep 규칙이 필요하다.
- 첫 프레임 전에 테마는 `startupDesign()`, 곡 목록은 `librarySongs()`로 읽는다.
- 시작 화면은 `setSplashScreenTheme(Theme.BandMR.*)`로 설정한다. 기존 스타일 이름을 유지하고 배경색은 `Theme.kt`와 맞춘다. 아이콘은 따로 지정하지 않는다.
- 시스템 바 아이콘은 `SideEffect`에서 앱 테마로 재설정한다. 구성 변경 시에도 적용해야 한다.

## 재생

- AI OFF는 MixCache WAV만 재생한다. 압축 원본의 직접 스트리밍은 금지한다.
- 종료는 `PlayerController.release()`로 모은다. 싱글턴 코루틴 스코프는 취소하지 않는다. 홈 이동은 종료가 아니다.
- 해제 시 `releaseEpoch`를 올려 열린 화면의 엔진 재준비를 알린다. 종료 중 알림 재등록은 `PlaybackService.stopping`으로 막는다.
- 알림·잠금화면·블루투스 명령은 `setPlaying(Boolean)`. 토글은 화면의 `playPause()`만 사용한다.
- 재생 FGS는 `setPlaying`의 재생 의도 시점에 시작한다. `isPlaying` 관찰로 기동하지 않는다.
- 곡끝 콜백은 실행 시점에 `released`를 확인한다. 이 플래그는 `release()`에서만 세운다.
- `StemMixPlayer.renderChunk`는 양수를 반환한다. 입력이 없으면 무음, 닫힌 리더 예외는 스템별로 처리한다.
- `ensureLoaded`는 `separatedTier`·`stemsDir` 변경 시 믹서를 다시 연다. 게인·키·배속은 setter로 적용한다.
- A-B 반복은 오디오 스레드에서 처리한다. 곡 전환은 `setLoop(..., apply=false)` 후 새 엔진에 적용한다.
- write 후 트랙 재시작은 `stateLock` 안에서 `isPlaying`을 확인해 pause와 직렬화한다.
- pause의 flush만 제거하지 않는다. 변경하려면 재개 시 트랙 시작, `stopEngine`의 join 전 flush, 스테일 청크 flush도 함께 처리한다. 현재 최대 버퍼만큼의 위치 스킵은 허용된 동작이다.
- 배속은 `AudioTrack.setPlaybackParams(speed, pitch=1)`로만 적용하고 시크·재개 때 다시 건다.

## DSP

- 시크는 `processorsDirty`만 세운다. 오디오 스레드가 `framePos`·곡끝 판정·렌더 전에 제자리 리셋한다. UI 스레드에서 DSP를 리셋하지 않는다.
- 체인 객체 교체는 muteMask 변경 시에만 한다. `chain`은 `@Volatile`을 유지한다.
- `SpectralStage.reset()`은 `magHist`까지 비운다. 리셋 출력은 새 체인 출력과 같아야 한다.
- 0반음 피치는 패스스루. 기본 형식은 interleaved stereo PCM16이며 모노는 `chCount=1`을 처리한다.
- WAV·FOURCC는 little-endian. 파이프라인과 ms↔프레임 수학은 `PIPELINE_SAMPLE_RATE`(44.1kHz) 고정이며 불일치 스템은 제외한다.

## 파일·캐시

- 내부 산출물 승격은 `FilePromote`를 사용한다. 복사 실패 시 목적지를 지우며, 분리 중 기존 정식 스템을 먼저 지우지 않는다.
- MixCache는 디코딩을 `.part`의 WavWriter에 직접 쓰는 1패스. close 후 승격하고, 0프레임은 거부한다(44바이트 헤더는 일반 파일 검사·WavReader를 통과한다).
- 열 수 없거나 길이가 0인 캐시는 `MixCache.delete`로 WAV·peaks를 함께 버린다.
- 캐시 표시 용량·버튼·삭제 대상은 `CacheStorage.clearable*`로 맞춘다. 집계·삭제는 같은 술어를 쓰고, 일반 정리에서 작업 중 `.part/.tmp`를 제외한다.
- 전체 스템 삭제는 분리를 취소하고 `withStemMutation` 안에서 `.part` 포함 삭제(`includeInFlight`)와 DB 해제를 함께 수행한다.
- 스템 경로는 `StemFiles`로 통일한다. 분리·고아 정리·용량 집계·삭제에서 별도로 조합하지 않는다.
- 파형 `.peaks`는 FilePromote 대상에서 제외한다. 막대 수·원본 크기 불일치나 손상 시 재계산하며 표시용 파일 오류는 전파하지 않는다.
- 파형 상태는 songId로 remember한다. 준비 상태 변화로 비우지 않으며 캐시 대기는 `MixCache.awaitReady`를 쓴다.

## 분리·유튜브

- 분리·내보내기 입력은 MixCache WAV. 별도 raw 디코딩을 만들지 않는다.
- `OrtSession`은 분리마다 열고 닫는다. 세션 캐시는 금지한다.
- 추론은 `Tier.segmentSamples` 고정 shape, 마지막 청크는 0 패딩. 동적 축은 사용하지 않는다.
- 취소는 `currentCoroutineContext()[Job]`으로 판정한다. 새 분리는 이전 Job을 join한 뒤 시작한다.
- 마지막 추론 후·결과 반환 전에도 취소를 확인한다. 승격·DB 갱신은 `commitSeparation`의 짧은 `NonCancellable` 구간에서 확정한다. 추론 전체를 감싸지 않는다.
- `SepBus`는 마지막 시도의 진행·오류만 담고, 완료 여부는 `Song.isSeparated`로 판단한다.
- 모델 다운로드는 Range 이어받기. `.tmp`는 네트워크 실패 시 보존하고 무결성 실패 시 삭제한다.
- 유튜브 작업은 `importMutex`로 정리까지 직렬화하고 작업별 임시 파일을 사용한다. 늦은 상태 콜백은 현재 활성 Job만 허용한다.
- 시작 상태 `Resolving`은 Job 등록 후 `next.start()` 전에 발행한다. 대기 UI 누락과 즉시 실패 상태 덮어쓰기를 막는다.
- 유튜브 임포트·모델 다운로드는 `appScope`에서 실행한다(FGS 아님).

## 데이터·내보내기·모델

- Song 저장은 컬럼별 UPDATE만 사용한다. `get → copy → update`는 금지한다.
- 볼륨 기준은 `stemGainsPacked`. AI ON 게인과 AI OFF 체크·muteMask는 `Stem` 변환 함수로 파생한다. DB의 muteMask 컬럼은 이전 마이그레이션 전용이다.
- 내보내기는 전체 곡·원곡 템포. 볼륨·키만 적용하고 배속·A-B는 제외한다. 피치는 `PitchShifter.renderTo`를 공유한다.
- `writeMixWav`는 저장마다 고유 임시 WAV를 쓰며, close·취소 확인 후 목적지로 복사한다.
- `ModelConfig.stemOrder`는 `Tier.stemOrder`와 일치시킨다. 모델 규격·경로는 ModelCatalog/Tier를 기준으로 한다.
- 모델 재업로드 시 `ModelCatalog.kt`의 SHA-256 핀을 갱신한다. [라이선스 고지](THIRD_PARTY_NOTICES.md)는 유지한다.
- 이전 id `light`·`balanced`·`quality`는 6스템으로 읽는다. `Tier.of(FOUR, BALANCED)`는 Demucs 4스템이다.
