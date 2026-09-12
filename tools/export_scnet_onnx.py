#!/usr/bin/env python3
"""SCNet XL / XL IHF → 복소수 없는 ONNX(fp32) 변환 + 검증.

사용법: python export_scnet_onnx.py <출력폴더> <xl|xl-ihf>

DemucsSeparator와 같은 입출력: audio [1,2,262144] → stems [1,4,2,262144]
(drums, bass, other, vocals). 가중치는 11초 학습이지만 온디바이스는 6초 청크.
11초(485100) IHF export는 S25에서 첫 추론 스왑 7GB → LMKD SIGKILL.

우회:
- torch.stft/istft complex → re/im 쌍. 창은 원본과 같이 사각창(Hann 아님).
- FeatureConversion의 rfft/irfft+complex → 직교 정규화 DFT 행렬곱.
"""
import hashlib
import math
import os
import subprocess
import sys
import time
import urllib.request

import numpy as np
import torch as th
import torch.nn.functional as F

USAGE = "사용법: python export_scnet_onnx.py <출력폴더> <xl|xl-ihf>"
if len(sys.argv) != 3:
    print(USAGE, file=sys.stderr)
    sys.exit(2)
OUT_DIR = sys.argv[1]
VARIANT = sys.argv[2]
os.makedirs(OUT_DIR, exist_ok=True)

MSS_REPO = "https://github.com/ZFTurbo/Music-Source-Separation-Training.git"
SEG = 262_144
ZF = "https://github.com/ZFTurbo/Music-Source-Separation-Training/releases/download"

COMMON = {
    "sources": ["drums", "bass", "other", "vocals"],
    "audio_channels": 2,
    "dims": [4, 64, 128, 256],
    "nfft": 4096,
    "hop_size": 1024,
    "win_size": 4096,
    "normalized": True,
    "band_SR": [0.23, 0.37, 0.4],
    "compress": 4,
    "conv_kernel": 3,
    "num_dplayer": 8,
    "expand": 1,
}
VARIANTS = {
    "xl": {
        "fname": "scnetxl-fp32.onnx",
        "ckpt": f"{ZF}/v1.0.13/model_scnet_ep_54_sdr_9.8051.ckpt",
        "config": f"{ZF}/v1.0.13/config_musdb18_scnet_xl.yaml",
        "cfg_name": "config_musdb18_scnet_xl.yaml",
        "ckpt_name": "model_scnet_ep_54_sdr_9.8051.ckpt",
        "fallback": {
            **COMMON,
            "band_stride": [1, 4, 16],
            "band_kernel": [3, 4, 16],
            "conv_depths": [3, 2, 1],
        },
    },
    "xl-ihf": {
        "fname": "scnetxl-ihf-fp32.onnx",
        "ckpt": f"{ZF}/v1.0.15/model_scnet_ep_36_sdr_10.0891.ckpt",
        "config": f"{ZF}/v1.0.15/config_musdb18_scnet_xl_more_wide_v5.yaml",
        "cfg_name": "config_musdb18_scnet_xl_more_wide_v5.yaml",
        "ckpt_name": "model_scnet_ep_36_sdr_10.0891.ckpt",
        # IHF = 고역 stride/kernel 16→4, conv_depths 전부 3.
        "fallback": {
            **COMMON,
            "band_stride": [1, 4, 4],
            "band_kernel": [3, 4, 4],
            "conv_depths": [3, 3, 3],
        },
    },
}
if VARIANT not in VARIANTS:
    print(USAGE, file=sys.stderr)
    sys.exit(2)
SPEC = VARIANTS[VARIANT]
CKPT_URL = SPEC["ckpt"]
CONFIG_URL = SPEC["config"]
FNAME = SPEC["fname"]
FALLBACK = SPEC["fallback"]


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        log(f"이미 있음: {dest}")
        return
    log(f"다운로드: {url}")
    urllib.request.urlretrieve(url, dest)


