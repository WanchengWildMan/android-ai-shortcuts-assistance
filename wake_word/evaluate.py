"""
定量评估脚本 — 用留出的测试集评估唤醒词检测性能

测试集: 正样本末尾 TEST_SPLIT_COUNT 条 (不参与训练和注册)
负样本: 全部 TTS 负样本 (每类随机抽 N 条)

评估指标:
  - 正样本命中率 (True Positive Rate / Recall)
  - 负样本误触率 (False Positive Rate)
  - max-margin 对比评分分布
  - 说话人验证通过率
"""
import argparse
from pathlib import Path

import numpy as np
import torch
import torchaudio.transforms as T
import soundfile as sf

from config import (
    SAMPLE_RATE, N_SAMPLES, N_MELS, N_FFT, HOP_LENGTH,
    EMBEDDING_DIM, SPEAKER_EMBEDDING_DIM,
    POSITIVE_DIR, NEGATIVE_DIR, TEMPLATE_DIR, MODEL_DIR,
    SIMILARITY_THRESHOLD, SPEAKER_THRESHOLD, DEVICE,
    TEST_SPLIT_COUNT,
)
from model import WakeWordEncoder, SpeakerEncoder
from augment import pad_or_trim, normalize_volume


def audio_to_mel(audio):
    """音频波形 → Mel频谱图 [1, N_MELS, T]"""
    audio = normalize_volume(audio, target_rms=0.1)
    mel_transform = T.MelSpectrogram(
        sample_rate=SAMPLE_RATE, n_fft=N_FFT,
        hop_length=HOP_LENGTH, n_mels=N_MELS,
    )
    amp_to_db = T.AmplitudeToDB()
    waveform = torch.FloatTensor(audio).unsqueeze(0)
    mel = mel_transform(waveform)
    mel = amp_to_db(mel)
    return mel


def load_audio_file(filepath):
    """加载单个音频文件 → float32 数组"""
    audio, sr = sf.read(str(filepath))
    audio = audio.astype(np.float32)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    audio = pad_or_trim(audio, N_SAMPLES)
    return audio


def encode_file(filepath, model, device):
    """加载文件 → 计算嵌入向量 [1, D]"""
    audio = load_audio_file(filepath)
    mel = audio_to_mel(audio).unsqueeze(0).to(device)
    with torch.no_grad():
        emb = model(mel).cpu().numpy()
    return emb  # [1, D]


