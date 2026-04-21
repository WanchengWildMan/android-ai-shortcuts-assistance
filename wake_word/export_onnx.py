"""
导出模型为 ONNX 格式，供 Android 端部署
支持唤醒词编码器和说话人编码器的双模型导出
使用 ONNX Runtime Android 加载推理
"""
import argparse
import torch

from config import (
    N_MELS, EMBEDDING_DIM, SPEAKER_EMBEDDING_DIM,
    N_SAMPLES, HOP_LENGTH, MODEL_DIR,
)
from model import WakeWordEncoder, SpeakerEncoder


def _export_single(model, name, output_filename, embedding_dim):
    """导出单个编码器为 ONNX"""
    n_frames = N_SAMPLES // HOP_LENGTH  # 150
    dummy_input = torch.randn(1, 1, N_MELS, n_frames)

    output_path = MODEL_DIR / output_filename
    torch.onnx.export(
        model,
        dummy_input,
        str(output_path),
        input_names=["mel_spectrogram"],
        output_names=["embedding"],
        dynamic_axes={
            "mel_spectrogram": {0: "batch"},
            "embedding": {0: "batch"},
        },
        opset_version=13,
    )

    print(f"  {name}: {output_path}")
    print(f"    输入: [B, 1, {N_MELS}, {n_frames}]")
    print(f"    输出: [B, {embedding_dim}]")
    return output_path


def export_onnx(mode="both"):
    exported = []

    # 1. 导出唤醒词编码器
    if mode in ("keyword", "both"):
        model_path = MODEL_DIR / "encoder_best.pt"
        if not model_path.exists():
            model_path = MODEL_DIR / "encoder_final.pt"
        if not model_path.exists():
            print("警告: 找不到唤醒词编码器，跳过")
        else:
            model = WakeWordEncoder(n_mels=N_MELS, embedding_dim=EMBEDDING_DIM)
            model.load_state_dict(torch.load(str(model_path), map_location="cpu"))
            model.eval()
            print(f"唤醒词编码器已加载: {model_path.name} ({model.count_parameters():,} 参数)")
            path = _export_single(model, "唤醒词编码器", "wake_encoder.onnx", EMBEDDING_DIM)
            exported.append(path)

    # 2. 导出说话人编码器
    if mode in ("speaker", "both"):
        spk_path = MODEL_DIR / "speaker_encoder_best.pt"
        if not spk_path.exists():
            spk_path = MODEL_DIR / "speaker_encoder_final.pt"
        if not spk_path.exists():
            print("警告: 找不到说话人编码器，跳过")
        else:
            model = SpeakerEncoder(n_mels=N_MELS, embedding_dim=SPEAKER_EMBEDDING_DIM)
            model.load_state_dict(torch.load(str(spk_path), map_location="cpu"))
            model.eval()
            print(f"说话人编码器已加载: {spk_path.name} ({model.count_parameters():,} 参数)")
            path = _export_single(model, "说话人编码器", "speaker_encoder.onnx", SPEAKER_EMBEDDING_DIM)
            exported.append(path)

    if exported:
        print(f"\n=== ONNX 导出完成 ({len(exported)} 个模型) ===")
        print("Android 集成: 将 .onnx + 唤醒词.npy 放入 app/src/main/assets/")
    else:
        print("错误: 没有找到任何可导出的模型，请先运行 train.py")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="导出编码器为 ONNX 格式")
    parser.add_argument(
        "--mode", choices=["keyword", "speaker", "both"],
        default="both", help="导出模式: keyword=仅唤醒词, speaker=仅说话人, both=全部"
    )
    args = parser.parse_args()
    export_onnx(args.mode)
