#!/usr/bin/env python3
"""Prepare voice-agent TTS for RumiTX TX_AUDIO (canonical RIFF PCM16 WAV).

This is a safe acoustic-path quality improvement; it does not claim or
attempt to perform S80 digital microphone injection.
"""
import argparse
import hashlib
import shutil
import subprocess
import sys
import wave
from pathlib import Path

SAMPLE_RATE = 16000
MAX_SAMPLES = 15 * SAMPLE_RATE
SPEECH_FILTER = (
    "highpass=f=160,"
    "lowpass=f=3150,"
    "acompressor=threshold=0.14:ratio=2.5:attack=12:release=140,"
    "alimiter=limit=0.82:attack=5:release=65:level=0"
)


def prepare(input_path: Path, output_path: Path) -> tuple[int, str]:
    if not input_path.is_file():
        raise ValueError("Input file does not exist")
    if input_path.resolve() == output_path.resolve():
        raise ValueError("Output must not overwrite the source TTS audio")
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required on the ThinkPad")

    command = [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
        "-i", str(input_path), "-vn", "-sn", "-dn", "-af", SPEECH_FILTER,
        "-ar", str(SAMPLE_RATE), "-ac", "1", "-f", "s16le", "pipe:1",
    ]
    process = subprocess.run(command, capture_output=True, timeout=60, check=False)
    if process.returncode != 0:
        message = process.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"ffmpeg audio preparation failed: {message}")
    pcm = process.stdout
    if not pcm or len(pcm) % 2:
        raise ValueError("No valid 16-bit PCM samples were produced")
    sample_count = len(pcm) // 2
    if sample_count > MAX_SAMPLES:
        raise ValueError("Message exceeds the 15-second TX_AUDIO limit; split it")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(output_path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(pcm)
    content = output_path.read_bytes()
    if len(content) != 44 + len(pcm) or content[:4] != b"RIFF" or content[8:12] != b"WAVE":
        output_path.unlink(missing_ok=True)
        raise RuntimeError("Non-canonical WAV header; refusing to transmit")
    return sample_count, hashlib.sha256(content).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Generated TTS input audio")
    parser.add_argument("output", type=Path, help="Canonical 16 kHz mono PCM WAV output")
    args = parser.parse_args()
    try:
        samples, digest = prepare(args.input, args.output)
    except (RuntimeError, ValueError, subprocess.TimeoutExpired, OSError) as error:
        print(f"TX speech prep failed: {error}", file=sys.stderr)
        return 1
    print(f"OK: {args.output} ({samples / SAMPLE_RATE:.3f}s, 16kHz mono PCM16)")
    print(f"SHA-256: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
