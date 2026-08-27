#!/usr/bin/env python3
"""htdemucs_6s -> 복소수 없는 ONNX(fp32) 변환 + 검증.

사용법: python export_demucs_onnx.py <출력폴더>
6스템: drums/bass/other/vocals/guitar/piano.

torch.stft/istft는 complex dtype을 반환해 ONNX export가 불가능하다.
demucs.spec의 spectro/ispectro를 실수(re/im 쌍) 연산으로 재구현해 교체한다.
htdemucs_6s는 cac=True가 기본이라 complex 사용이 view뿐이므로 이 치환만으로
원본과 수치 동등한 동적 그래프가 만들어진다.

- STFT: reflect 패딩 후 Conv1D(stride=hop)로 창+DFT 투영 동시 수행
- iSTFT: DFT 행렬곱 + Col2im(fold) 기반 OLA
- 정규화 배율은 torch와 실험적으로 대조해 확인한 값 사용
  (stft: ×win_length^-0.5 / istft: ×√win_length)

int8 동적 양자화는 음악 입력에서 심각하게 왜곡되고, fp16 컨버터도 이 그래프에서
dtype 불일치를 일으키므로 **fp32 그대로 배포**한다(세그먼트 길이로만 등급 구분).
"""
import hashlib
import math
import os
import sys
import time

import numpy as np
import torch as th
import torch.nn.functional as F

USAGE = "사용법: python export_demucs_onnx.py <출력폴더>"
if len(sys.argv) < 2:
    print(USAGE, file=sys.stderr)
    sys.exit(2)
if len(sys.argv) > 2 and sys.argv[2] != "htdemucs_6s":
    print(f"{USAGE}\n이 스크립트는 htdemucs_6s만 변환합니다.", file=sys.stderr)
    sys.exit(2)
