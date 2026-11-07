import io
import math
import struct
import wave
import numpy as np

try:
    import winsound
    HAS_WINSOUND = True
except ImportError:
    HAS_WINSOUND = False


def _build_wav(samples: np.ndarray, sample_rate=44100) -> bytes:
    samples = np.clip(samples, -1.0, 1.0)
    int_samples = (samples * 32767).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(int_samples.tobytes())
    return buf.getvalue()


def _gen_fireworks() -> bytes:
    sr = 44100
    t1 = np.linspace(0, 0.4, int(sr * 0.4), endpoint=False)
    freq = np.linspace(400, 1200, len(t1))
    whistle = 0.3 * np.sin(2 * np.pi * freq * t1) * np.linspace(0.1, 1.0, len(t1))
    t2 = np.linspace(0, 0.8, int(sr * 0.8), endpoint=False)
    noise = np.random.uniform(-1, 1, len(t2))
    decay = np.exp(-t2 * 6.0)
    low_sine = 0.7 * np.sin(2 * np.pi * 65 * t2) * decay
    boom = 0.5 * noise * decay + low_sine
    return _build_wav(np.concatenate([whistle, boom]), sr)


def _gen_balloons() -> bytes:
    sr = 44100
    notes = [523.25, 659.25, 783.99, 1046.50]
    chunks = []
    for freq in notes:
        t = np.linspace(0, 0.09, int(sr * 0.09), endpoint=False)
        chunks.append(0.4 * np.sin(2 * np.pi * freq * t) * np.exp(-t * 20.0))
    return _build_wav(np.concatenate(chunks), sr)


def _gen_confetti() -> bytes:
    sr = 44100
    t = np.linspace(0, 0.3, int(sr * 0.3), endpoint=False)
    noise = np.random.uniform(-1, 1, len(t)) * np.exp(-t * 30.0)
    brass = 0.5 * np.sin(2 * np.pi * np.linspace(440, 880, len(t)) * t) * np.exp(-t * 8.0)
    return _build_wav(0.6 * noise + brass, sr)


def _gen_hearts() -> bytes:
    sr = 44100
    notes = [739.99, 932.33, 1108.73, 1396.91]
    t = np.linspace(0, 0.4, int(sr * 0.4), endpoint=False)
    combined = np.zeros_like(t)
    for i, freq in enumerate(notes):
        delay = int(i * 0.06 * sr)
        sub_t = t[:len(t) - delay]
        combined[delay:] += 0.25 * np.sin(2 * np.pi * freq * sub_t) * np.exp(-sub_t * 6.0)
    return _build_wav(combined, sr)


def _gen_rain() -> bytes:
    sr = 44100
    t = np.linspace(0, 0.9, int(sr * 0.9), endpoint=False)
    rumble = 0.4 * np.sin(2 * np.pi * 55 * t) * (1.0 + 0.3 * np.sin(2 * np.pi * 4 * t)) * np.exp(-t * 2.0)
    hiss = 0.15 * np.random.uniform(-1, 1, len(t)) * np.exp(-t * 1.5)
    return _build_wav(rumble + hiss, sr)


SOUND_CACHE = {}


def play_sound(name: str):
    if not HAS_WINSOUND:
        return
    if name not in SOUND_CACHE:
        table = {
            "fireworks": _gen_fireworks,
            "balloons": _gen_balloons,
            "confetti": _gen_confetti,
            "hearts": _gen_hearts,
            "rain": _gen_rain,
        }
        if name in table:
            SOUND_CACHE[name] = table[name]()
    wav_bytes = SOUND_CACHE.get(name)
    if wav_bytes:
        winsound.PlaySound(wav_bytes, winsound.SND_MEMORY | winsound.SND_ASYNC)
