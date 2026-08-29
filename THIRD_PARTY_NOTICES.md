# Third-Party Notices

이 프로젝트에 포함되거나 사용된 서드파티 저작물과 라이선스입니다.

## Demucs 모델 가중치 (ONNX 변환 포함)

`htdemucs_6s` 사전학습 가중치를 ONNX로 변환한 모델 파일
(`releases/tag/model-v2`의 `*.onnx`)은 Meta Platforms가 MIT 라이선스로
배포하는 [facebookresearch/demucs](https://github.com/facebookresearch/demucs)의
가중치에서 파생되었습니다.

```
MIT License

Copyright (c) Meta Platforms, Inc. and affiliates.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## ONNX Runtime

앱 내 추론에 [onnxruntime-android](https://github.com/microsoft/onnxruntime) 사용.
MIT License — Copyright (c) Microsoft Corporation.

## Android Jetpack (Compose, Room, DataStore 등)

Apache License 2.0 — Copyright The Android Open Source Project.
[https://www.apache.org/licenses/LICENSE-2.0](https://www.apache.org/licenses/LICENSE-2.0)

## Kotlin / Kotlin Coroutines

Apache License 2.0 — Copyright JetBrains s.r.o. 및 기여자들.

## Material Design Icons (알림 컨트롤 아이콘)

`res/drawable/ic_replay_5·ic_replay_10·ic_forward_5·ic_forward_10·ic_play·ic_pause`는
[google/material-design-icons](https://github.com/google/material-design-icons)의
경로 데이터를 Android 벡터 드로어블로 변환한 것입니다.
Apache License 2.0 — Copyright Google LLC.

## NewPipeExtractor (유튜브 링크 가져오기)

앱의 `youtube/` 패키지는 [TeamNewPipe/NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
(JitPack `com.github.TeamNewPipe:NewPipeExtractor`)를 사용해 유튜브 영상 정보와
오디오 스트림 URL을 추출합니다. **GNU General Public License v3.0 이후(GPL-3.0+)**,
Copyright © Team NewPipe — https://www.gnu.org/licenses/gpl-3.0.txt

이 라이브러리를 링크하므로 배포되는 APK 전체는 GPL-3.0 호환 조건이 적용됩니다.
전이 의존성: jsoup(MIT), rhino/rhino-engine(MPL-2.0), protobuf-javalite(BSD-3-Clause),
nanojson(Apache-2.0), jsr305(Apache-2.0).

## JUnit

단위 테스트에만 사용. Eclipse Public License 1.0.