def evaluate(args):
    device = torch.device(args.device)

    # 1. 加载模板 (注册结果)
    template_path = TEMPLATE_DIR / f"{args.wake_word}.npy"
    if not template_path.exists():
        print(f"错误: 找不到注册模板 {template_path}")
        print("请先运行: python enroll.py --wake-word " + args.wake_word)
        return

    template = np.load(str(template_path), allow_pickle=True).item()
    kw_prototype = template["keyword_prototype"]       # [D]
    bg_matrix = template.get("bg_class_matrix")        # [K, D] 或 None
    bg_names = template.get("bg_class_names", [])
    spk_prototype = template.get("speaker_prototype")  # [D] 或 None
    speaker_verify = template.get("speaker_verify", False)
    kw_threshold = template.get("keyword_threshold", SIMILARITY_THRESHOLD)
    spk_threshold = template.get("speaker_threshold", SPEAKER_THRESHOLD)

    # 2. 加载编码器
    kw_model = WakeWordEncoder(n_mels=N_MELS, embedding_dim=EMBEDDING_DIM)
    kw_path = MODEL_DIR / "encoder_best.pt"
    kw_model.load_state_dict(torch.load(str(kw_path), map_location=device))
    kw_model.to(device).eval()

    spk_model = None
    if speaker_verify and spk_prototype is not None:
        spk_model = SpeakerEncoder(n_mels=N_MELS, embedding_dim=SPEAKER_EMBEDDING_DIM)
        spk_path = MODEL_DIR / "speaker_encoder_best.pt"
        spk_model.load_state_dict(torch.load(str(spk_path), map_location=device))
        spk_model.to(device).eval()

    print(f"=== 唤醒词检测评估: {args.wake_word} ===")
    print(f"唤醒词阈值: {kw_threshold}  说话人阈值: {spk_threshold}")
    print(f"说话人验证: {'开启' if speaker_verify else '关闭'}")
    print()

    # 3. 加载测试集正样本 (末尾 TEST_SPLIT_COUNT 条)
    pos_dir = POSITIVE_DIR / args.wake_word
    all_pos = sorted(pos_dir.glob("*.wav"))
    test_pos = all_pos[-TEST_SPLIT_COUNT:]
    train_pos = all_pos[:-TEST_SPLIT_COUNT]

    print(f"[正样本] 总计 {len(all_pos)} 条: "
          f"训练+注册 {len(train_pos)} 条, 测试 {len(test_pos)} 条")
    print(f"  测试集: {[f.name for f in test_pos]}")
    print()

    # 4. 评估正样本 (期望全部命中)
    print("--- 正样本评估 (期望: 全部命中) ---")
    pos_results = []
    for f in test_pos:
        emb = encode_file(f, kw_model, device)   # [1, D]
        wake_sim = float(emb @ kw_prototype)

        # max-margin 对比评分
        if bg_matrix is not None:
            bg_sims = (emb @ bg_matrix.T).flatten()
            bg_max = bg_sims.max()
            contrast = wake_sim - bg_max
            nearest_neg = bg_names[bg_sims.argmax()]
        else:
            contrast = wake_sim
            bg_max = 0.0
            nearest_neg = "N/A"

        # 说话人评分
        spk_sim = 0.0
        spk_pass = True
        if spk_model is not None and spk_prototype is not None:
            spk_emb = encode_file(f, spk_model, device)
            spk_sim = float(spk_emb @ spk_prototype)
            spk_pass = spk_sim >= spk_threshold

        kw_pass = contrast >= kw_threshold
        final_pass = kw_pass and spk_pass

        status = "命中" if final_pass else "漏检"
        print(f"  {f.name}: wake_sim={wake_sim:.4f} bg_max={bg_max:.4f} "
              f"contrast={contrast:.4f} spk={spk_sim:.4f} -> [{status}]"
              f"  (最近负类: {nearest_neg})")

        pos_results.append({
            "file": f.name, "wake_sim": wake_sim, "contrast": contrast,
            "spk_sim": spk_sim, "kw_pass": kw_pass, "spk_pass": spk_pass,
            "final_pass": final_pass,
        })

    tp = sum(1 for r in pos_results if r["final_pass"])
    recall = tp / len(pos_results) if pos_results else 0
    print(f"\n  正样本命中率: {tp}/{len(pos_results)} = {recall:.1%}")
    print(f"  对比评分: mean={np.mean([r['contrast'] for r in pos_results]):.4f} "
          f"min={np.min([r['contrast'] for r in pos_results]):.4f}")

    # 5. 评估负样本 (期望全部拒绝)
    print(f"\n--- 负样本评估 (期望: 全部拒绝) ---")
    neg_results = []
    n_per_class = args.neg_per_class  # 每个负类抽几条

    neg_dirs = sorted(d for d in NEGATIVE_DIR.iterdir() if d.is_dir())
    print(f"  负类数量: {len(neg_dirs)}, 每类抽 {n_per_class} 条")

    for subdir in neg_dirs:
        wav_files = sorted(subdir.glob("*.wav"))
        if not wav_files:
            continue

        # 随机抽样
        import random
        sampled = random.sample(wav_files, min(n_per_class, len(wav_files)))

        for f in sampled:
            emb = encode_file(f, kw_model, device)
            wake_sim = float(emb @ kw_prototype)

            if bg_matrix is not None:
                bg_sims = (emb @ bg_matrix.T).flatten()
                bg_max = bg_sims.max()
                contrast = wake_sim - bg_max
            else:
                contrast = wake_sim
                bg_max = 0.0

            kw_pass = contrast >= kw_threshold
            # 负样本不做说话人检查 (即使通过了关键词也算误触发)
            neg_results.append({
                "file": f.name, "class": subdir.name,
                "wake_sim": wake_sim, "contrast": contrast,
                "kw_pass": kw_pass,
            })

    fp = sum(1 for r in neg_results if r["kw_pass"])
    fpr = fp / len(neg_results) if neg_results else 0
    print(f"  负样本总评估: {len(neg_results)} 条")
    print(f"  误触发 (仅关键词通道): {fp}/{len(neg_results)} = {fpr:.2%}")

    if fp > 0:
        print(f"\n  误触发详情:")
        false_positives = [r for r in neg_results if r["kw_pass"]]
        false_positives.sort(key=lambda x: x["contrast"], reverse=True)
        for r in false_positives[:20]:  # 最多显示 20 条
            print(f"    [{r['class']}] {r['file']}: "
                  f"wake_sim={r['wake_sim']:.4f} contrast={r['contrast']:.4f}")

    # 6. 如果有说话人验证，额外统计: 对同一批误触发样本，声纹能否拦住
    if speaker_verify and fp > 0 and spk_model is not None:
        print(f"\n--- 说话人验证二次拦截 ---")
        spk_blocked = 0
        for r in false_positives:
            fp_path = NEGATIVE_DIR / r["class"] / r["file"]
            if fp_path.exists():
                spk_emb = encode_file(fp_path, spk_model, device)
                spk_sim = float(spk_emb @ spk_prototype)
                blocked = spk_sim < spk_threshold
                if blocked:
                    spk_blocked += 1
                tag = "拦截" if blocked else "放行"
                print(f"    [{r['class']}] {r['file']}: spk_sim={spk_sim:.4f} -> [{tag}]")
        print(f"  声纹拦截: {spk_blocked}/{fp} ({spk_blocked/fp:.0%})")

    # 7. 总结
    print(f"\n{'='*50}")
    print(f"评估总结: {args.wake_word}")
    print(f"  正样本命中率 (Recall):    {recall:.1%}  ({tp}/{len(pos_results)})")
    print(f"  负样本误触率 (FPR-关键词): {fpr:.2%}  ({fp}/{len(neg_results)})")
    if speaker_verify and fp > 0:
        final_fp = fp - spk_blocked
        final_fpr = final_fp / len(neg_results) if neg_results else 0
        print(f"  负样本误触率 (FPR-双通道): {final_fpr:.2%}  ({final_fp}/{len(neg_results)})")
    print(f"{'='*50}")


def main():
    parser = argparse.ArgumentParser(description="唤醒词检测定量评估")
    parser.add_argument("--wake-word", required=True, help="唤醒词名称")
    parser.add_argument("--neg-per-class", type=int, default=3,
                        help="每个负类随机抽取的样本数 (默认 3)")
    parser.add_argument("--device", type=str, default=DEVICE)
    args = parser.parse_args()

    evaluate(args)


if __name__ == "__main__":
    main()
