"""
唤醒词注册脚本 - 双通道注册
同时生成: 唤醒词内容模板 + 说话人声纹模板
注册过程不涉及训练，仅做推理，秒级完成
"""
import argparse

import numpy as np
import torch
import torchaudio.transforms as T
import soundfile as sf

from config import (
    SAMPLE_RATE, N_SAMPLES, N_MELS, N_FFT, HOP_LENGTH,
    EMBEDDING_DIM, SPEAKER_EMBEDDING_DIM,
    POSITIVE_DIR, NEGATIVE_DIR, TEMPLATE_DIR, MODEL_DIR,
    SIMILARITY_THRESHOLD, SPEAKER_THRESHOLD, SPEAKER_VERIFY, DEVICE,
    TEST_SPLIT_COUNT,
)
from model import WakeWordEncoder, SpeakerEncoder
from augment import pad_or_trim, normalize_volume


def load_encoder(model_path, device, model_class=WakeWordEncoder, embedding_dim=EMBEDDING_DIM):
    """加载训练好的编码器"""
    model = model_class(n_mels=N_MELS, embedding_dim=embedding_dim)
    model.load_state_dict(torch.load(str(model_path), map_location=device))
    model.to(device)
    model.eval()
    return model


def audio_to_mel(audio):
    """音频波形 → Mel频谱图 [1, N_MELS, T]（音量归一化后）"""
    # 音量归一化：消除响度差异，支持悄悄话唤醒
    audio = normalize_volume(audio, target_rms=0.1)
    mel_transform = T.MelSpectrogram(
        sample_rate=SAMPLE_RATE, n_fft=N_FFT,
        hop_length=HOP_LENGTH, n_mels=N_MELS,
    )
    amp_to_db = T.AmplitudeToDB()
    waveform = torch.FloatTensor(audio).unsqueeze(0)  # [1, N_SAMPLES]
    mel = mel_transform(waveform)                      # [1, N_MELS, T]
    mel = amp_to_db(mel)
    return mel


