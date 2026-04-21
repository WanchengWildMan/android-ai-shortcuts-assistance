"""
模型训练脚本
支持两种训练模式:
  1. 唤醒词编码器 — 原型网络，按短语分类 (train --mode keyword)
  2. 说话人编码器 — GE2E Loss，按TTS音色分类 (train --mode speaker)
  3. 两者同时训练 (train --mode both，默认)

使用相同的 TTS 合成数据，按不同维度分类训练
"""
import argparse

import torch
import torch.nn.functional as F

from config import (
    NEGATIVE_DIR, MODEL_DIR, N_MELS, EMBEDDING_DIM,
    TRAIN_EPISODES, N_WAY, K_SHOT, Q_QUERY,
    LEARNING_RATE, LR_STEP_SIZE, LR_GAMMA, DEVICE,
    SPEAKER_TRAIN_EPISODES, SPEAKER_N_SPEAKERS, SPEAKER_M_UTTERANCES,
    SPEAKER_EMBEDDING_DIM, SPEAKER_LEARNING_RATE,
)
from model import WakeWordEncoder, SpeakerEncoder, GE2ELoss
from dataset import EpisodeDataset


def prototypical_loss(support_emb, query_emb, labels, n_way, k_shot):
    """
    原型网络损失函数

    1. 将支持集嵌入按类取均值 → 原型向量
    2. 计算查询样本与各原型的余弦相似度
    3. 交叉熵损失 + 准确率

    support_emb: [n_way * k_shot, embed_dim]
    query_emb:   [n_way * q_query, embed_dim]
    labels:      [n_way * q_query]
    """
    # 1. 计算类原型
    support_emb = support_emb.view(n_way, k_shot, -1)  # [n_way, k_shot, dim]
    prototypes = support_emb.mean(dim=1)                 # [n_way, dim]
    prototypes = F.normalize(prototypes, p=2, dim=1)     # L2 归一化

    # 2. 余弦相似度 (因嵌入已 L2 归一化，点积 = 余弦相似度)
    # 取负值作为"距离"，再乘温度系数提高区分度
    logits = torch.mm(query_emb, prototypes.t()) * 10    # [n_q, n_way]

    # 3. 交叉熵损失
    loss = F.cross_entropy(logits, labels)

    # 4. 准确率
    preds = logits.argmax(dim=1)
    acc = (preds == labels).float().mean()

    return loss, acc


def train(args):
    # 1. 加载数据集
    # light_augment=True: 训练时只用轻度增强，保持同类样本的聚类性
    # include_positive=True: 正样本也参与训练作为额外类别
    dataset = EpisodeDataset(
        NEGATIVE_DIR, do_augment=True, include_positive=True, light_augment=True
    )

    if dataset.num_classes < 3:
        print(f"错误: 至少需要 3 个类别，当前只有 {dataset.num_classes} 个")
        print("请先运行: python generate_negatives.py")
        return

    # 预计算 Mel 缓存，消除训练时 I/O 瓶颈
    print("预计算 Mel 频谱缓存...")
    dataset.precompute_mels()

    device = torch.device(args.device)
    MODEL_DIR.mkdir(parents=True, exist_ok=True)

    # 2. 按模式训练
    if args.mode in ("keyword", "both"):
        print("\n=== 训练唤醒词编码器 (原型网络) ===")
        train_keyword_encoder(dataset, device, args)

    if args.mode in ("speaker", "both"):
        if dataset.num_speakers < 3:
            print(f"\n错误: 说话人编码器至少需要 3 个说话人，当前只有 {dataset.num_speakers} 个")
            print("请确保 TTS 数据已正确生成")
        else:
            print("\n=== 训练说话人编码器 (GE2E) ===")
            train_speaker_encoder(dataset, device, args)


def train_keyword_encoder(dataset, device, args):
    """训练唤醒词内容编码器 — 使用原型网络"""
    model = WakeWordEncoder(n_mels=N_MELS, embedding_dim=EMBEDDING_DIM).to(device)
    print(f"唤醒词编码器参数量: {model.count_parameters():,}")

    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    scheduler = torch.optim.lr_scheduler.StepLR(
        optimizer, step_size=LR_STEP_SIZE, gamma=LR_GAMMA
    )

    n_way = min(args.n_way, dataset.num_classes)
    best_acc = 0.0

    model.train()
    for episode in range(1, args.episodes + 1):
        support, query, labels, actual_n_way = dataset.sample_episode(
            n_way=n_way, k_shot=args.k_shot, q_query=args.q_query
        )
        support = support.to(device)
        query = query.to(device)
        labels = labels.to(device)

        support_emb = model(support)
        query_emb = model(query)

        loss, acc = prototypical_loss(
            support_emb, query_emb, labels,
            n_way=actual_n_way, k_shot=args.k_shot
        )

        optimizer.zero_grad()
        loss.backward()
        optimizer.step()
        scheduler.step()

        if episode % 50 == 0:
            lr = scheduler.get_last_lr()[0]
            print(
                f"[Keyword {episode:4d}/{args.episodes}]  "
                f"Loss={loss.item():.4f}  Acc={acc.item():.2%}  LR={lr:.6f}"
            )

        if acc.item() > best_acc:
            best_acc = acc.item()
            torch.save(model.state_dict(), MODEL_DIR / "encoder_best.pt")

    torch.save(model.state_dict(), MODEL_DIR / "encoder_final.pt")
    print(f"唤醒词编码器训练完成: 最佳准确率={best_acc:.2%}")


