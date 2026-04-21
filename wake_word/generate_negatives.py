"""
负样本合成脚本 - 使用 edge-tts 多音色合成 + 噪声生成
输出结构:
  data/negative/
    你好/            ← 每个短语一个子目录
      Xiaoxiao.wav
      Xiaoxiao_aug0.wav
      ...
    _noise_white/    ← 噪声类
      000.wav
    _noise_pink/
    _noise_brown/
    _silence/
"""
import asyncio
import os
import random
import subprocess
import sys
import tempfile

import numpy as np
import soundfile as sf

# 添加 edge-tts 源码路径
from config import EDGE_TTS_DIR
sys.path.insert(0, str(EDGE_TTS_DIR))
import edge_tts

from config import (
    SAMPLE_RATE, N_SAMPLES, NEGATIVE_DIR,
    TTS_VOICES, NEGATIVE_PHRASES,
    TTS_AUGMENT_COUNT, NOISE_SAMPLE_COUNT,
    TTS_VOLUME_LEVELS, TTS_POSITIVE_DIR, TTS_POSITIVE_VOICES,
)
from augment import augment, pad_or_trim


# ============================================================
# 噪声生成
# ============================================================

def generate_noise_samples():
    """生成各种噪声样本，每种噪声作为独立类别"""
    noise_types = {
        "_noise_white": lambda n: np.random.randn(n).astype(np.float32) * 0.1,
        "_noise_brown": lambda n: _brown_noise(n),
        "_silence": lambda n: np.random.randn(n).astype(np.float32) * 0.001,
    }

    # 粉红噪声需要 scipy，单独处理
    try:
        from scipy.signal import lfilter
        noise_types["_noise_pink"] = lambda n: _pink_noise(n)
    except ImportError:
        print("  [跳过] 粉红噪声 (需要 scipy)")

    for noise_name, gen_func in noise_types.items():
        noise_dir = NEGATIVE_DIR / noise_name
        noise_dir.mkdir(parents=True, exist_ok=True)

        for i in range(NOISE_SAMPLE_COUNT):
            audio = gen_func(N_SAMPLES)
            filepath = noise_dir / f"{i:03d}.wav"
            sf.write(str(filepath), audio, SAMPLE_RATE)

        print(f"  {noise_name}: {NOISE_SAMPLE_COUNT} 个样本")


def _brown_noise(n):
    """布朗噪声 (随机游走)"""
    audio = np.cumsum(np.random.randn(n) * 0.01).astype(np.float32)
    max_val = np.max(np.abs(audio))
    if max_val > 0:
        audio = audio / max_val * 0.1
    return audio


def _pink_noise(n):
    """粉红噪声 (1/f 频谱，通过滤波实现)"""
    from scipy.signal import lfilter
    white = np.random.randn(n)
    # Voss-McCartney 近似 IIR 滤波器系数
    b = [0.049922035, -0.095993537, 0.050612699, -0.004709510]
    a = [1.0, -2.494956002, 2.017265875, -0.522189400]
    audio = lfilter(b, a, white).astype(np.float32)
    max_val = np.max(np.abs(audio))
    if max_val > 0:
        audio = audio / max_val * 0.1
    return audio


# ============================================================
# TTS 合成
# ============================================================

def convert_mp3_to_wav(mp3_path, wav_path):
    """MP3 → 16kHz 单声道 WAV (依赖 ffmpeg)"""
    try:
        subprocess.run(
            [
                "ffmpeg", "-y", "-i", str(mp3_path),
                "-ar", str(SAMPLE_RATE), "-ac", "1",
                "-f", "wav", str(wav_path),
            ],
            capture_output=True,
            check=True,
        )
        return True
    except FileNotFoundError:
        print("  [错误] 未找到 ffmpeg，请先安装: brew install ffmpeg")
        sys.exit(1)
    except subprocess.CalledProcessError as e:
        print(f"  [错误] ffmpeg 转换失败: {e.stderr.decode()[:200]}")
        return False


async def synthesize_one(text, voice, output_path, volume="+0%"):
    """
    使用 edge-tts 合成一段语音，带指数退避重试

    重试策略: 最多 3 次，间隔 2s/4s/8s，覆盖网络抖动和限流
    """
    max_retries = 3
    for attempt in range(max_retries):
        try:
            communicate = edge_tts.Communicate(text, voice, volume=volume)
            await communicate.save(str(output_path))
            return
        except Exception as e:
            if attempt < max_retries - 1:
                wait = 2 ** (attempt + 1)  # 2s, 4s, 8s
                await asyncio.sleep(wait)
            else:
                raise e