def spectro_pair(x, n_fft=4096, hop_length=1024, pad=0):
    """torch.stft(window=None, normalized=True, center=True)와 수치 동등.

    SCNet stft_config에 window가 없어 사각창이다. 반환 [..., K, F, 2].
    """
    *other, length = x.shape
    x = x.reshape(-1, length)
    nf = n_fft * (1 + pad)
    hop = hop_length or nf // 4
    win = th.ones(nf, device=x.device, dtype=x.dtype)

    kv = th.arange(nf // 2 + 1, dtype=th.float32, device=x.device)
    nv = th.arange(nf, dtype=th.float32, device=x.device)
    ang = (2.0 * math.pi / nf) * th.outer(kv, nv)
    w_cos = th.cos(ang) * win[None, :]
    w_sin = -th.sin(ang) * win[None, :]
    weight = th.cat([w_cos, w_sin], dim=0) * (float(nf) ** -0.5)

    xp = F.pad(x.unsqueeze(1), (nf // 2, nf // 2), mode="reflect").squeeze(1)
    proj = F.conv1d(xp.unsqueeze(1), weight.unsqueeze(1), stride=hop)
    k2 = proj.shape[1] // 2
    out = th.stack([proj[:, :k2], proj[:, k2:]], dim=-1)
    _, freqs, frames = out.shape[:3]
    return out.view(*other, freqs, frames, 2)


def ispectro_pair(z, hop_length=None, length=None, pad=0):
    """torch.istft(window=None, normalized=True, center=True)와 수치 동등."""
    *other, freqs, frames, _ = z.shape
    n_fft = 2 * freqs - 2
    hop = hop_length or n_fft // 4
    win_len = n_fft // (1 + pad)
    win = th.ones(win_len, device=z.device, dtype=z.dtype)

    re, im = z[..., 0], z[..., 1]
    kv = th.arange(freqs, dtype=th.float32, device=z.device)
    nv = th.arange(n_fft, dtype=th.float32, device=z.device)
    ang = (2.0 * math.pi / n_fft) * th.outer(nv, kv)
    wgt = th.ones(freqs, device=z.device)
    wgt[1:-1] = 2.0
    ct = (th.cos(ang) * wgt).t().contiguous()
    st = (th.sin(ang) * wgt).t().contiguous()

    lead = re.shape[:-2]
    re = re.reshape(-1, freqs, frames).transpose(1, 2)
    im = im.reshape(-1, freqs, frames).transpose(1, 2)
    sig = ((re @ ct) - (im @ st)) / n_fft

    total = (frames - 1) * hop + n_fft
    nblk = n_fft // hop
    lead_b = re.shape[0]
    tt = th.arange(total, device=z.device)
    fj = (tt // hop).view(-1, 1)
    pv = (tt % hop).view(-1, 1)
    dsel = th.arange(nblk, device=z.device).view(1, nblk)
    fm = fj - dsel
    nm = pv + dsel * hop
    valid = ((fm >= 0) & (fm < frames) & (nm >= 0) & (nm < n_fft)).float()
    fmc = fm.clamp(0, frames - 1)
    nmc = nm.clamp(0, n_fft - 1)

    w_all = win.gather(0, nmc.reshape(-1)).view(total, nblk)
    gflat = sig.reshape(lead_b, -1)
    base = fmc * n_fft + nmc
    offs = (th.arange(lead_b, device=z.device) * (frames * n_fft)).view(-1, 1, 1)
    idx = (base.unsqueeze(0) + offs).view(-1)
    gathered = gflat.reshape(-1).gather(0, idx).view(lead_b, total, nblk)
    y = (gathered * w_all * valid).sum(-1)
    env = (w_all * w_all * valid).sum(-1).unsqueeze(0)
    y = y / env.clamp(min=1e-11)
    x = y[..., n_fft // 2 : -(n_fft // 2)]
    x = x * float(n_fft) ** 0.5

    cur = x.shape[-1]
    if length is not None:
        if cur > length:
            x = x[..., :length]
        elif cur < length:
            x = F.pad(x, (0, length - cur))
    return x.view(*lead, x.shape[-1])


def rfft_ortho(x, n, dim=3):
    """torch.fft.rfft(..., norm='ortho')의 실수 쌍. 마지막 축 길이 n은 상수."""
    x_ = x.transpose(dim, -1) if dim != -1 and dim != x.dim() - 1 else x
    k = n // 2 + 1
    tv = th.arange(n, device=x.device, dtype=x.dtype)
    kv = th.arange(k, device=x.device, dtype=x.dtype)
    ang = (2.0 * math.pi / n) * th.outer(kv, tv)
    scale = n ** -0.5
    re = F.linear(x_, th.cos(ang) * scale)
    im = F.linear(x_, -th.sin(ang) * scale)
    if dim != -1 and dim != x.dim() - 1:
        re = re.transpose(dim, -1)
        im = im.transpose(dim, -1)
    return re, im


def irfft_ortho(re, im, n, dim=3):
    """torch.fft.irfft(..., n=n, norm='ortho')의 실수 쌍."""
    last = re.dim() - 1
    if dim != -1 and dim != last:
        re = re.transpose(dim, -1)
        im = im.transpose(dim, -1)
    k = n // 2 + 1
    tv = th.arange(n, device=re.device, dtype=re.dtype)
    kv = th.arange(k, device=re.device, dtype=re.dtype)
    ang = (2.0 * math.pi / n) * th.outer(tv, kv)
    w = th.ones(k, device=re.device, dtype=re.dtype)
    w[1:-1] = 2.0
    w = w * (n ** -0.5)
    y = re @ (th.cos(ang) * w).t() - im @ (th.sin(ang) * w).t()
    if dim != -1 and dim != last:
        y = y.transpose(dim, -1)
    return y


def stft_frame_count(length, hop):
    padding = hop - length % hop
    if (length + padding) // hop % 2 == 0:
        padding += hop
    return (length + padding) // hop + 1


def ensure_mss(cache):
    dest = os.path.join(cache, "Music-Source-Separation-Training")
    if not os.path.isdir(os.path.join(dest, "models", "scnet")):
        log("ZFTurbo 레포 클론")
        subprocess.check_call(["git", "clone", "--depth", "1", MSS_REPO, dest])
    return dest


def unwrap_state(raw):
    if isinstance(raw, dict):
        for key in ("state_dict", "model", "model_state_dict"):
            if key in raw and isinstance(raw[key], dict):
                raw = raw[key]
                break
    out = {}
    for k, v in raw.items():
        nk = k
        for prefix in ("model.", "net.", "module."):
            if nk.startswith(prefix):
                nk = nk[len(prefix):]
        out[nk] = v
    return out


def build_scnet(SCNet, mc):
    return SCNet(
        sources=mc["sources"],
        audio_channels=mc["audio_channels"],
        dims=mc["dims"],
        nfft=mc["nfft"],
        hop_size=mc["hop_size"],
        win_size=mc["win_size"],
        normalized=mc["normalized"],
        band_SR=mc["band_SR"],
        band_stride=mc["band_stride"],
        band_kernel=mc["band_kernel"],
        conv_depths=mc["conv_depths"],
        compress=mc["compress"],
        conv_kernel=mc["conv_kernel"],
        num_dplayer=mc["num_dplayer"],
        expand=mc["expand"],
    ).cpu().eval()


def try_load(model, state):
    missing, unexpected = model.load_state_dict(state, strict=False)
    return missing, unexpected


def patch_feature_conversion(FeatureConversion, n_time):
    """T 길이를 상수로 고정. movedim(-1)은 ONNX가 perm=-1·shape 0으로 깨진다."""

    def forward(self, x):
        if self.inverse:
            x = x.float()
            half = self.channels // 2
            return irfft_ortho(x[:, :half], x[:, half:], n_time, dim=3)
        x = x.float()
        re, im = rfft_ortho(x, n_time, dim=3)
        return th.cat([re, im], dim=1)

    FeatureConversion.forward = forward


def patch_scnet_stft(model):
    nfft = model.stft_config["n_fft"]
    hop = model.stft_config["hop_length"]

    def forward(x):
        b = x.shape[0]
        padding = hop - x.shape[-1] % hop
        if (x.shape[-1] + padding) // hop % 2 == 0:
            padding += hop
        x = F.pad(x, (0, padding))
        length = x.shape[-1]
        x = x.reshape(-1, length)
        z = spectro_pair(x, n_fft=nfft, hop_length=hop)
        z = z.permute(0, 3, 1, 2).reshape(
            z.shape[0] // model.audio_channels,
            2 * model.audio_channels,
            z.shape[1],
            z.shape[2],
        )
        fr, tt = z.shape[-2], z.shape[-1]
        save_skip, save_lengths, save_orig = [], [], []
        for sd in model.encoder:
            z, skip, lengths, original = sd(z)
            save_skip.append(skip)
            save_lengths.append(lengths)
            save_orig.append(original)
        z = model.separation_net(z)
        for fusion, su in model.decoder:
            z = fusion(z, save_skip.pop())
            z = su(z, save_lengths.pop(), save_orig.pop())
        n = model.dims[0]
        z = z.view(b, n, -1, fr, tt)
        z = z.reshape(-1, 2, fr, tt).permute(0, 2, 3, 1).contiguous()
        y = ispectro_pair(z, hop_length=hop, length=length)
        y = y.reshape(b, len(model.sources), model.audio_channels, -1)
        return y[:, :, :, :-padding]

    model.forward = forward


log("캐시·의존 준비")
cache = os.path.join(OUT_DIR, "_cache")
os.makedirs(cache, exist_ok=True)
mss = ensure_mss(cache)
sys.path.insert(0, mss)

import yaml  # noqa: E402
from models.scnet.scnet import SCNet  # noqa: E402
from models.scnet.separation import FeatureConversion  # noqa: E402

cfg_path = os.path.join(cache, SPEC["cfg_name"])
ckpt_path = os.path.join(cache, SPEC["ckpt_name"])
try:
    download(CONFIG_URL, cfg_path)
except Exception as e:
    log(f"공식 yaml 실패({e}) — {VARIANT} 폴백 사용")
    cfg_path = None
download(CKPT_URL, ckpt_path)

log("가중치 로드")
raw = th.load(ckpt_path, map_location="cpu", weights_only=False)
state = unwrap_state(raw)

candidates = []
if cfg_path and os.path.exists(cfg_path):
    with open(cfg_path) as f:
        # 공식 yaml에 !!python/tuple(증강 확률)이 있어 SafeLoader는 실패한다
        candidates.append(("yaml", yaml.load(f, Loader=yaml.FullLoader)["model"]))
candidates.append(("fallback", FALLBACK))

model = None
for name, mc in candidates:
    log(f"모델 생성: {name} dims={mc['dims']} stride={mc['band_stride']} "
        f"depths={mc['conv_depths']} dplayer={mc['num_dplayer']}")
    cand = build_scnet(SCNet, mc)
    missing, unexpected = try_load(cand, state)
    log(f"  누락 {len(missing)} 여분 {len(unexpected)}")
    if missing:
        log(f"  누락 예: {missing[:6]}")
        continue
    model = cand
    log(f"가중치 일치: {name}")
    break
assert model is not None, "체크포인트와 맞는 SCNet 설정을 찾지 못함"

log("STFT/FFT 패치 전 수치 대조")
x_ref = th.randn(1, 2, 8192)
with th.no_grad():
    z_torch = th.stft(x_ref.reshape(-1, 8192), n_fft=4096, hop_length=1024,
                      win_length=4096, window=None, center=True,
                      normalized=True, return_complex=False)
    z_pair = spectro_pair(x_ref.reshape(-1, 8192), n_fft=4096, hop_length=1024)
    stft_rel = ((z_torch - z_pair).norm() / z_torch.norm()).item()
log(f"spectro_pair vs torch.stft rel={stft_rel:.2e}")
# 4096pt 직접 DFT는 float32에서 ~1e-4. 가청 오차보다 작고 ONNX 대조가 본검증이다
assert stft_rel < 1e-3, "사각창 STFT 패치가 원본과 다름"

t_fft = th.randn(2, 8, 16, 32)
with th.no_grad():
    ref = th.fft.rfft(t_fft, dim=3, norm="ortho")
    re, im = rfft_ortho(t_fft, 32, dim=3)
    rfft_rel = ((ref.real - re).norm() / ref.real.norm()).item()
    back = irfft_ortho(re, im, 32, dim=3)
    irfft_rel = ((t_fft - back).norm() / t_fft.norm()).item()
log(f"rfft rel={rfft_rel:.2e} irfft recon rel={irfft_rel:.2e}")
assert rfft_rel < 1e-5 and irfft_rel < 1e-5

log("원본(complex) 기준 출력 (짧은 클립)")
x_full = th.randn(1, 2, 44_100)
with th.no_grad():
    y_ref = model(x_full)
log(f"원본 출력: {tuple(y_ref.shape)}")
assert y_ref.shape == (1, 4, 2, 44_100), y_ref.shape

n_short = stft_frame_count(44_100, model.hop_length)
patch_feature_conversion(FeatureConversion, n_short)
patch_scnet_stft(model)
with th.no_grad():
    y_new = model(x_full)
rel = ((y_ref - y_new).norm() / y_ref.norm()).item()
mx = (y_ref - y_new).abs().max().item()
log(f"패치 검증: rel_err={rel:.2e}, max_abs_diff={mx:.2e}")
assert rel < 5e-3, "패치된 경로가 원본과 다름"
del y_ref, y_new, x_full
n_time = stft_frame_count(SEG, model.hop_length)
log(f"export용 STFT 프레임: {n_time}")
patch_feature_conversion(FeatureConversion, n_time)

dest = os.path.join(OUT_DIR, FNAME)
tmp = os.path.join(OUT_DIR, f"tmp-{FNAME}")
if os.path.exists(dest):
    log(f"{FNAME} 이미 있음 — export 건너뜀")
else:
    log(f"export 시작: {FNAME} (seg={SEG}, opset=18)")
    t0 = time.time()
    with th.no_grad():
        th.onnx.export(
            model,
            th.randn(1, 2, SEG),
            tmp,
            opset_version=18,
            input_names=["audio"],
            output_names=["stems"],
            dynamo=False,
            do_constant_folding=False,
        )
    log(f"export 완료 ({time.time() - t0:.0f}s): {os.path.getsize(tmp) / 1e6:.0f}MB")
    os.replace(tmp, dest)

log("검증")
import onnxruntime as ort  # noqa: E402

sess = ort.InferenceSession(dest, providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0]
assert list(inp.shape) == [1, 2, SEG], inp.shape

rng = np.random.RandomState(42)
base = rng.randn(1, 2, SEG - 44100).astype(np.float32) * 0.1
xt = np.zeros((1, 2, SEG), dtype=np.float32)
xt[:, :, : base.shape[2]] = base
active = base.shape[2]
with th.no_grad():
    y_pt = model(th.from_numpy(xt))[0].numpy()
y_ort = sess.run(None, {"audio": xt})[0]
assert y_ort.shape == (1, 4, 2, SEG), y_ort.shape
assert np.isfinite(y_ort).all()
pa = y_pt[..., :active].ravel()
oa = y_ort[..., :active].ravel()
corr = np.corrcoef(pa, oa)[0, 1]
rms = float(np.sqrt(((pa - oa) ** 2).mean()) / np.sqrt((pa ** 2).mean()))
log(f"활성구간 corr={corr:.4f} relRMS={rms:.4f}")
assert corr > 0.99, "원본과 차이"
assert rms < 0.1, "오차 큼"

digest = sha256(dest)
mb = os.path.getsize(dest) / 1e6
log(f"해시: {FNAME} size={mb:.0f}MB sha256={digest}")
log("ALL_OK")
log(f"ModelCatalog.kt sha256 핀: {digest}")
log(f"approxSizeMb: {int(round(os.path.getsize(dest) / 1e6))}")
