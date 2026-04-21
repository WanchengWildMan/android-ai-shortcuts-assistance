"""
PyTorch 数据集 - 为原型网络训练提供 episode 采样
扫描 negative/ 目录下的子目录，每个子目录作为一个类别
支持将 positive/ 目录下的正样本也加入训练（作为额外类别）
"""
import random
from pathlib import Path

import numpy as np
import torch
import torchaudio.transforms as T
import soundfile as sf

from config import (
    SAMPLE_RATE, N_SAMPLES, N_MELS, N_FFT, HOP_LENGTH, NEGATIVE_DIR,
    POSITIVE_DIR, TTS_POSITIVE_DIR, TTS_VOICES, TEST_SPLIT_COUNT,
)
from augment import augment, pad_or_trim, random_silence_pad, normalize_volume, mel_augment


class EpisodeDataset:
    """
    按类别组织的音频数据集，支持原型网络 episode 采样

    目录结构:
      negative/
        你好/
          Xiaoxiao.wav
          Yunxi.wav
          ...
        谢谢/
          ...
        _noise_white/
          ...
    """

    def __init__(self, data_dir=None, do_augment=True, include_positive=False,
                 light_augment=False, split="train"):
        self.data_dir = Path(data_dir or NEGATIVE_DIR)
        self.do_augment = do_augment
        self.include_positive = include_positive
        self.light_augment = light_augment  # 训练时用轻度增强
        self.split = split  # "train" / "test" / "all" — 控制正样本分割

        # Mel 频谱提取器
        self.mel_transform = T.MelSpectrogram(
            sample_rate=SAMPLE_RATE,
            n_fft=N_FFT,
            hop_length=HOP_LENGTH,
            n_mels=N_MELS,
        )
        self.amp_to_db = T.AmplitudeToDB()

        # 噪声文件列表（用于增强时叠加）
        self.noise_files = []

        # Mel 缓存: filepath → mel tensor (消除训练时重复 I/O)
        self._mel_cache = {}

        # 按子目录扫描类别
        self.classes = {}  # class_name → [file_paths]
        self._scan_dirs()

    def _scan_dirs(self):
        """扫描数据目录，每个子目录作为一个类别"""
        if not self.data_dir.exists():
            return

        for subdir in sorted(self.data_dir.iterdir()):
            if not subdir.is_dir():
                continue

            wav_files = sorted(str(f) for f in subdir.glob("*.wav"))
            if len(wav_files) < 2:
                continue

            class_name = subdir.name
            self.classes[class_name] = wav_files

            # 收集噪声类的文件（用于增强时叠加）
            if class_name.startswith("_noise"):
                self.noise_files.extend(wav_files)

        self.class_names = list(self.classes.keys())

        # 扫描正样本目录（用户录音作为额外训练类别）
        if self.include_positive:
            self._scan_positive_dir()

        print(f"[数据集] {len(self.class_names)} 个类别, "
              f"{sum(len(v) for v in self.classes.values())} 个样本")

        # 按说话人(TTS音色)重组索引，供说话人编码器训练使用
        self._build_speaker_index()

    def load_audio(self, filepath):
        """加载音频文件 → float32 一维数组，统一长度"""
        audio, sr = sf.read(filepath)
        audio = audio.astype(np.float32)

        # 确保单声道
        if audio.ndim > 1:
            audio = audio.mean(axis=1)

        # 裁剪/填充到统一长度
        # 训练时 50% 概率使用随机静默填充，模拟实时检测中语音只占窗口一部分
        if self.do_augment and random.random() < 0.5:
            audio = random_silence_pad(audio, N_SAMPLES)
        else:
            audio = pad_or_trim(audio, N_SAMPLES)

        # 数据增强 (light=True 时只做轻度增强，保持同类样本聚类性)
        if self.do_augment:
            noise = None
            if self.noise_files and not self.light_augment:
                noise_file = random.choice(self.noise_files)
                noise_audio, _ = sf.read(noise_file)
                noise = noise_audio.astype(np.float32)
            audio = augment(audio, noise, light=self.light_augment)

        return audio

    def audio_to_mel(self, audio):
        """音频波形 → Mel频谱图张量 [1, N_MELS, T]（音量归一化后）"""
        # 音量归一化：消除绝对响度差异，确保模型学频谱形状而非响度
        audio = normalize_volume(audio, target_rms=0.1)
        waveform = torch.FloatTensor(audio).unsqueeze(0)  # [1, N_SAMPLES]
        mel = self.mel_transform(waveform)                 # [1, N_MELS, T]
        mel = self.amp_to_db(mel)                          # dB 刻度

        # Mel 级别增强: 仅在非轻度模式下应用 (训练时关闭以保持原型质量)
        if self.do_augment and not self.light_augment:
            mel = mel_augment(mel, p=0.5)

        return mel

    def _scan_positive_dir(self):
        """
        扫描正样本目录，每个唤醒词子目录作为额外训练类别

        正样本按 split 分割:
        - "train": 排除末尾 TEST_SPLIT_COUNT 条，仅用前部训练
        - "test":  仅取末尾 TEST_SPLIT_COUNT 条
        - "all":   全部使用 (兼容旧行为)

        正样本数量少(~20条)，靠波形级+Mel级增强扩增多样性:
        - load_audio 中的波形增强: 噪声/增益/混响/悄悄话模拟
        - audio_to_mel 中的 Mel 增强: 时间拉伸/SpecAugment
        """
        positive_dir = Path(POSITIVE_DIR)
        if not positive_dir.exists():
            return

        for subdir in sorted(positive_dir.iterdir()):
            if not subdir.is_dir():
                continue

            all_wavs = sorted(str(f) for f in subdir.glob("*.wav"))
            if len(all_wavs) < 2:
                continue

            # 按 split 分割: 末尾 TEST_SPLIT_COUNT 条为测试集，其余为训练集
            if self.split == "test":
                wav_files = all_wavs[-TEST_SPLIT_COUNT:]
            elif self.split == "train":
                wav_files = all_wavs[:-TEST_SPLIT_COUNT] if len(all_wavs) > TEST_SPLIT_COUNT else all_wavs
            else:  # "all"
                wav_files = all_wavs

            if len(wav_files) < 1:
                continue

            # 用 _pos_ 前缀与 TTS 负样本区分
            class_name = f"_pos_{subdir.name}"
            self.classes[class_name] = wav_files
            if class_name not in self.class_names:
                self.class_names.append(class_name)

        # 扫描 TTS 合成正样本目录 (仅训练用，不参与 test split)
        # TTS 正样本与用户录音合并到同一个 _pos_{wake_word} 类
        # 原理: 关键词编码器需要从多音色样本中学习"小万"的声学模式，而非特定人声
        if self.split != "test":
            self._scan_tts_positive_dir()

        n_pos = sum(len(self.classes[c]) for c in self.class_names if c.startswith("_pos_"))
        if n_pos > 0:
            split_label = {"train": "训练集", "test": "测试集", "all": "全部"}.get(self.split, self.split)
            print(f"[正样本] {len([c for c in self.class_names if c.startswith('_pos_')])} 个唤醒词, "
                  f"{n_pos} 个样本 ({split_label})")

    def _scan_tts_positive_dir(self):
        """
        扫描 TTS 合成正样本目录，合并到对应的 _pos_ 类中

        TTS 正样本全部用于训练，不参与 test split（它们不是真人录音）
        与用户录音合并到同一个 _pos_ 类，让关键词编码器从多音色中学声学模式
        """
        tts_pos_dir = Path(TTS_POSITIVE_DIR)
        if not tts_pos_dir.exists():
            return

        n_tts = 0
        for subdir in sorted(tts_pos_dir.iterdir()):
            if not subdir.is_dir():
                continue

            wav_files = sorted(str(f) for f in subdir.glob("*.wav"))
            if not wav_files:
                continue

            # 合并到同名的 _pos_ 类中 (如 _pos_小万)
            class_name = f"_pos_{subdir.name}"
            if class_name in self.classes:
                self.classes[class_name].extend(wav_files)
            else:
                self.classes[class_name] = wav_files
                self.class_names.append(class_name)

            n_tts += len(wav_files)

        if n_tts > 0:
            print(f"[TTS正样本] {n_tts} 个合成样本已合并到正类")

    def precompute_mels(self):
        """
        预计算所有音频的 Mel 频谱并缓存到内存

        消除训练时每个 episode 重复读磁盘的 I/O 瓶颈
        11,692 样本 × [1, 40, 150] float32 ≈ 280MB，完全可放内存
        预计算不做增强，增强在采样时在线应用
        """
        total = sum(len(files) for files in self.classes.values())
        loaded = 0

        for class_name, files in self.classes.items():
            for f in files:
                if f not in self._mel_cache:
                    try:
                        audio, sr = sf.read(f)
                        audio = audio.astype(np.float32)
                        if audio.ndim > 1:
                            audio = audio.mean(axis=1)
                        audio = pad_or_trim(audio, N_SAMPLES)
                        # 不做增强，存原始 mel (增强在采样时在线做)
                        audio = normalize_volume(audio, target_rms=0.1)
                        waveform = torch.FloatTensor(audio).unsqueeze(0)
                        mel = self.mel_transform(waveform)
                        mel = self.amp_to_db(mel)
                        self._mel_cache[f] = mel
                    except Exception:
                        pass
                loaded += 1

            if loaded % 2000 == 0 or loaded == total:
                print(f"  [预计算] {loaded}/{total} ({loaded/total:.0%})")

        print(f"[Mel缓存] {len(self._mel_cache)} 个频谱已缓存到内存")

    def _get_mel(self, filepath):
        """获取 Mel 频谱: 优先从缓存读取，否则实时计算"""
        if filepath in self._mel_cache:
            mel = self._mel_cache[filepath].clone()
            # 在线增强
            if self.do_augment:
                # 随机静默填充 (mel级): 模拟实时检测时语音只占窗口一部分
                # 随机将前/后若干帧设为静默值，等价于音频级的 random_silence_pad
                if random.random() < 0.5:
                    n_frames = mel.shape[-1]
                    silence_ratio = random.uniform(0.2, 0.6)
                    silence_frames = int(n_frames * silence_ratio)
                    silence_val = mel.min() - 10.0  # dB 域中静默值
                    if random.random() < 0.5:
                        mel[:, :, :silence_frames] = silence_val
                    else:
                        mel[:, :, -silence_frames:] = silence_val
                # 轻度 mel 增强
                if self.light_augment and random.random() < 0.3:
                    mel = mel_augment(mel, p=0.5)
            return mel
        else:
            audio = self.load_audio(filepath)
            return self.audio_to_mel(audio)

    def sample_episode(self, n_way, k_shot, q_query):
        """
        采样一个原型网络训练 episode

        返回:
          support: [n_way*k_shot, 1, N_MELS, T]  支持集
          query:   [n_way*q_query, 1, N_MELS, T]  查询集
          labels:  [n_way*q_query]                类别标签 0~n_way-1
          actual_n_way: 实际使用的类别数
        """
        # 1. 强制将正样本类（唤醒词）加入每个 episode
        # 原理: 正类只有 1 个，若纯随机采样，被选到的概率只有 1/74 ≈ 1.3%
        # 这意味着 98.7% 的梯度根本不包含正类信号，模型根本学不到识别唤醒词
        positive_classes = [c for c in self.class_names if c.startswith("_pos_")]
        forced_classes = random.sample(positive_classes, min(len(positive_classes), 1)) if positive_classes else []

        # 2. 从剩余负类中随机补充，凑够 n_way
        remaining = [c for c in self.class_names if c not in forced_classes]
        actual_n_way = min(n_way, len(self.class_names))
        n_random = actual_n_way - len(forced_classes)
        chosen_classes = forced_classes + random.sample(remaining, min(n_random, len(remaining)))

        support_mels = []
        query_mels = []
        query_labels = []

        for class_idx, class_name in enumerate(chosen_classes):
            files = self.classes[class_name]
            n_needed = k_shot + q_query

            # 样本不足时允许重复采样
            if len(files) >= n_needed:
                chosen = random.sample(files, n_needed)
            else:
                chosen = random.choices(files, k=n_needed)

            support_files = chosen[:k_shot]
            query_files = chosen[k_shot:]

            for f in support_files:
                mel = self._get_mel(f)
                support_mels.append(mel)

            for f in query_files:
                mel = self._get_mel(f)
                query_mels.append(mel)
                query_labels.append(class_idx)

        support = torch.stack(support_mels)   # [n_way*k_shot, 1, N_MELS, T]
        query = torch.stack(query_mels)       # [n_way*q_query, 1, N_MELS, T]
        labels = torch.LongTensor(query_labels)

        return support, query, labels, actual_n_way

    @property
    def num_classes(self):
        return len(self.class_names)

    @property
    def total_samples(self):
        return sum(len(v) for v in self.classes.values())

    def _build_speaker_index(self):
        """
        按说话人(TTS音色)重新组织文件索引

        generate_negatives.py 生成的文件名格式: {voice_tag}_v{vol}.wav / {voice_tag}_v{vol}_aug{i}.wav
        voice_tag 从 TTS_VOICES 中提取 (如 Xiaoxiao, Yunxi, WanLung)

        这样同一个 voice_tag 说不同短语的文件归为同一说话人
        """
        # 从 TTS_VOICES 提取音色标签
        voice_tags = set()
        for v in TTS_VOICES:
            tag = v.split("-")[-1].replace("Neural", "")
            voice_tags.add(tag)

        self.speakers = {}  # speaker_tag → [file_paths]

        for class_name, files in self.classes.items():
            # 跳过噪声类（无说话人概念）
            if class_name.startswith("_noise") or class_name == "_silence":
                continue

            for f in files:
                fname = Path(f).stem  # 如 "Xiaoxiao_v0" 或 "Xiaoxiao_v-30_aug1"
                # 提取 voice_tag: 文件名第一个下划线前的部分
                parts = fname.split("_")
                tag = parts[0]

                if tag in voice_tags:
                    if tag not in self.speakers:
                        self.speakers[tag] = []
                    self.speakers[tag].append(f)

        self.speaker_names = [s for s in self.speakers if len(self.speakers[s]) >= 2]
        n_speaker_files = sum(len(self.speakers[s]) for s in self.speaker_names)
        print(f"[说话人索引] {len(self.speaker_names)} 个说话人, "
              f"{n_speaker_files} 个样本")

    def sample_speaker_episode(self, n_speakers, m_utterances):
        """
        为 GE2E Loss 采样一个说话人 episode

        采样逻辑: 随机选 n_speakers 个说话人，每人取 m_utterances 条语音
        同一说话人的语音来自不同短语(不同内容)，使编码器只学音色不学内容

        返回:
          mel_batch: [n_speakers, m_utterances, 1, N_MELS, T]
          actual_n: 实际采样到的说话人数
        """
        actual_n = min(n_speakers, len(self.speaker_names))
        chosen = random.sample(self.speaker_names, actual_n)

        batch = []
        for speaker in chosen:
            files = self.speakers[speaker]

            if len(files) >= m_utterances:
                selected = random.sample(files, m_utterances)
            else:
                selected = random.choices(files, k=m_utterances)

            speaker_mels = []
            for f in selected:
                mel = self._get_mel(f)
                speaker_mels.append(mel)

            batch.append(torch.stack(speaker_mels))  # [m_utterances, 1, N_MELS, T]

        mel_batch = torch.stack(batch)  # [actual_n, m_utterances, 1, N_MELS, T]
        return mel_batch, actual_n

    @property
    def num_speakers(self):
        return len(self.speaker_names)
