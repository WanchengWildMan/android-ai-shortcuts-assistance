"""逐类 max-margin 对比评分分析 - 检查阈值安全性"""
import numpy as np
import torch
import soundfile as sf
import sys

sys.path.insert(0, str(__import__('pathlib').Path(__file__).parent))
from model import WakeWordEncoder
from enroll import load_encoder, audio_to_mel
from augment import pad_or_trim
from config import *

device = torch.device('mps')
model = load_encoder(MODEL_DIR / 'encoder_best.pt', device, WakeWordEncoder, EMBEDDING_DIM)
t = np.load(str(TEMPLATE_DIR / '小万.npy'), allow_pickle=True).item()
wake_p = torch.FloatTensor(t['keyword_prototype']).to(device)
bg_matrix = torch.FloatTensor(t['bg_class_matrix']).to(device)  # [K, D]
bg_names = t['bg_class_names']

def score_file(path):
    a, _ = sf.read(str(path))
    a = pad_or_trim(a.astype(np.float32), N_SAMPLES)
    rms = float(np.sqrt(np.mean(a ** 2)))
    with torch.no_grad():
        mel = audio_to_mel(a).unsqueeze(0).to(device)
        e = model(mel).squeeze(0)
        ws = torch.dot(e, wake_p).item()
        bg_sims = torch.mv(bg_matrix, e)
        bg_max_val, bg_max_idx = bg_sims.max(dim=0)
        bg_max = bg_max_val.item()
        bg_name = bg_names[bg_max_idx.item()]
    return ws - bg_max, ws, bg_max, rms, bg_name

# 正样本
print('=== 正样本(小万) max-margin 对比评分 ===')
pos_dir = POSITIVE_DIR / '小万'
pos_scores = []
for f in sorted(pos_dir.glob('*.wav')):
    c, ws, bg, rms, bgn = score_file(f)
    pos_scores.append(c)
    print(f'  {f.name}: contrast={c:.4f} wake={ws:.4f} nearest_neg={bg:.4f}({bgn}) rms={rms:.4f}')
print(f'  MEAN={np.mean(pos_scores):.4f} MIN={np.min(pos_scores):.4f} MAX={np.max(pos_scores):.4f}')

# 负样本
print('\n=== 负样本 max-margin 对比评分 TOP15 ===')
neg_results = []
for d in sorted(NEGATIVE_DIR.iterdir()):
    if not d.is_dir(): continue
    for f in sorted(d.glob('*.wav'))[:3]:
        c, ws, bg, rms, bgn = score_file(f)
        neg_results.append((c, ws, bg, rms, bgn, d.name, f.name))
neg_results.sort(reverse=True)
for c, ws, bg, rms, bgn, cat, fn in neg_results[:15]:
    print(f'  contrast={c:.4f} wake={ws:.4f} nearest_neg={bg:.4f}({bgn}) rms={rms:.4f} {cat}/{fn}')

print(f'\n负样本总数: {len(neg_results)}')
for th in [0.10, 0.05, 0.02, 0.00, -0.02, -0.05]:
    n = sum(1 for c, *_ in neg_results if c > th)
    print(f'  对比评分 > {th:+.2f}: {n}/{len(neg_results)} ({100*n/len(neg_results):.1f}%)')

# 间隙分析
pos_min = np.min(pos_scores)
neg_max = neg_results[0][0]
print(f'\n=== 间隙分析 ===')
print(f'正样本最低: {pos_min:.4f}')
print(f'负样本最高: {neg_max:.4f}')
print(f'间隙: {pos_min - neg_max:.4f}')
if pos_min > neg_max:
    print(f'建议阈值: {(pos_min + neg_max) / 2:.4f} (正负中点)')
else:
    print(f'[危险] 正负样本有交叠! 需要进一步改进')