def enroll(args):
    device = torch.device(args.device)

    # 1. 加载唤醒词编码器
    kw_model_path = MODEL_DIR / "encoder_best.pt"
    if not kw_model_path.exists():
        kw_model_path = MODEL_DIR / "encoder_final.pt"
    if not kw_model_path.exists():
        print("错误: 找不到唤醒词编码器模型")
        print("请先运行: python train.py --mode keyword")
        return

    kw_model = load_encoder(kw_model_path, device, WakeWordEncoder, EMBEDDING_DIM)
    print(f"唤醒词编码器已加载: {kw_model_path.name}")

    # 2. 加载说话人编码器（可选）
    spk_model = None
    speaker_verify = SPEAKER_VERIFY and not args.no_speaker
    if speaker_verify:
        spk_model_path = MODEL_DIR / "speaker_encoder_best.pt"
        if not spk_model_path.exists():
            spk_model_path = MODEL_DIR / "speaker_encoder_final.pt"
        if spk_model_path.exists():
            spk_model = load_encoder(
                spk_model_path, device, SpeakerEncoder, SPEAKER_EMBEDDING_DIM
            )
            print(f"说话人编码器已加载: {spk_model_path.name}")
        else:
            print("警告: 找不到说话人编码器模型，将跳过声纹注册")
            print("  如需声纹验证，请先运行: python train.py --mode speaker")
            speaker_verify = False

    # 3. 加载录音文件
    audio_dir = POSITIVE_DIR / args.wake_word
    if not audio_dir.exists():
        print(f"错误: 找不到录音目录 {audio_dir}")
        print("请先运行: python record_audio.py")
        return

    wav_files = sorted(audio_dir.glob("*.wav"))
    if not wav_files:
        print(f"错误: {audio_dir} 中没有 .wav 文件")
        return

    # 排除测试集: 末尾 TEST_SPLIT_COUNT 条保留给 evaluate.py 评估
    all_files = wav_files
    if len(wav_files) > TEST_SPLIT_COUNT:
        wav_files = wav_files[:-TEST_SPLIT_COUNT]
        test_files = all_files[-TEST_SPLIT_COUNT:]
        print(f"注册用 {len(wav_files)} 条 (排除 {len(test_files)} 条测试集: "
              f"{test_files[0].name}~{test_files[-1].name})")
    else:
        print(f"警告: 总录音仅 {len(wav_files)} 条，不足以分割测试集，全部用于注册")

    if len(wav_files) < 5:
        print(f"警告: 只有 {len(wav_files)} 个录音，建议至少录制 5 个")

    print(f"唤醒词: {args.wake_word}")
    print(f"录音数量: {len(wav_files)}")
    print(f"说话人验证: {'开启' if speaker_verify else '关闭'}")

    # 4. 计算嵌入向量
    kw_embeddings = []
    spk_embeddings = []

    with torch.no_grad():
        for f in wav_files:
            audio, sr = sf.read(str(f))
            audio = audio.astype(np.float32)
            if audio.ndim > 1:
                audio = audio.mean(axis=1)
            audio = pad_or_trim(audio, N_SAMPLES)

            mel = audio_to_mel(audio).unsqueeze(0).to(device)

            # 唤醒词编码
            kw_emb = kw_model(mel)
            kw_embeddings.append(kw_emb.cpu().numpy())

            # 说话人编码
            if speaker_verify:
                spk_emb = spk_model(mel)
                spk_embeddings.append(spk_emb.cpu().numpy())

            print(f"  [编码] {f.name}")

    # 5. 计算唤醒词原型向量
    kw_all = np.concatenate(kw_embeddings, axis=0)
    kw_prototype = kw_all.mean(axis=0)
    kw_prototype = kw_prototype / np.linalg.norm(kw_prototype)

    kw_sims = kw_all @ kw_prototype
    print(f"\n唤醒词类内余弦相似度:")
    print(f"  mean={kw_sims.mean():.4f}  min={kw_sims.min():.4f}  max={kw_sims.max():.4f}")

    if kw_sims.min() < 0.7:
        print("  [警告] 某些录音与原型差异较大，建议重听并重录低质量的条目")

    # 5.5 计算逐类背景原型矩阵 — 用于 max-margin 对比校准评分
    # 原理: score = sim(query, wake_proto) - max_k(sim(query, neg_proto_k))
    # 每个负类（短语/噪声）各一个原型向量，检测时取与查询最相似的负类减去
    # 这样粉红噪声会匹配到自己的噪声原型(~0.99)，减去后分数变为负值
    print(f"\n计算逐类背景原型矩阵...")
    class_prototypes = {}  # {类名: L2归一化原型向量}
    with torch.no_grad():
        for subdir in sorted(NEGATIVE_DIR.iterdir()):
            if not subdir.is_dir():
                continue
            class_embs = []
            for f in sorted(subdir.glob("*.wav"))[:5]:
                audio_neg, _ = sf.read(str(f))
                audio_neg = audio_neg.astype(np.float32)
                if audio_neg.ndim > 1:
                    audio_neg = audio_neg.mean(axis=1)
                audio_neg = pad_or_trim(audio_neg, N_SAMPLES)
                mel = audio_to_mel(audio_neg).unsqueeze(0).to(device)
                emb = kw_model(mel).cpu().numpy()
                class_embs.append(emb)
            if class_embs:
                proto = np.concatenate(class_embs, axis=0).mean(axis=0)
                proto = proto / np.linalg.norm(proto)
                class_prototypes[subdir.name] = proto

    if class_prototypes:
        # 按类名排序，打包为矩阵 [K, D]
        sorted_names = sorted(class_prototypes.keys())
        bg_matrix = np.stack([class_prototypes[n] for n in sorted_names], axis=0)
        print(f"  {len(sorted_names)} 个负类原型已计算")

        # 诊断: 正样本的 max-margin 对比评分
        pos_wake_sim = kw_all @ kw_prototype           # [N]
        pos_bg_max = (kw_all @ bg_matrix.T).max(axis=1) # [N] 取每个正样本与最近负类的相似度
        pos_contrast = pos_wake_sim - pos_bg_max
        print(f"  正样本 max-margin 对比评分: mean={pos_contrast.mean():.4f} min={pos_contrast.min():.4f}")

        # 每个负类原型与唤醒词原型的相似度及其自身 max-margin
        neg_wake_sim = bg_matrix @ kw_prototype          # [K]
        neg_bg_max = (bg_matrix @ bg_matrix.T)
        np.fill_diagonal(neg_bg_max, -1)  # 排除自身
        neg_bg_max = neg_bg_max.max(axis=1)              # [K]
        neg_contrast = neg_wake_sim - neg_bg_max
        # 危险负类: 与唤醒词相似但与其他负类不相似的
        danger_idx = np.argsort(neg_contrast)[::-1][:5]
        print(f"  最危险的负类 (原型级 max-margin):")
        for i in danger_idx:
            print(f"    {sorted_names[i]}: contrast={neg_contrast[i]:.4f} "
                  f"wake_sim={neg_wake_sim[i]:.4f}")
    else:
        bg_matrix = None
        sorted_names = []
        print("  [警告] 未找到负样本，将不使用对比校准")

    # 6. 计算说话人原型向量
    spk_prototype = None
    if speaker_verify:
        spk_all = np.concatenate(spk_embeddings, axis=0)
        spk_prototype = spk_all.mean(axis=0)
        spk_prototype = spk_prototype / np.linalg.norm(spk_prototype)

        spk_sims = spk_all @ spk_prototype
        print(f"\n声纹类内余弦相似度:")
        print(f"  mean={spk_sims.mean():.4f}  min={spk_sims.min():.4f}  max={spk_sims.max():.4f}")

    # 7. 保存模板
    TEMPLATE_DIR.mkdir(parents=True, exist_ok=True)
    template_path = TEMPLATE_DIR / f"{args.wake_word}.npy"
    template_data = {
        "keyword_prototype": kw_prototype,
        "keyword_embeddings": kw_all,
        "bg_class_matrix": bg_matrix,        # 逐类背景原型矩阵 [K, D]
        "bg_class_names": sorted_names,      # 对应类名列表
        "speaker_prototype": spk_prototype,
        "speaker_verify": speaker_verify,
        "n_samples": len(wav_files),
        "keyword_threshold": args.threshold,
        "speaker_threshold": args.speaker_threshold,
    }
    np.save(str(template_path), template_data)

    print(f"\n=== 注册完成 ===")
    print(f"模板已保存: {template_path}")
    print(f"唤醒词阈值: {args.threshold}")
    if speaker_verify:
        print(f"声纹阈值: {args.speaker_threshold}")
    print(f"下一步: python detect.py --wake-word {args.wake_word}")


def main():
    parser = argparse.ArgumentParser(description="唤醒词注册（双通道）")
    parser.add_argument("--wake-word", required=True, help="唤醒词名称")
    parser.add_argument("--threshold", type=float, default=SIMILARITY_THRESHOLD,
                        help=f"唤醒词检测阈值 (默认 {SIMILARITY_THRESHOLD})")
    parser.add_argument("--speaker-threshold", type=float, default=SPEAKER_THRESHOLD,
                        help=f"说话人验证阈值 (默认 {SPEAKER_THRESHOLD})")
    parser.add_argument("--no-speaker", action="store_true",
                        help="禁用说话人验证，仅注册唤醒词")
    parser.add_argument("--device", type=str, default=DEVICE)
    args = parser.parse_args()

    enroll(args)


if __name__ == "__main__":
    main()
