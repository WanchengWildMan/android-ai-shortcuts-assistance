import os
import torch
import numpy as np
import sounddevice as sd
import time
import argparse
from collections import deque
import scipy.io.wavfile as wavfile

import config
from model import WakeWordEncoder
from enroll import audio_to_mel
from augment import pad_or_trim

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wake-word", type=str, required=True)
    args = parser.parse_args()

    template_path = os.path.join(config.TEMPLATE_DIR, f"{args.wake_word}.npy")
    data = np.load(template_path, allow_pickle=True).item()
    kw_proto = torch.tensor(data['keyword_prototype'], dtype=torch.float32)

    encoder = WakeWordEncoder().eval()
    model_path = os.path.join(config.MODEL_DIR, "encoder_best.pt")
    encoder.load_state_dict(torch.load(model_path, map_location='cpu', weights_only=True))

    print("\n[DEBUG] 开始录制滑动窗口...")
    print("请像平时一样喊「小万」...")
    
    window_frames = config.N_SAMPLES
    audio_buffer = deque(maxlen=window_frames)
    
    last_detect = 0
    save_idx = 0
    
    chunk_size = int(config.SAMPLE_RATE * 0.1)

    def audio_callback(indata, frames, time_info, status):
        nonlocal last_detect, save_idx

        audio = indata[:, 0].astype(np.float32)
        audio_buffer.extend(audio)
            
        now = time.time()
        if len(audio_buffer) == window_frames and (now - last_detect >= config.STEP_SECONDS):
            recent = np.array(list(audio_buffer))[-int(0.5 * config.SAMPLE_RATE):]
            rms = np.sqrt(np.mean(recent**2))
            
            if rms >= config.VAD_RMS_THRESHOLD:
                audio_raw = np.array(list(audio_buffer), dtype=np.float32)
                audio_padded = pad_or_trim(audio_raw, config.N_SAMPLES)
                
                with torch.no_grad():
                    mel = audio_to_mel(audio_padded).unsqueeze(0)
                    emb = encoder(mel).squeeze(0)
                    sim = torch.dot(emb, kw_proto).item()
                
                print(f" => RMS={rms:.4f}, 与模板原始相似度={sim:.4f}")
                
                if sim > 0.4:
                    wavfile.write(os.path.join(config.BASE_DIR, f"debug_live_{save_idx}.wav"), config.SAMPLE_RATE, (audio_padded * 32767).astype(np.int16))
                    print(f"    [!] 成功录入1帧 (sim={sim:.4f})，已存至 debug_live_{save_idx}.wav")
                    save_idx += 1
                    last_detect = now + 1.0 
                    audio_buffer.clear()
            else:
                last_detect = now

    try:
        with sd.InputStream(
            samplerate=config.SAMPLE_RATE,
            channels=1,
            blocksize=chunk_size,
            callback=audio_callback,
        ):
            while save_idx < 5:
                time.sleep(0.1)
            print("\n[已拍到5次波形] 脚本正常结束。")
    except KeyboardInterrupt:
        pass

if __name__ == "__main__":
    main()
