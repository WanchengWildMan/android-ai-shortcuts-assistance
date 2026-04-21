"""
数据增强模块 - 对音频进行多种变换以提升模型泛化能力

增强分两层:
  波形级: 高斯噪声、变速(含变调)、随机增益、简单混响、噪声叠加、悄悄话模拟
  Mel级:  时间拉伸(不变调)、频率遮挡、时间遮挡 (SpecAugment)
"""
import random
import numpy as np
import torch
import torch.nn.functional as TF

from config import N_SAMPLES, SAMPLE_RATE


def add_noise(audio, noise_level=0.005):
    """添加高斯白噪声"""
    noise = np.random.randn(len(audio)).astype(np.float32) * noise_level
    return audio + noise


def change_speed(audio, factor=None):
    """变速（通过重采样实现，会改变时长）"""
    if factor is None:
        factor = random.uniform(0.85, 1.15)
    indices = np.round(np.arange(0, len(audio), factor)).astype(int)
    indices = indices[indices < len(audio)]
    return audio[indices]


def add_reverb(audio, decay=0.3, delay_samples=None):
    """简单混响（单次延迟反射）"""
    if delay_samples is None:
        delay_samples = random.randint(800, 3200)  # 50~200ms @ 16kHz
    result = np.copy(audio)
    if len(audio) > delay_samples:
        result[delay_samples:] += audio[:-delay_samples] * decay
    return result


def random_gain(audio, min_db=-20, max_db=6):
    """
    随机增益调节
    范围扩大到 -20dB~+6dB，覆盖悄悄话到正常说话的响度差异
    """
    gain_db = random.uniform(min_db, max_db)
    gain = 10 ** (gain_db / 20)
    return audio * gain


def normalize_volume(audio, target_rms=0.1):
    """
    音量归一化 — 消除绝对响度作为特征
    将所有音频归一化到相同 RMS 能量级别，确保模型学习频谱形状而非响度
    注意: 不能加 min_rms 限制放大倍数，否则低灵敏度麦克风的实时信号无法
    与注册时的高质量录音对齐，导致 Mel 特征系统性偏移、相似度暴跌。
    防噪声误触依靠 VAD 门控 + max-margin 对比评分，不靠限制归一化。
    """
    rms = np.sqrt(np.mean(audio ** 2))
    if rms > 1e-6:
        audio = audio * (target_rms / rms)
    return audio.astype(np.float32)


def simulate_whisper(audio, sr=SAMPLE_RATE):
    """
    模拟悄悄话语音特征:
    1. 高通滤波去除基频（悄悄话声带不振动，基频 < 300Hz 消失）
    2. 添加气息感噪声（悄悄话的湍流气流特征）
    3. 降低整体振幅
    """
    try:
        from scipy.signal import butter, filtfilt
        # 1. 高通滤波：去除 300Hz 以下（模拟声带不振动）
        cutoff = random.uniform(200, 400)  # Hz
        nyq = sr / 2
        b, a = butter(4, cutoff / nyq, btype='high')
        audio = filtfilt(b, a, audio).astype(np.float32)
    except ImportError:
        pass

    # 2. 添加气息噪声
    breath_level = random.uniform(0.01, 0.03)
    breath_noise = np.random.randn(len(audio)).astype(np.float32) * breath_level
    audio = audio + breath_noise

    # 3. 降低振幅（悄悄话通常比正常说话低 15~30dB）
    gain = random.uniform(0.03, 0.15)
    audio = audio * gain

    return audio


def random_silence_pad(audio, target_length):
    """
    随机静默填充 — 模拟实时检测中语音只占窗口一部分的场景

    训练时使用，让模型学会从「语音占20-80%」的窗口中提取有效特征
    解决注册时语音占89%但检测时只占20-30%的分布不匹配问题

    原理: 先用能量检测截取有声段，再随机放到目标长度的某个位置
    """
    # 1. 找到有声段边界
    frame_len = int(0.025 * SAMPLE_RATE)  # 25ms帧
    hop = int(0.01 * SAMPLE_RATE)          # 10ms步进
    threshold = max(np.sqrt(np.mean(audio ** 2)) * 0.1, 0.001)

    voiced_start = 0
    voiced_end = len(audio)
    for i in range(0, len(audio) - frame_len, hop):
        if np.sqrt(np.mean(audio[i:i + frame_len] ** 2)) > threshold:
            voiced_start = i
            break
    for i in range(len(audio) - frame_len, 0, -hop):
        if np.sqrt(np.mean(audio[i:i + frame_len] ** 2)) > threshold:
            voiced_end = min(i + frame_len, len(audio))
            break

    # 2. 截取有声段（保留少量前后余量）
    margin = int(0.05 * SAMPLE_RATE)  # 50ms 余量
    voiced_start = max(0, voiced_start - margin)
    voiced_end = min(len(audio), voiced_end + margin)
    speech = audio[voiced_start:voiced_end]

    # 3. 如果有声段已经≥目标长度，直接截取
    if len(speech) >= target_length:
        start = random.randint(0, len(speech) - target_length)
        return speech[start:start + target_length]

    # 4. 将有声段随机放置到目标长度窗口中
    result = np.zeros(target_length, dtype=np.float32)
    max_offset = target_length - len(speech)
    offset = random.randint(0, max_offset)
    result[offset:offset + len(speech)] = speech

    return result


