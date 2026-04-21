"""
实时唤醒词检测脚本（双通道）
同时进行: 唤醒词内容检测 + 说话人身份验证
两个条件都满足才触发唤醒
"""
import argparse
import time
from collections import deque

import numpy as np
import torch
import sounddevice as sd

from config import (
    SAMPLE_RATE, N_SAMPLES, N_MELS, EMBEDDING_DIM, SPEAKER_EMBEDDING_DIM,
    MODEL_DIR, TEMPLATE_DIR,
    DETECTION_STRIDE, DETECTION_COOLDOWN, VAD_RMS_THRESHOLD,
    SIMILARITY_THRESHOLD, SPEAKER_THRESHOLD, DEVICE,
)
from model import WakeWordEncoder, SpeakerEncoder
from enroll import load_encoder, audio_to_mel
from augment import pad_or_trim


class WakeWordDetector:
    """
    实时唤醒词检测器（双通道）

    工作流程:
      麦克风 → 缓冲区(1.5s) → 每0.5s提取Mel
        ├─→ 唤醒词编码器 → 内容相似度 ≥ 阈值A?
        └─→ 说话人编码器 → 声纹相似度 ≥ 阈值B?
                            ↓
                    两个都满足 → 触发唤醒
    """

    def __init__(self, wake_word, device="cpu", debug=False):
        self.device = torch.device(device)
        self.debug = debug

        # 1. 加载唤醒词编码器
        kw_path = MODEL_DIR / "encoder_best.pt"
        if not kw_path.exists():
            kw_path = MODEL_DIR / "encoder_final.pt"
        if not kw_path.exists():
            raise FileNotFoundError("找不到唤醒词编码器，请先运行 python train.py")
        self.kw_model = load_encoder(kw_path, self.device, WakeWordEncoder, EMBEDDING_DIM)

        # 2. 加载唤醒词模板
        template_path = TEMPLATE_DIR / f"{wake_word}.npy"
        if not template_path.exists():
            raise FileNotFoundError(
                f"找不到模板 {template_path}，请先运行 python enroll.py --wake-word {wake_word}"
            )
        template_data = np.load(str(template_path), allow_pickle=True).item()

        self.kw_prototype = torch.FloatTensor(
            template_data["keyword_prototype"]
        ).to(self.device)
        self.kw_threshold = template_data.get("keyword_threshold", SIMILARITY_THRESHOLD)

        # 背景原型矩阵 — 逐类原型 [K, D]，用于 max-margin 对比校准
        # score = sim(query, wake_proto) - max_k(sim(query, neg_proto_k))
        bg_matrix = template_data.get("bg_class_matrix", None)
        self.bg_matrix = (
            torch.FloatTensor(bg_matrix).to(self.device)
            if bg_matrix is not None else None
        )

        # 3. 加载说话人编码器（如果注册时开启了人验证）
        self.speaker_verify = template_data.get("speaker_verify", False)
        self.spk_model = None
        self.spk_prototype = None
        self.spk_threshold = SPEAKER_THRESHOLD

        if self.speaker_verify:
            spk_path = MODEL_DIR / "speaker_encoder_best.pt"
            if not spk_path.exists():
                spk_path = MODEL_DIR / "speaker_encoder_final.pt"
            if spk_path.exists():
                self.spk_model = load_encoder(
                    spk_path, self.device, SpeakerEncoder, SPEAKER_EMBEDDING_DIM
                )
                self.spk_prototype = torch.FloatTensor(
                    template_data["speaker_prototype"]
                ).to(self.device)
                self.spk_threshold = template_data.get(
                    "speaker_threshold", SPEAKER_THRESHOLD
                )
            else:
                print("警告: 注册时开启了声纹验证但找不到说话人编码器，将退化为仅内容检测")
                self.speaker_verify = False

        # 4. 音频环形缓冲区
        self.buffer = deque(maxlen=N_SAMPLES)
        self.stride_samples = int(DETECTION_STRIDE * SAMPLE_RATE)
        self.samples_since_last = 0

        # 5. 冷却计时器
        self.cooldown = DETECTION_COOLDOWN
        self.last_trigger_time = 0

        print(f"检测器已初始化:")
        print(f"  唤醒词: {wake_word}")
        print(f"  内容阈值: {self.kw_threshold}")
        print(f"  声纹验证: {'开启 (阈值=' + str(self.spk_threshold) + ')' if self.speaker_verify else '关闭'}")
        print(f"  窗口: {N_SAMPLES / SAMPLE_RATE:.1f}s  步进: {DETECTION_STRIDE}s")

    def process_audio(self, audio_chunk):
        """
        处理一块音频数据
        返回: 触发时返回 (内容相似度, 声纹相似度) 元组，否则 None
        """
        for sample in audio_chunk:
            self.buffer.append(sample)

        self.samples_since_last += len(audio_chunk)

        if (self.samples_since_last >= self.stride_samples
                and len(self.buffer) >= N_SAMPLES):
            self.samples_since_last = 0
            return self._detect()

        return None

    def _detect(self):
        """执行一次双通道检测"""
        now = time.time()
        if now - self.last_trigger_time < self.cooldown:
            return None

        # 取出缓冲区音频，提取 Mel
        audio = np.array(list(self.buffer), dtype=np.float32)
        audio = pad_or_trim(audio, N_SAMPLES)

        # 能量门控: 原始音频能量太低说明是静默/环境噪声，跳过检测
        # 避免 normalize_volume 把噪声放大后产生虚假匹配
        rms = np.sqrt(np.mean(audio ** 2))
        if rms < VAD_RMS_THRESHOLD:
            if self.debug:
                print(f"  [跳过] RMS={rms:.6f} < {VAD_RMS_THRESHOLD}")
            return None

        with torch.no_grad():
            mel = audio_to_mel(audio).unsqueeze(0).to(self.device)

            # 通道1: 唤醒词内容检测 (max-margin 对比校准评分)
            kw_emb = self.kw_model(mel).squeeze(0)
            kw_sim_raw = torch.dot(kw_emb, self.kw_prototype).item()

            # max-margin 对比: 减去与所有负类原型中最相似的那个
            # 粉红噪声 → 与噪声原型 ~0.99 → 减去后 << 0 → 拒绝
            # 真实唤醒词 → 与所有负类原型都不太相似 → 减去后仍然高 → 接受
            if self.bg_matrix is not None:
                bg_sims = torch.mv(self.bg_matrix, kw_emb)  # [K]
                bg_max_sim = bg_sims.max().item()
                kw_sim = kw_sim_raw - bg_max_sim
            else:
                kw_sim = kw_sim_raw
                bg_max_sim = 0.0

            if self.debug:
                print(f"  [检测] RMS={rms:.4f}  原始={kw_sim_raw:.4f}  最近负类={bg_max_sim:.4f}  对比={kw_sim:.4f}  ", end="")

            if kw_sim < self.kw_threshold:
                if self.debug:
                    print("→ 内容未达标")
                return None

            # 通道2: 说话人声纹验证
            spk_sim = 1.0  # 默认通过（未开启验证时）
            if self.speaker_verify and self.spk_model is not None:
                spk_emb = self.spk_model(mel).squeeze(0)
                spk_sim = torch.dot(spk_emb, self.spk_prototype).item()

                if self.debug:
                    print(f"声纹={spk_sim:.4f}  ", end="")

                if spk_sim < self.spk_threshold:
                    if self.debug:
                        print("→ 声纹未达标")
                    return None

        if self.debug:
            print("→ 触发!")
        # 双通道都通过 → 触发唤醒
        self.last_trigger_time = now
        return (kw_sim, spk_sim)


