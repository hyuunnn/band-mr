# 분리 모델 ONNX 변환

## 실행

```bash
python3 -m venv /tmp/bandmr-model-venv
source /tmp/bandmr-model-venv/bin/activate
python -m pip install torch torchaudio demucs onnx onnxruntime onnxscript onnxconverter-common pyyaml

cd tools
python export_demucs_onnx.py ./exported htdemucs
python export_demucs_onnx.py ./exported htdemucs_6s
python export_scnet_onnx.py ./exported xl
python export_scnet_onnx.py ./exported xl-ihf
```

## 변환 규칙

- fp32·opset 18·고정 shape·`do_constant_folding=False`를 사용한다. int8/fp16 변환은 사용하지 않는다.
- Demucs는 균형형 262144·품질 344064 샘플, SCNet XL/XL IHF는 262144 샘플이다.
- 복소 STFT/FFT는 실수 연산으로 변환한다. Demucs는 `.models[0]`·`use_train_segment=False`·수동 attention, SCNet은 normalized 사각창·직교 DFT·LSTM을 쓴다.
- SCNet의 학습 청크(485100)는 온디바이스에 쓰지 않는다(메모리 제한).
- 수치 비교는 무음 패딩을 제외한 활성 구간으로 한다.

## 게시

1. 스크립트가 `ALL_OK`로 끝나는지 확인한다. 자동 검증은 패치 전후 PyTorch 오차·고정 입출력 shape·NaN/Inf 및 활성 구간 `corr > 0.99`·`relRMS < 0.1`을 검사한다. `corr=1.0000`은 배포 전 확인할 기대값이다.
2. 생성한 파일을 GitHub Releases `model-v3`에 올린다. 파일명·스템 순서는 스크립트와 ModelCatalog/Tier를 따른다.
3. [ModelCatalog.kt](../app/src/main/java/com/bandmr/app/separation/ModelCatalog.kt)의 SHA-256 핀을 갱신한다.

세부 변환 코드는 [Demucs](export_demucs_onnx.py) · [SCNet](export_scnet_onnx.py)를 참고한다.