def pad_or_trim(audio, target_length):
    """
    填充或裁剪到目标长度
    过长: 随机截取一段
    过短: 零填充（居中）
    """
    if len(audio) >= target_length:
        start = random.randint(0, len(audio) - target_length)
        return audio[start:start + target_length]
    else:
        pad_left = (target_length - len(audio)) // 2
        pad_right = target_length - len(audio) - pad_left
        return np.pad(audio, (pad_left, pad_right), mode="constant")


def augment(audio, noise_audio=None, light=False):
    """
    组合随机增强

    light=False (默认): 1~3 种变换，含悄悄话模拟（用于负样本生成、推理增强）
    light=True:  0~1 种轻度变换（用于训练，保持同类样本的聚类性）
    """
    if light:
        # 训练模式: 轻度增强，保持原型网络的类内一致性
        aug_funcs = [
            lambda a: add_noise(a, random.uniform(0.001, 0.008)),
            lambda a: random_gain(a, min_db=-6, max_db=3),
            lambda a: add_reverb(a, decay=0.15),
        ]
        # 50% 概率不增强，50% 概率选 1 种
        if random.random() < 0.5:
            chosen = []
        else:
            chosen = [random.choice(aug_funcs)]
    else:
        # 完整增强: 用于负样本生成
        aug_funcs = [
            lambda a: add_noise(a, random.uniform(0.001, 0.015)),
            lambda a: change_speed(a),
            lambda a: random_gain(a),
            lambda a: add_reverb(a),
            lambda a: simulate_whisper(a),
        ]
        n_augs = random.randint(1, 3)
        chosen = random.sample(aug_funcs, n_augs)

    result = audio.copy()
    for fn in chosen:
        result = fn(result)

    # 叠加背景噪声（模拟真实环境）
    if noise_audio is not None and random.random() > 0.5:
        noise = pad_or_trim(noise_audio.copy(), len(result))
        snr = random.uniform(5, 20)  # 信噪比 (dB)
        noise_power = np.mean(noise ** 2)
        signal_power = np.mean(result ** 2)
        if noise_power > 1e-10:
            scale = np.sqrt(signal_power / (noise_power * 10 ** (snr / 10)))
            result = result + noise * scale

    # 统一长度
    result = pad_or_trim(result, N_SAMPLES)

    # 归一化防止数值溢出
    max_val = np.max(np.abs(result))
    if max_val > 1.0:
        result = result / max_val

    return result.astype(np.float32)


# ===== Mel 频谱级别增强 =====

def time_stretch_mel(mel, rate=None):
    """
    时间拉伸 — 改变语速但不改变音调
    在 Mel 频谱图上沿时间轴插值实现，等效于相位声码器但更高效

    mel: [1, N_MELS, T] 或 [N_MELS, T]
    rate: 拉伸系数，>1 加速(时间缩短)，<1 减速(时间拉长)
    """
    if rate is None:
        rate = random.uniform(0.85, 1.15)

    squeeze = mel.dim() == 2
    if squeeze:
        mel = mel.unsqueeze(0)  # [1, N_MELS, T]

    # 1. 沿时间轴插值
    new_T = max(1, int(mel.shape[-1] / rate))
    stretched = TF.interpolate(
        mel.unsqueeze(0),        # [1, 1, N_MELS, T]
        size=(mel.shape[1], new_T),
        mode='bilinear',
        align_corners=False,
    ).squeeze(0)                 # [1, N_MELS, new_T]

    # 2. 裁剪/填充到原始时间长度
    orig_T = mel.shape[-1]
    if new_T >= orig_T:
        start = random.randint(0, new_T - orig_T)
        stretched = stretched[:, :, start:start + orig_T]
    else:
        pad_left = (orig_T - new_T) // 2
        pad_right = orig_T - new_T - pad_left
        stretched = TF.pad(stretched, (pad_left, pad_right), mode='constant', value=stretched.min().item())

    if squeeze:
        stretched = stretched.squeeze(0)

    return stretched


def spec_augment(mel, n_freq_masks=1, freq_mask_param=5, n_time_masks=1, time_mask_param=15):
    """
    SpecAugment — 标准频谱增强方法 (Park et al., 2019)

    在 Mel 频谱图上随机遮挡频率带和时间段，模拟：
    - 频率遮挡: 某些频段被噪声淹没、麦克风频响差异
    - 时间遮挡: 部分音素被截断、环境声打断

    mel: [1, N_MELS, T]
    """
    mel = mel.clone()
    _, n_mels, n_frames = mel.shape

    # 1. 频率遮挡: 随机遮挡连续 f 个 Mel 频带
    for _ in range(n_freq_masks):
        f = random.randint(1, min(freq_mask_param, n_mels - 1))
        f0 = random.randint(0, n_mels - f)
        mel[:, f0:f0 + f, :] = mel.min()

    # 2. 时间遮挡: 随机遮挡连续 t 帧
    for _ in range(n_time_masks):
        t = random.randint(1, min(time_mask_param, n_frames - 1))
        t0 = random.randint(0, n_frames - t)
        mel[:, :, t0:t0 + t] = mel.min()

    return mel


def mel_augment(mel, p=0.5):
    """
    Mel 级别组合增强 — 以概率 p 应用各项增强

    mel: [1, N_MELS, T]
    返回: 增强后的 mel [1, N_MELS, T]
    """
    # 时间拉伸 (不改变音调)
    if random.random() < p:
        mel = time_stretch_mel(mel)

    # SpecAugment 频率/时间遮挡
    if random.random() < p:
        mel = spec_augment(mel)

    return mel