def train_speaker_encoder(dataset, device, args):
    """
    训练说话人声纹编码器 — 使用 GE2E Loss

    关键: 按说话人 (TTS音色) 采样 episode，每个说话人取不同短语的语音
    这样编码器被迫忽略内容差异，只学说话人身份特征
    """
    model = SpeakerEncoder(
        n_mels=N_MELS, embedding_dim=SPEAKER_EMBEDDING_DIM
    ).to(device)
    ge2e_loss = GE2ELoss().to(device)
    print(f"说话人编码器参数量: {model.count_parameters():,}")

    # GE2E Loss 的 w, b 也需要训练
    optimizer = torch.optim.Adam(
        list(model.parameters()) + list(ge2e_loss.parameters()),
        lr=SPEAKER_LEARNING_RATE,
    )
    scheduler = torch.optim.lr_scheduler.StepLR(
        optimizer, step_size=LR_STEP_SIZE, gamma=LR_GAMMA
    )

    n_speakers = min(SPEAKER_N_SPEAKERS, dataset.num_speakers)
    m_utterances = SPEAKER_M_UTTERANCES
    best_acc = 0.0
    episodes = args.speaker_episodes or SPEAKER_TRAIN_EPISODES

    model.train()
    ge2e_loss.train()

    for episode in range(1, episodes + 1):
        # 采样: [n_speakers, m_utterances, 1, N_MELS, T]
        mel_batch, actual_n = dataset.sample_speaker_episode(n_speakers, m_utterances)
        mel_batch = mel_batch.to(device)

        # 展平推理: [n*m, 1, N_MELS, T] → [n*m, D]
        n, m = actual_n, m_utterances
        flat = mel_batch.view(n * m, *mel_batch.shape[2:])
        embeddings_flat = model(flat)

        # 重组: [n, m, D]
        embeddings = embeddings_flat.view(n, m, -1)

        # 计算 GE2E Loss
        loss, acc = ge2e_loss(embeddings)

        optimizer.zero_grad()
        loss.backward()
        # 梯度裁剪，防止 GE2E 训练不稳定
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=3.0)
        torch.nn.utils.clip_grad_norm_(ge2e_loss.parameters(), max_norm=1.0)
        optimizer.step()
        scheduler.step()

        # 约束 w > 0 (论文要求) — 放在 step() 之后，避免 inplace 修改影响反向传播
        with torch.no_grad():
            ge2e_loss.w.clamp_(min=0.1)

        if episode % 50 == 0:
            lr = scheduler.get_last_lr()[0]
            print(
                f"[Speaker {episode:4d}/{episodes}]  "
                f"Loss={loss.item():.4f}  Acc={acc:.2%}  "
                f"w={ge2e_loss.w.item():.2f}  b={ge2e_loss.b.item():.2f}  LR={lr:.6f}"
            )

        if acc > best_acc:
            best_acc = acc
            torch.save(model.state_dict(), MODEL_DIR / "speaker_encoder_best.pt")

    torch.save(model.state_dict(), MODEL_DIR / "speaker_encoder_final.pt")
    print(f"说话人编码器训练完成: 最佳准确率={best_acc:.2%}")


def main():
    parser = argparse.ArgumentParser(description="模型训练")
    parser.add_argument("--mode", choices=["keyword", "speaker", "both"],
                        default="both", help="训练模式: keyword/speaker/both")
    parser.add_argument("--episodes", type=int, default=TRAIN_EPISODES,
                        help="唤醒词编码器训练 episode 数")
    parser.add_argument("--speaker-episodes", type=int, default=None,
                        help="说话人编码器训练 episode 数 (默认同 config)")
    parser.add_argument("--n-way", type=int, default=N_WAY)
    parser.add_argument("--k-shot", type=int, default=K_SHOT)
    parser.add_argument("--q-query", type=int, default=Q_QUERY)
    parser.add_argument("--lr", type=float, default=LEARNING_RATE)
    parser.add_argument("--device", type=str, default=DEVICE)
    args = parser.parse_args()

    train(args)


if __name__ == "__main__":
    main()