async def generate_tts_negatives():
    """
    合成 TTS 负样本: 多短语 x 多音色 x 多音量级别 x 多增强

    优化:
    1. 断点续传 — 已有文件自动跳过
    2. 并发合成 — asyncio.Semaphore 控制并发数，10 路同时请求
    3. 增强分离 — 先合成原始 WAV，再批量生成增强变体
    """
    # 1. 建立任务列表，跳过已存在的文件
    tts_tasks = []          # 需要 TTS 合成的任务
    aug_tasks = []          # 需要生成增强版本的任务
    skipped_tts = 0
    skipped_aug = 0

    for phrase in NEGATIVE_PHRASES:
        phrase_dir = NEGATIVE_DIR / phrase
        phrase_dir.mkdir(parents=True, exist_ok=True)

        for voice in TTS_VOICES:
            voice_tag = voice.split("-")[-1].replace("Neural", "")

            for vol_level in TTS_VOLUME_LEVELS:
                vol_tag = vol_level.replace("+", "").replace("%", "")
                wav_path = phrase_dir / f"{voice_tag}_v{vol_tag}.wav"

                # 原始 WAV 不存在 → 需要 TTS 合成
                if not wav_path.exists():
                    tts_tasks.append((phrase, voice, voice_tag, vol_level, vol_tag, phrase_dir, wav_path))
                else:
                    skipped_tts += 1

                # 增强变体不存在 → 需要生成
                for aug_i in range(TTS_AUGMENT_COUNT):
                    aug_path = phrase_dir / f"{voice_tag}_v{vol_tag}_aug{aug_i}.wav"
                    if not aug_path.exists():
                        aug_tasks.append((wav_path, aug_path))
                    else:
                        skipped_aug += 1

    total_tts = len(tts_tasks)
    total_aug = len(aug_tasks)
    print(f"  TTS 合成: {total_tts} 条待合成, {skipped_tts} 条已存在(跳过)")
    print(f"  增强变体: {total_aug} 条待生成, {skipped_aug} 条已存在(跳过)")

    if total_tts == 0 and total_aug == 0:
        print("  全部已完成，无需重复合成")
        return

    # 2. 并发 TTS 合成 (10 路并发，网络 I/O 密集型)
    if total_tts > 0:
        sem = asyncio.Semaphore(10)
        completed = [0]
        failed = [0]

        async def process_one_tts(task):
            phrase, voice, voice_tag, vol_level, vol_tag, phrase_dir, wav_path = task
            async with sem:
                with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as tmp:
                    tmp_mp3 = tmp.name
                try:
                    # TTS 合成 → MP3
                    await synthesize_one(phrase, voice, tmp_mp3, volume=vol_level)

                    # MP3 → WAV
                    if not convert_mp3_to_wav(tmp_mp3, wav_path):
                        failed[0] += 1
                        return

                    # 统一长度
                    audio, sr = sf.read(str(wav_path))
                    audio = audio.astype(np.float32)
                    if audio.ndim > 1:
                        audio = audio.mean(axis=1)
                    audio = pad_or_trim(audio, N_SAMPLES)
                    sf.write(str(wav_path), audio, SAMPLE_RATE)

                    completed[0] += 1
                    if completed[0] % 50 == 0 or completed[0] == total_tts:
                        print(f"  [TTS {completed[0]}/{total_tts}] {completed[0]/total_tts:.0%}")

                except Exception as e:
                    failed[0] += 1
                    if failed[0] <= 5:
                        print(f"  [跳过] {phrase}/{voice_tag}/v{vol_tag}: {e}")
                finally:
                    if os.path.exists(tmp_mp3):
                        os.unlink(tmp_mp3)

        print(f"\n  开始并发 TTS 合成 (10 路并发)...")
        await asyncio.gather(*[process_one_tts(t) for t in tts_tasks])
        print(f"  TTS 完成: {completed[0]} 成功, {failed[0]} 失败")

    # 3. 批量生成增强变体 (CPU 密集，顺序处理但很快)
    if total_aug > 0:
        print(f"\n  生成增强变体...")
        aug_completed = 0
        aug_skipped = 0

        for wav_path, aug_path in aug_tasks:
            if not wav_path.exists():
                aug_skipped += 1
                continue

            try:
                audio, sr = sf.read(str(wav_path))
                audio = audio.astype(np.float32)
                if audio.ndim > 1:
                    audio = audio.mean(axis=1)
                aug_audio = augment(audio)
                sf.write(str(aug_path), aug_audio, SAMPLE_RATE)
                aug_completed += 1
            except Exception:
                aug_skipped += 1

            if aug_completed % 200 == 0 and aug_completed > 0:
                print(f"  [增强 {aug_completed}/{total_aug}] {aug_completed/total_aug:.0%}")

        print(f"  增强完成: {aug_completed} 成功, {aug_skipped} 跳过")


# ============================================================
# 主函数
# ============================================================