def main():
    parser = argparse.ArgumentParser(description="实时唤醒词检测")
    parser.add_argument("--wake-word", required=True, help="唤醒词名称")
    parser.add_argument("--device", type=str, default=DEVICE)
    parser.add_argument("--threshold", type=float, default=None,
                        help="覆盖唤醒词内容阈值")
    parser.add_argument("--speaker-threshold", type=float, default=None,
                        help="覆盖说话人声纹阈值")
    parser.add_argument("--debug", action="store_true",
                        help="打印每帧 RMS 和分数用于调试")
    args = parser.parse_args()

    detector = WakeWordDetector(args.wake_word, args.device, debug=args.debug)
    if args.threshold is not None:
        detector.kw_threshold = args.threshold
        print(f"  内容阈值已覆盖为: {args.threshold}")
    if args.speaker_threshold is not None and detector.speaker_verify:
        detector.spk_threshold = args.speaker_threshold
        print(f"  声纹阈值已覆盖为: {args.speaker_threshold}")

    print("\n正在监听... (Ctrl+C 退出)\n")

    chunk_size = int(SAMPLE_RATE * 0.1)  # 100ms 一块

    def audio_callback(indata, frames, time_info, status):
        if status:
            print(f"[音频状态] {status}")

        audio = indata[:, 0].astype(np.float32)
        result = detector.process_audio(audio)

        if result is not None:
            kw_sim, spk_sim = result
            if detector.speaker_verify:
                print(f"  >>> 唤醒! 内容={kw_sim:.4f} 声纹={spk_sim:.4f} <<<")
            else:
                print(f"  >>> 唤醒! 内容相似度={kw_sim:.4f} <<<")

    try:
        with sd.InputStream(
            samplerate=SAMPLE_RATE,
            channels=1,
            blocksize=chunk_size,
            callback=audio_callback,
        ):
            while True:
                time.sleep(0.1)
    except KeyboardInterrupt:
        print("\n停止监听")


if __name__ == "__main__":
    main()
