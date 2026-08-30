# htdemucs_6s → ONNX 변환

앱이 쓰는 온디바이스 모델(`model-6s.onnx`)을 만드는 절차. 모델을 다시 export할 때만 필요하고,
앱 코드를 고치는 작업과는 무관하다 — 그래서 AGENTS.md에서 이 파일로 분리했다.

사용법: `python export_demucs_onnx.py <출력폴더>` (htdemucs_6s 전용)

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
python3 -m venv && pip install torch torchaudio demucs onnx onnxruntime onnxscript onnxconverter-common
```

## export 후 할 일

- 세그먼트 3종(light 131072 / balanced 262144 / quality 344064)을 fp32로 뽑는다 — 약 178MB씩
- GitHub Releases에 올리고 **`ModelCatalog.kt`의 SHA-256 핀 3개를 갱신**한다 (안 하면 다운로드가 무결성 실패로 전부 삭제된다)
- 원본 PyTorch와 활성 구간 corr을 비교해 1.0000을 확인한다

앱이 기대하는 스템 순서·파일명 등은 AGENTS.md의 "AI 모델" 절이 기준이다.
