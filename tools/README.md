# 분리 모델 → ONNX 변환

앱이 쓰는 온디바이스 모델을 만드는 절차. 모델을 다시 export할 때만 필요하고,
앱 코드를 고치는 작업과는 무관하다 — 그래서 AGENTS.md에서 이 파일로 분리했다.

사용법:

```bash
python export_demucs_onnx.py <출력폴더> htdemucs      # Demucs 4스템
python export_demucs_onnx.py <출력폴더> htdemucs_6s   # Demucs 6스템
python export_scnet_onnx.py <출력폴더>               # SCNet XL IHF (4스템, 485100)
```

## 그대로는 export 불가 — 아래 우회가 모두 필요

1. `torch.stft/istft` complex 반환 → `demucs.htdemucs.spectro/ispectro`를 re/im 쌍 텐서 버전으로 교체
   - STFT: Conv1D(stride=hop) 투영 ×`win_length^-0.5`, iSTFT: DFT 행렬곱 + gather OLA ×`√win_length` (torch 배율 실험 확인값)
2. `get_model()` 반환은 BagOfModels 감싸개 → `.models[0]` 사용 + `use_train_segment=False`
3. `nn.MultiheadAttention`은 융합 연산자 때문에 수동 분해 버전으로 교체
4. cac=True 경로의 `_magnitude/_mask`는 view_as_real/complex만 대체하면 됨
5. opset 18 필요(col2im 등), `do_constant_folding=False` 권장
6. int8 동적 양자화는 활성 범위 큰 입력에서 심각하게 깨짐(corr 0.01대) → 사용하지 말 것.
   fp16 컨버터(onnxruntime/onnxconverter_common)도 이 그래프에선 dtype 불일치 발생 → **fp32 그대로 사용**
7. 검증 시 무음 패딩 구간이 corr을 망가뜨리므로 **활성 구간만** 비교할 것

## 환경

python venv는 임시 폴더라 사라졌을 수 있음. 재구성:

```bash
python3 -m venv && pip install torch torchaudio demucs onnx onnxruntime onnxscript onnxconverter-common pyyaml
```

## export 후 할 일

- 세그먼트 2종(balanced 262144 / quality 344064)을 fp32로 뽑는다. 4스템 약 236MB, 6스템 약 178MB. 경량은 쓰지 않는다
- 4스템·6스템·SCNet 모두 GitHub Releases `model-v3`에 올리고 **`ModelCatalog.kt`의 SHA-256 핀을 갱신**한다 (안 하면 다운로드가 무결성 실패로 전부 삭제된다)
- 원본 PyTorch와 활성 구간 corr을 비교해 1.0000을 확인한다

## SCNet XL IHF

ZFTurbo `model_scnet_ep_36_sdr_10.0891.ckpt` (MUSDB-only). 입출력은 Demucs와 같다.

그대로는 export 불가:

1. `torch.stft/istft` complex — 사각창(원본 `window` 키 없음)·normalized STFT를 re/im 쌍으로 교체
2. `FeatureConversion`의 `rfft`/`irfft`+complex — 직교 정규화 DFT 행렬곱
3. 세그먼트는 학습 청크 **485100만**. Demucs 6s/7.8s를 넣지 말 것
4. LSTM은 ORT Android가 지원한다(Tran으로 바꾸지 않음)
5. fp32, opset 18, `do_constant_folding=False`. 파일명은 `scnetxl-ihf-fp32.onnx` — `model-4s.onnx`를 덮지 않는다

올린 위치는 `model-v3` (`scnetxl-ihf-fp32.onnx`, 약 287MB). SHA-256 핀을 `S4_SCNET_XL_IHF`에 넣는다.

앱이 기대하는 스템 순서·파일명 등은 AGENTS.md의 "AI 모델" 절이 기준이다.