OUT_DIR = sys.argv[1]
MODEL_NAME = "htdemucs_6s"
MODEL_TAG = "htdemucs6s"
os.makedirs(OUT_DIR, exist_ok=True)


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def spectro_pair(x, n_fft=512, hop_length=None, pad=0):
    """torch.stft(normalized=True, center=True)와 수치 동등. 반환 [..., K, F, 2]."""
    *other, length = x.shape
    x = x.reshape(-1, length)
    nf = n_fft * (1 + pad)
    hop = hop_length or nf // 4
    win = th.hann_window(nf, device=x.device)

    kv = th.arange(nf // 2 + 1, dtype=th.float32, device=x.device)
    nv = th.arange(nf, dtype=th.float32, device=x.device)
    ang = (2.0 * math.pi / nf) * th.outer(kv, nv)  # [K, nf]
    w_cos = th.cos(ang) * win[None, :]
    w_sin = -th.sin(ang) * win[None, :]
    weight = th.cat([w_cos, w_sin], dim=0) * (float(nf) ** -0.5)  # [2K, nf]

    xp = F.pad(x.unsqueeze(1), (nf // 2, nf // 2), mode="reflect").squeeze(1)
    proj = F.conv1d(xp.unsqueeze(1), weight.unsqueeze(1), stride=hop)  # [B, 2K, F]
    K2 = proj.shape[1] // 2
    re = proj[:, :K2]
    im = proj[:, K2:]
    out = th.stack([re, im], dim=-1)  # [B, K, F, 2]
    _, freqs, frames = out.shape[:3]
    return out.view(*other, freqs, frames, 2)


def ispectro_pair(z, hop_length=None, length=None, pad=0):
    """torch.istft(normalized=True, center=True)와 수치 동등. 입력 [..., K, F, 2]."""
    *other, freqs, frames, _ = z.shape
    n_fft = 2 * freqs - 2
    hop = hop_length or n_fft // 4
    win_len = n_fft // (1 + pad)
    win = th.hann_window(win_len, device=z.device)

    re, im = z[..., 0], z[..., 1]  # [..., K, F]
    kv = th.arange(freqs, dtype=th.float32, device=z.device)
    nv = th.arange(n_fft, dtype=th.float32, device=z.device)
    ang = (2.0 * math.pi / n_fft) * th.outer(nv, kv)  # [N, K]
    wgt = th.ones(freqs, device=z.device) * 2.0
    wgt[0] = 1.0
    wgt[-1] = 1.0
    ct = (th.cos(ang) * wgt).t().contiguous()  # [K, N]
    st = (th.sin(ang) * wgt).t().contiguous()

    lead = re.shape[:-2]
    re = re.reshape(-1, freqs, frames).transpose(1, 2)  # [B, F, K]
    im = im.reshape(-1, freqs, frames).transpose(1, 2)  # [B, F, K]
    sig = ((re @ ct) - (im @ st)) / n_fft  # [B, F, N]

    total = (frames - 1) * hop + n_fft
    # OLA: 출력 샘플당 겹치는 프레임이 정확히 nblk(N/hop)개임을 이용한 gather 합산.
    #   y[t] = Σ_d g[t//hop - d][t%hop + d*hop] · win[t%hop + d*hop]  (유효 인덱스만)
    nblk = n_fft // hop
    lead_b = re.shape[0]
    tt = th.arange(total, device=z.device)
    fj = (tt // hop).view(-1, 1)                    # [T,1]
    pv = (tt % hop).view(-1, 1)                     # [T,1]
    dsel = th.arange(nblk, device=z.device).view(1, nblk)  # [1,D]
    fm = fj - dsel                                  # [T,D] 프레임 인덱스
    nm = pv + dsel * hop                            # [T,D] 프레임 내 샘플 오프셋
    valid = ((fm >= 0) & (fm < frames) & (nm >= 0) & (nm < n_fft)).float()
    fmc = fm.clamp(0, frames - 1)
    nmc = nm.clamp(0, n_fft - 1)

    w_all = win.gather(0, nmc.reshape(-1)).view(total, nblk)   # [T,D]
    m_all = valid                                                # [T,D]

    gflat = sig.reshape(lead_b, -1)                             # [B, F*N]
    base = (fmc * n_fft + nmc)                                  # [T,D]
    offs = (th.arange(lead_b, device=z.device) * (frames * n_fft)).view(-1, 1, 1)
    idx = (base.unsqueeze(0) + offs).view(-1)                   # [B*T*D]
    gathered = gflat.reshape(-1).gather(0, idx).view(lead_b, total, nblk)
    y = (gathered * w_all * m_all).sum(-1)                      # [B, T]
    env = (w_all * w_all * m_all).sum(-1).unsqueeze(0)          # [1, T]
    y = y / env.clamp(min=1e-11)
    x = y[..., n_fft // 2:-(n_fft // 2)]
    x = x * float(n_fft) ** 0.5

    cur = x.shape[-1]
    if not th.jit.is_tracing():
        if length is not None:
            if cur > length:
                x = x[..., :length]
            elif cur < length:
                x = F.pad(x, (0, length - cur))
        return x.view(*lead, x.shape[-1])
    else:
        # tracing 중에는 조건 분기 대신 항상 동일 연산: slice/pad를 그래프로
        if length is not None:
            x = x[..., :length] if cur > length else x
            if cur < length:
                x = F.pad(x, (0, length - cur))
        return x.view(*lead, x.shape[-1])


# ---------------------------------------------------------------- 로드 & 참조
log("모델 로드 중 (체크포인트 다운로드 포함, 최초 1회)")
from demucs.pretrained import get_model  # noqa: E402

wrapper = get_model(MODEL_NAME).cpu().eval()
model = wrapper.models[0] if hasattr(wrapper, "models") else wrapper
model.use_train_segment = False
N_SRC = len(model.sources)
assert N_SRC == 6, f"htdemucs_6s가 아님: sources={list(model.sources)}"
log(f"모델={MODEL_NAME}, 스템 순서={list(model.sources)} (총 {N_SRC}개)")

# nn.MultiheadAttention은 ONNX export 시 융합 연산자(aten::_native_multi_head_attention)가
# 되어 지원되지 않으므로, 동일 가중치로 기본 연산만 사용하는 수동 구현으로 교체한다.
class _ManualMHA(th.nn.Module):
    def __init__(self, src: th.nn.MultiheadAttention):
        super().__init__()
        self.src = src
        self.E = src.embed_dim
        self.H = src.num_heads

    def forward(self, query, key, value, key_padding_mask=None,
                need_weights=True, attn_mask=None, average_attn_weights=True,
                is_causal=False, **kwargs):
        m = self.src
        E, H = self.E, self.H
        dh = E // H
        if m.batch_first:
            q, k, v = query, key, value
        else:
            q, k, v = query.transpose(0, 1), key.transpose(0, 1), value.transpose(0, 1)
        proj = th.nn.functional.linear(q, m.in_proj_weight[:E], None if m.in_proj_bias is None else m.in_proj_bias[:E])
        kk = th.nn.functional.linear(k, m.in_proj_weight[E:2 * E], None if m.in_proj_bias is None else m.in_proj_bias[E:2 * E])
        vv = th.nn.functional.linear(v, m.in_proj_weight[2 * E:], None if m.in_proj_bias is None else m.in_proj_bias[2 * E:])
        B, T, _ = proj.shape
        proj = proj.view(B, T, H, dh).transpose(1, 2)
        kk = kk.view(B, -1, H, dh).transpose(1, 2)
        vv = vv.view(B, -1, H, dh).transpose(1, 2)
        att = (proj @ kk.transpose(-2, -1)) / math.sqrt(dh)
        att = th.softmax(att, dim=-1)
        ctx = (att @ vv).transpose(1, 2).reshape(B, T, E)
        out = th.nn.functional.linear(ctx, m.out_proj.weight, m.out_proj.bias)
        if not m.batch_first:
            out = out.transpose(0, 1)
        return out, None


def _replace_mha(root):
    for name, child in root.named_children():
        if isinstance(child, th.nn.MultiheadAttention):
            setattr(root, name, _ManualMHA(child))
        else:
            _replace_mha(child)


_replace_mha(model)
log(f"로드 완료: {type(model).__name__}, "
    f"params={sum(p.numel() for p in model.parameters())/1e6:.1f}M")

SEG = 262144
X_REF = th.randn(1, 2, SEG)
with th.no_grad():
    Y_REF = model(X_REF)
log(f"원본(complex) 경로 기준 출력: {tuple(Y_REF.shape)}")

# ---------------------------------------------------------------- 패치 적용
import demucs.htdemucs as hd  # noqa: E402  # HTDemucs 구현 모듈. 가중치는 htdemucs_6s

hd.spectro = spectro_pair
hd.ispectro = ispectro_pair


def _magnitude_pair(self, z):
    B, C, Fq, T, _ = z.shape
    return z.permute(0, 1, 4, 2, 3).reshape(B, C * 2, Fq, T)


def _mask_pair(self, z, m):
    B, S, C2, Fq, T = m.shape
    out = m.view(B, S, C2 // 2, 2, Fq, T).permute(0, 1, 2, 4, 5, 3)
    return out.contiguous()


def _spec_pair(self, x):
    """원본 HTDemucs._spec의 쌍 텐서 버전 ([..., K, T, 2])."""
    hl = self.hop_length
    nfft = self.nfft
    assert hl == nfft // 4
    le = int(math.ceil(x.shape[-1] / hl))
    pad = hl // 2 * 3
    x = F.pad(x, (pad, pad + le * hl - x.shape[-1]), mode="reflect")
    z = spectro_pair(x, nfft, hl)[..., :-1, :, :]  # 나이퀴스트 빈 제거
    assert z.shape[-2] == le + 4, (z.shape, le)
    z = z[..., 2:2 + le, :]
    return z


def _ispec_pair(self, z, length=None, scale=0):
    """원본 HTDemucs._ispec의 쌍 텐서 버전."""
    hl = self.hop_length // (4 ** scale)
    z = F.pad(z, (0, 0, 0, 0, 0, 1))   # 나이퀴스트 빈 복원 (K+1)
    z = F.pad(z, (0, 0, 2, 2))         # 프레임 양쪽 +2
    pad = hl // 2 * 3
    le = hl * int(math.ceil(length / hl)) + 2 * pad
    x = ispectro_pair(z, hl, length=le)
    return x[..., pad:pad + length]


model._magnitude = _magnitude_pair.__get__(model)
model._mask = _mask_pair.__get__(model)
model._spec = _spec_pair.__get__(model)
model._ispec = _ispec_pair.__get__(model)

with th.no_grad():
    Y_NEW = model(X_REF)
rel = ((Y_REF - Y_NEW).norm() / Y_REF.norm()).item()
mx = (Y_REF - Y_NEW).abs().max().item()
log(f"패치 수치 검증: rel_err={rel:.2e}, max_abs_diff={mx:.2e}")
# htdemucs_6s=~1e-4 수준 — fp32 연산 순서 차이의 정상 범위
assert rel < 1e-3, "패치된 경로가 원본과 다름"
del Y_REF, Y_NEW

# ---------------------------------------------------------------- export
# Kotlin 쪽(DemucsSeparator)은 항상 고정 길이 세그먼트로 추론하므로
# 등급별 고정 길이로 각각 export한다 (동적 축 불필요 → 그래프 단순·안전).
TIERS = [
    (f"{MODEL_TAG}-light-fp32.onnx", 131072, "fp32"),
    (f"{MODEL_TAG}-balanced-fp32.onnx", 262144, "fp32"),
    (f"{MODEL_TAG}-quality-fp32.onnx", 344064, "fp32"),
]

PRODUCED = []
for fname, seg, kind in TIERS:
    dest = os.path.join(OUT_DIR, fname)
    if os.path.exists(dest):
        log(f"{fname} 이미 있음 — 건너뜀")
        PRODUCED.append(dest)
        continue
    tmp_fp32 = os.path.join(OUT_DIR, f"tmp-{MODEL_TAG}-{seg}.onnx")
    if not os.path.exists(tmp_fp32):
        log(f"export 시작: {fname} (seg={seg}, opset=18, 고정 길이)")
        t0 = time.time()
        with th.no_grad():
            th.onnx.export(
                model,
                th.randn(1, 2, seg),
                tmp_fp32,
                opset_version=18,
                input_names=["audio"],
                output_names=["stems"],
                dynamo=False,
                do_constant_folding=False,
            )
        log(f"export 완료 ({time.time()-t0:.0f}s): {os.path.getsize(tmp_fp32)/1e6:.0f}MB")
    os.replace(tmp_fp32, dest)
    log(f"{fname} 준비 완료: {os.path.getsize(dest)/1e6:.0f}MB")
    PRODUCED.append(dest)

# ---------------------------------------------------------------- 검증
log("검증 시작")
import onnxruntime as ort  # noqa: E402


def make_input(seconds, seed=42):
    sr = 44100
    rng = np.random.RandomState(seed)
    n = seconds * sr
    t = np.arange(n) / sr
    voc = 0.4 * np.sin(2 * np.pi * 220 * t)
    perc = np.zeros(n)
    for kk in range(0, n, 4410):
        perc[kk] = rng.uniform(-1, 1)
    left = voc + 0.5 * perc
    right = voc + 0.5 * np.roll(perc, 220)
    xx = np.stack([left, right]).astype(np.float32)[None]
    return xx / max(1.0, np.abs(xx).max())


def pad_to(x, seg):
    """Kotlin DemucsSeparator와 동일: 마지막 청크는 0으로 채워 full 세그먼트."""
    out = np.zeros((1, 2, seg), dtype=np.float32)
    out[:, :, : x.shape[2]] = x
    return out


for fname, seg, kind in TIERS:
    path = os.path.join(OUT_DIR, fname)
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    assert list(inp.shape) == [1, 2, seg], f"{fname}: 입력 규격 {inp.shape}"

    # 파이토치 원본(같은 세그먼트, 0 패딩 동일)과 비교 — 활성 구간만
    base = make_input(max(1, seg // 44100 - 1))  # 마지막 1초는 무음(패딩)
    active = base.shape[2]
    xt = th.from_numpy(pad_to(base, seg))
    with th.no_grad():
        y_pt = model(xt)[0].numpy()
    y_ort = sess.run(None, {"audio": xt.numpy()})[0]
    assert y_ort.shape == (1, N_SRC, 2, seg), y_ort.shape
    assert np.isfinite(y_ort).all(), f"{fname}: NaN/Inf"
    pa = y_pt[..., :active].ravel()
    oa = y_ort[..., :active].ravel()
    corr = np.corrcoef(pa, oa)[0, 1]
    rms_err = float(np.sqrt(((pa - oa) ** 2).mean()) / np.sqrt((pa ** 2).mean()))
    log(f"  {fname}: 활성구간 corr={corr:.4f} relRMS={rms_err:.4f}")
    assert corr > 0.99, f"{fname} 원본과 차이"
    assert rms_err < 0.1, f"{fname} 오차 큼"

# 서로 다른 스템이 실제로 다른 값인지 (분리가 동작하는지 스모크)
sess = ort.InferenceSession(PRODUCED[1], providers=["CPUExecutionProvider"])
y = sess.run(None, {"audio": pad_to(make_input(5), 262144)})[0]
stems = y[0].reshape(N_SRC, -1)
c01 = np.corrcoef(stems[0], stems[1])[0, 1]
c23 = np.corrcoef(stems[2], stems[3])[0, 1]
log(f"스템 상관 [0]-[1]={c01:.3f}, [2]-[3]={c23:.3f}")
assert abs(c01) < 0.99 and abs(c23) < 0.99

log("해시:")
for p in PRODUCED:
    log(f"  {os.path.basename(p)}: size={os.path.getsize(p)/1e6:.0f}MB sha256={sha256(p)}")

log("ALL_OK")
