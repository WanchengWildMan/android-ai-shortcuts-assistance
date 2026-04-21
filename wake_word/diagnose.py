"""诊断脚本: 分析嵌入空间中正/负/噪声样本与唤醒词原型的相似度分布"""
import numpy as np
import torch
import soundfile as sf
import sys
sys.path.insert(0, '/Users/admin/Documents/Open-AutoGLM/autoglm-shortcut-assistant-android-app/wake_word')

from model import WakeWordEncoder
from enroll import load_encoder, audio_to_mel
from augment import pad_or_trim
from config import *

device = torch.device('mps')
model = load_encoder(MODEL_DIR / 'encoder_best.pt', device, WakeWordEncoder, EMBEDDING_DIM)
template = np.load(str(TEMPLATE_DIR / '小万.npy'), allow_pickle=True).item()
proto = torch.FloatTensor(template['keyword_prototype']).to(device)

def get_sim(filepath):
    a, _ = sf.read(str(filepath))
    a = pad_or_trim(a.astype(np.float32), N_SAMPLES)
    mel = audio_to_mel(a).unsqueeze(0).to(device)
    with torch.no_grad():
        e = model(mel).squeeze(0)
    return torch.dot(e, proto).item()

# 1. 正样本
print("=== 正样本(小万) ===")
pos_sims = []
for f in sorted((POSITIVE_DIR / '小万').glob('*.wav'))[:10]:
    s = get_sim(f)
    pos_sims.append(s)
    print(f"  {f.name}: {s:.4f}")
print(f"  MEAN={np.mean(pos_sims):.4f} MIN={np.min(pos_sims):.4f}")

# 2. 各类负样本
print("\n=== TTS负样本 vs 唤醒词原型 ===")
phrases = ['你好','谢谢','打开微信','小碗','小王','播放音乐','再见','小度小度','早上好','关灯']
for ph in phrases:
    d = NEGATIVE_DIR / ph
    if not d.exists():
        continue
    wavs = sorted(d.glob('*.wav'))[:5]
    sims = [get_sim(f) for f in wavs]
    print(f"  {ph:12s}: mean={np.mean(sims):.4f} [{np.min(sims):.4f} ~ {np.max(sims):.4f}]")

# 3. 噪声
print("\n=== 噪声 vs 唤醒词原型 ===")
for nt in ['_noise_white', '_noise_brown', '_noise_pink', '_silence']:
    d = NEGATIVE_DIR / nt
    if not d.exists():
        continue
    wavs = sorted(d.glob('*.wav'))[:5]
    sims = [get_sim(f) for f in wavs]
    print(f"  {nt:16s}: mean={np.mean(sims):.4f} [{np.min(sims):.4f} ~ {np.max(sims):.4f}]")

# 4. 所有负样本的整体分布
print("\n=== 全体负样本统计 ===")
all_neg = []
for d in NEGATIVE_DIR.iterdir():
    if not d.is_dir():
        continue
    for f in sorted(d.glob('*.wav'))[:3]:  # 每类3条
        all_neg.append(get_sim(f))
all_neg = np.array(all_neg)
print(f"  N={len(all_neg)} mean={all_neg.mean():.4f} std={all_neg.std():.4f}")
print(f"  min={all_neg.min():.4f} max={all_neg.max():.4f}")
print(f"  >0.80: {(all_neg > 0.80).sum()}/{len(all_neg)} ({(all_neg > 0.80).mean()*100:.1f}%)")
print(f"  >0.70: {(all_neg > 0.70).sum()}/{len(all_neg)} ({(all_neg > 0.70).mean()*100:.1f}%)")
print(f"  >0.60: {(all_neg > 0.60).sum()}/{len(all_neg)} ({(all_neg > 0.60).mean()*100:.1f}%)")
