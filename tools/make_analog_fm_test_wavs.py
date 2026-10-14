#!/usr/bin/env python3
"""Generate safe no-RF analog FM receive-test messages for RumiTX TX_AUDIO.

Creates canonical 44-byte, 16 kHz mono PCM16 WAVs:
  silence.wav  -- Keyed FM mic path with silent Android playback.
  tone_1khz.wav -- Predictable low-level 1 kHz tone (-22 dBFS).

These are diagnostic sources ONLY: transmitting still requires an
operator-authorized channel and the existing signed TX_AUDIO client.
"""
import argparse
import hashlib
import math
from pathlib import Path
import struct
import wave


def write_pcm(path: Path, pcm: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(16000)
        f.writeframes(pcm)
    data = path.read_bytes()
    if (len(data) != 44 + len(pcm) or data[:4] != b"RIFF"
            or data[12:16] != b"fmt " or data[36:40] != b"data"):
        raise RuntimeError("not a canonical TX_AUDIO WAV")
    return hashlib.sha256(data).hexdigest()


def generate(out: Path, duration: float = 4.0):
    if not 1 <= duration <= 12:
        raise ValueError("duration must be 1-12 seconds")
    n = round(duration * 16000)
    silent_sha = write_pcm(out / "silence.wav", bytes(2 * n))
    amplitude = 32767 * 10 ** (-22 / 20)
    samples = bytearray(2 * n)
    fade = 320
    for i in range(n):
        env = min(1.0, i / fade, (n - i - 1) / fade)
        sample = round(amplitude * env * math.sin(2 * math.pi * 1000 * i / 16000))
        struct.pack_into("<h", samples, 2*i, sample)
    tone_sha = write_pcm(out / "tone_1khz.wav", bytes(samples))
    return {"silence": {"path": str(out/"silence.wav"), "sha256": silent_sha},
            "tone": {"path": str(out/"tone_1khz.wav"), "sha256": tone_sha}}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output", type=Path, default=Path("analog_fm_diag_sources"))
    p.add_argument("--duration", type=float, default=4.0)
    args = p.parse_args(argv)
    result = generate(args.output, args.duration)
    for item in result.values():
        print(item["path"], "sha256=", item["sha256"])
    print("No RF sent. Use operator-approved signed TX_AUDIO separately.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
