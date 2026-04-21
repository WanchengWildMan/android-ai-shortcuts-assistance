"""
录音采集脚本 - 录制唤醒词正样本
交互式引导用户录制指定次数的唤醒词音频
"""
import sys
import time
import numpy as np
import sounddevice as sd
import soundfile as sf

from config import (
    SAMPLE_RATE, CLIP_DURATION, POSITIVE_DIR,
    RECORD_COUNT, RECORD_COUNTDOWN
)


def record_clip(duration, sample_rate):
    """录制一个音频片段，返回 float32 一维数组"""
    print(f"  [录音中] {duration}秒...")
    audio = sd.rec(
        int(duration * sample_rate),
        samplerate=sample_rate,
        channels=1,
        dtype="float32",
    )
    sd.wait()
    return audio.flatten()


def main():
    # 1. 获取用户输入
    wake_word = input("请输入唤醒词名称（用于文件命名，如 '小爱'）: ").strip()
    if not wake_word:
        print("错误: 唤醒词不能为空")
        sys.exit(1)

    count_input = input(f"录制次数（默认{RECORD_COUNT}）: ").strip()
    count = int(count_input) if count_input else RECORD_COUNT

    # 2. 创建保存目录
    save_dir = POSITIVE_DIR / wake_word
    save_dir.mkdir(parents=True, exist_ok=True)

    # 扫描已有文件的最大编号，接续录制（即使中间文件被删也不会覆盖）
    existing = list(save_dir.glob(f"{wake_word}_*.wav"))
    start_idx = 0
    if existing:
        import re
        for f in existing:
            m = re.search(r'_(\d+)\.wav$', f.name)
            if m:
                start_idx = max(start_idx, int(m.group(1)) + 1)

    # 3. 打印录制信息
    print(f"\n=== 唤醒词录制 ===")
    print(f"唤醒词: {wake_word}")
    print(f"录制次数: {count}")
    print(f"每次时长: {CLIP_DURATION}秒")
    print(f"采样率: {SAMPLE_RATE}Hz")
    print(f"保存目录: {save_dir}")
    if start_idx > 0:
        print(f"已有 {start_idx} 个录音，从第 {start_idx + 1} 个开始编号")
    print()

    input("按回车开始录制...")

    # 4. 逐条录制
    for i in range(count):
        idx = start_idx + i
        print(f"\n--- 第 {i + 1}/{count} 次 ---")

        # 倒计时
        for sec in range(RECORD_COUNTDOWN, 0, -1):
            print(f"  {sec}...")
            time.sleep(1)

        # 录制
        audio = record_clip(CLIP_DURATION, SAMPLE_RATE)

        # 保存
        filepath = save_dir / f"{wake_word}_{idx:03d}.wav"
        sf.write(str(filepath), audio, SAMPLE_RATE)

        # 音量诊断
        rms = np.sqrt(np.mean(audio ** 2))
        peak = np.max(np.abs(audio))
        print(f"  [已保存] {filepath.name}  RMS={rms:.4f}  Peak={peak:.4f}")

        if rms < 0.01:
            print("  [警告] 音量过低，请靠近麦克风或提高音量后重录本条")

    # 5. 完成提示
    print(f"\n=== 录制完成 ===")
    print(f"共 {count} 个录音保存在: {save_dir}")
    print(f"下一步: python enroll.py --wake-word {wake_word}")


if __name__ == "__main__":
    main()