async def generate_tts_positive(wake_word):
    """
    用 TTS 合成唤醒词正样本 — 仅训练用，不参与注册

    普通话 6 音色 × 3 音量 = 18 条原始 + 增强变体
    输出到 data/tts_positive/{wake_word}/ 目录
    """
    out_dir = TTS_POSITIVE_DIR / wake_word
    out_dir.mkdir(parents=True, exist_ok=True)

    voices = TTS_POSITIVE_VOICES
    volumes = TTS_VOLUME_LEVELS
    aug_count = TTS_AUGMENT_COUNT

    # 1. 合成原始 WAV
    sem = asyncio.Semaphore(6)
    completed = [0]
    tasks = []

    for voice in voices:
        voice_tag = voice.split("-")[-1].replace("Neural", "")
        for vol_level in volumes:
            vol_tag = vol_level.replace("+", "").replace("%", "")
            wav_path = out_dir / f"{voice_tag}_v{vol_tag}.wav"

            if wav_path.exists():
                completed[0] += 1
                continue

            tasks.append((wake_word, voice, voice_tag, vol_level, vol_tag, wav_path))

    total = len(tasks) + completed[0]
    print(f"  TTS正样本: {len(tasks)} 条待合成, {completed[0]} 条已存在(跳过)")

    async def process_one(task):
        text, voice, voice_tag, vol_level, vol_tag, wav_path = task
        async with sem:
            with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as tmp:
                tmp_mp3 = tmp.name
            try:
                await synthesize_one(text, voice, tmp_mp3, volume=vol_level)
                if not convert_mp3_to_wav(tmp_mp3, wav_path):
                    return
                # 统一长度
                audio, sr = sf.read(str(wav_path))
                audio = audio.astype(np.float32)
                if audio.ndim > 1:
                    audio = audio.mean(axis=1)
                audio = pad_or_trim(audio, N_SAMPLES)
                sf.write(str(wav_path), audio, SAMPLE_RATE)
                completed[0] += 1
                print(f"  [TTS正样本 {completed[0]}/{total}] {voice_tag}_v{vol_tag}")
            except Exception as e:
                print(f"  [跳过] {voice_tag}_v{vol_tag}: {e}")
            finally:
                if os.path.exists(tmp_mp3):
                    os.unlink(tmp_mp3)

    if tasks:
        await asyncio.gather(*[process_one(t) for t in tasks])

    # 2. 生成增强变体
    aug_done = 0
    for voice in voices:
        voice_tag = voice.split("-")[-1].replace("Neural", "")
        for vol_level in volumes:
            vol_tag = vol_level.replace("+", "").replace("%", "")
            wav_path = out_dir / f"{voice_tag}_v{vol_tag}.wav"
            if not wav_path.exists():
                continue
            for aug_i in range(aug_count):
                aug_path = out_dir / f"{voice_tag}_v{vol_tag}_aug{aug_i}.wav"
                if aug_path.exists():
                    continue
                try:
                    audio, sr = sf.read(str(wav_path))
                    audio = audio.astype(np.float32)
                    if audio.ndim > 1:
                        audio = audio.mean(axis=1)
                    aug_audio = augment(audio)
                    sf.write(str(aug_path), aug_audio, SAMPLE_RATE)
                    aug_done += 1
                except Exception:
                    pass

    n_files = sum(1 for _ in out_dir.glob("*.wav"))
    print(f"  TTS正样本完成: {n_files} 个文件 (含 {aug_done} 个增强变体)")


def main():
    import argparse
    parser = argparse.ArgumentParser(description="合成负样本 / TTS正样本")
    parser.add_argument("--positive", type=str, default=None,
                        help="合成唤醒词的TTS正样本 (如: --positive 小万)")
    args = parser.parse_args()

    if args.positive:
        # 仅合成指定唤醒词的 TTS 正样本
        print(f"=== 合成TTS正样本: {args.positive} ===\n")
        print(f"音色: {len(TTS_POSITIVE_VOICES)} 个普通话音色")
        print(f"音量: {TTS_VOLUME_LEVELS}")
        asyncio.run(generate_tts_positive(args.positive))
        print(f"\n输出目录: {TTS_POSITIVE_DIR / args.positive}")
        return

    NEGATIVE_DIR.mkdir(parents=True, exist_ok=True)

    print("=== 负样本合成 ===\n")

    print("[1/2] 生成噪声样本...")
    generate_noise_samples()

    print(f"\n[2/2] 合成 TTS 负样本 ({len(NEGATIVE_PHRASES)} 短语 x {len(TTS_VOICES)} 音色)...")
    asyncio.run(generate_tts_negatives())

    # 统计
    total_dirs = sum(1 for d in NEGATIVE_DIR.iterdir() if d.is_dir())
    total_files = sum(1 for _ in NEGATIVE_DIR.rglob("*.wav"))
    print(f"\n=== 完成 ===")
    print(f"类别数: {total_dirs}")
    print(f"总样本数: {total_files}")
    print(f"输出目录: {NEGATIVE_DIR}")
    print(f"\n下一步: python train.py")


if __name__ == "__main__":
    main()
