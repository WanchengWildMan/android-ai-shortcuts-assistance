"""
轻量级音频编码器模型定义

包含两个独立编码器:
1. WakeWordEncoder  — 唤醒词内容编码器 (按短语分类训练)
2. SpeakerEncoder   — 说话人声纹编码器 (按说话人分类训练, GE2E Loss)

两个编码器共享相同的 CNN 骨架结构，但参数独立，各自编码不同维度的信息
"""
import torch
import torch.nn as nn
import torch.nn.functional as F

from config import N_MELS, EMBEDDING_DIM, SPEAKER_EMBEDDING_DIM


class WakeWordEncoder(nn.Module):
    """
    轻量级音频编码器

    输入: Mel 频谱图 [B, 1, n_mels, n_frames]  (如 [B, 1, 40, 150])
    输出: L2 归一化嵌入 [B, embedding_dim]       (如 [B, 64])

    结构:
      Block1: Conv(1→32) → BN → ReLU → MaxPool(2)
      Block2: Conv(32→64) → BN → ReLU → MaxPool(2)
      Block3: Conv(64→128) → BN → ReLU → AdaptiveAvgPool(1)
      Head:   Linear(128→64) → L2Norm
    """

    def __init__(self, n_mels=N_MELS, embedding_dim=EMBEDDING_DIM):
        super().__init__()

        self.features = nn.Sequential(
            # Block 1: [B, 1, 40, T] → [B, 32, 20, T/2]
            nn.Conv2d(1, 32, kernel_size=3, padding=1),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),

            # Block 2: [B, 32, 20, T/2] → [B, 64, 10, T/4]
            nn.Conv2d(32, 64, kernel_size=3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),

            # Block 3: [B, 64, 10, T/4] → [B, 128, 1, 1]
            nn.Conv2d(64, 128, kernel_size=3, padding=1),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.AdaptiveMaxPool2d(1),  # MaxPool: 聚焦语音帧峰值激活，忽略静默帧
        )

        self.head = nn.Linear(128, embedding_dim)

    def forward(self, x):
        """
        前向推理: Mel频谱 → L2归一化嵌入向量
        x: [B, 1, n_mels, n_frames]
        return: [B, embedding_dim]
        """
        x = self.features(x)           # [B, 128, 1, 1]
        x = x.view(x.size(0), -1)      # [B, 128]
        x = self.head(x)               # [B, embedding_dim]
        x = F.normalize(x, p=2, dim=1) # L2 归一化，使余弦相似度 = 点积
        return x

    def count_parameters(self):
        """返回可训练参数总量"""
        return sum(p.numel() for p in self.parameters() if p.requires_grad)


class SpeakerEncoder(nn.Module):
    """
    说话人声纹编码器 — 结构与 WakeWordEncoder 相同，但独立训练

    训练目标: 同一说话人说不同内容 → 嵌入接近
             不同说话人说相同内容 → 嵌入远离

    配合 GE2E Loss 训练，使得嵌入空间按说话人身份聚类
    """

    def __init__(self, n_mels=N_MELS, embedding_dim=SPEAKER_EMBEDDING_DIM):
        super().__init__()

        self.features = nn.Sequential(
            nn.Conv2d(1, 32, kernel_size=3, padding=1),
            nn.BatchNorm2d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),

            nn.Conv2d(32, 64, kernel_size=3, padding=1),
            nn.BatchNorm2d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(2),

            nn.Conv2d(64, 128, kernel_size=3, padding=1),
            nn.BatchNorm2d(128),
            nn.ReLU(inplace=True),
            nn.AdaptiveMaxPool2d(1),  # MaxPool: 同唤醒词编码器
        )

        self.head = nn.Linear(128, embedding_dim)

    def forward(self, x):
        x = self.features(x)
        x = x.view(x.size(0), -1)
        x = self.head(x)
        x = F.normalize(x, p=2, dim=1)
        return x

    def count_parameters(self):
        return sum(p.numel() for p in self.parameters() if p.requires_grad)


class GE2ELoss(nn.Module):
    """
    Generalized End-to-End Loss (Google, 2018)

    核心思想:
      对每个 utterance 的嵌入，计算它与所有说话人质心的相似度矩阵
      正确说话人的相似度应该最高（softmax 交叉熵）

    输入: embeddings [N_speakers, M_utterances, embed_dim]
    输出: 标量 loss

    可学习参数: w (缩放因子) 和 b (偏置)，用于校准相似度的数值范围
    """

    def __init__(self):
        super().__init__()
        # 可学习的温度参数（初始化为论文推荐值）
        self.w = nn.Parameter(torch.tensor(10.0))
        self.b = nn.Parameter(torch.tensor(-5.0))

    def forward(self, embeddings):
        """
        计算 GE2E Loss

        embeddings: [N, M, D]  N=说话人数, M=每人语句数, D=嵌入维度
        """
        N, M, D = embeddings.shape

        # 1. 计算每个说话人的质心（排除当前 utterance, 防止信息泄露）
        centroids = []
        for j in range(N):
            # 说话人 j 的质心 (排除第 i 条后的均值, 针对每条都计算一个)
            speaker_emb = embeddings[j]  # [M, D]
            centroid_excl = []
            for i in range(M):
                # 去掉第 i 条的均值
                mask = torch.ones(M, dtype=torch.bool)
                mask[i] = False
                c = speaker_emb[mask].mean(dim=0)
                c = F.normalize(c, p=2, dim=0)
                centroid_excl.append(c)
            centroids.append(torch.stack(centroid_excl))  # [M, D]

        # 2. 计算全体说话人的整体质心（用于负对比较）
        all_centroids = embeddings.mean(dim=1)  # [N, D]
        all_centroids = F.normalize(all_centroids, p=2, dim=1)

        # 3. 构建相似度矩阵并计算 softmax 损失
        loss = 0.0
        correct = 0
        total = 0

        for j in range(N):
            for i in range(M):
                e = embeddings[j, i]  # [D]
                sims = []
                for k in range(N):
                    if k == j:
                        # 正样本: 用排除自身的质心
                        sim = torch.dot(e, centroids[j][i])
                    else:
                        # 负样本: 用该说话人的整体质心
                        sim = torch.dot(e, all_centroids[k])
                    sims.append(sim)

                sims = torch.stack(sims)      # [N]
                sims = self.w * sims + self.b  # 缩放 + 偏置

                # 交叉熵: 正确标签 = j (当前说话人索引)
                target = torch.tensor(j, device=sims.device)
                loss += F.cross_entropy(sims.unsqueeze(0), target.unsqueeze(0))

                # 准确率统计
                if sims.argmax().item() == j:
                    correct += 1
                total += 1

        loss = loss / (N * M)
        accuracy = correct / total

        return loss, accuracy
