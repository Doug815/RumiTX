#!/usr/bin/env python3
"""Receive analog FM with RTL-SDR V4 and save actual demodulated WAV.

Read-only SDR capture. NEVER sends a TX_AUDIO request or keys the S80.
Uses rtl_fm + Python's PCM16 WAV writer (no new Python dependencies).
The operator must send an authorized analog FM message separately.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import wave

from analyze_analog_fm_whine import analyze
from tx_audio_client import validate_mhz

RATE = 24000


def receiver_command(freq: str, gain: float | None, ppm: int, device: int):
    rf_mhz = validate_mhz(freq)
    if not -100 <= ppm <= 100 or device < 0:
        raise ValueError("unsupported SDR correction or device")
    if gain is not None and not 0 <= gain <= 49:
        raise ValueError("gain must be 0-49 dB")
    # rtl_fm -f is always the real channel; it handles tuner internals.
    command = [
        "rtl_fm", "-d", str(device),
        "-f", str(round(float(rf_mhz) * 1000000)),
        "-M", "fm", "-s", str(RATE), "-r", str(RATE),
        "-l", "0", "-p", str(ppm), "-E", "deemp"
    ]
    if gain is not None:
        command += ["-g", str(gain)]
    command += ["-"]
    return command


def terminate(process: subprocess.Popen) -> int:
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGINT)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=3)
    return process.returncode if process.returncode is not None else -1


def pcm_to_wav(raw_file: Path, wav_file: Path) -> int:
    total = raw_file.stat().st_size
    if total < RATE * 2 or total % 2 != 0:
        raise ValueError("Too few samples or non-PCM16 output; see rtl_fm.log")
    with raw_file.open("rb") as stream, wave.open(str(wav_file), "wb") as dest:
        dest.setnchannels(1)
        dest.setsampwidth(2)
        dest.setframerate(RATE)
        while True:
            chunk = stream.read(65536)
            if not chunk:
                break
            dest.writeframesraw(chunk)
    return total // 2


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--freq", required=True, help="actual received analog RF channel in MHz")
    p.add_argument("--duration", type=int, default=25)
    p.add_argument("--gain", type=float, help="manual RTL gain dB; omit for automatic")
    p.add_argument("--ppm", type=int, default=0)
    p.add_argument("--device", type=int, default=0)
    p.add_argument("--output", type=Path,
                   help="new/empty output directory; defaults to timestamped session")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args(argv)
    try:
        command = receiver_command(args.freq, args.gain, args.ppm, args.device)
        if not 5 <= args.duration <= 120:
            raise ValueError("duration must be 5-120 seconds")
        out = args.output or Path("analog_fm_rx_tests") / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        if out.exists() and any(out.iterdir()):
            raise ValueError("output directory must be new or empty")
    except (ValueError, OSError) as error:
        p.error(str(error))
    info = {
        "mode": "receive_only",
        "rf_target_mhz": args.freq,
        "rtl_fm_command": command,
        "sample_rate_hz": RATE,
        "duration_seconds": args.duration,
        "NOTE": ("Receiver only. No transmit. FM WAV is actual demodulated "
                 "audio; compare SILENCE test with analog gain at 0% vs 40%. "
                 "Never assume gain or whine without the received audio.")
    }
    if args.dry_run:
        print(json.dumps(info, indent=2))
        return 0
    if shutil.which("rtl_fm") is None:
        p.error("rtl_fm not installed; install rtl-sdr package or use existing SDR++ FM WAV recordings")
    out.mkdir(parents=True, exist_ok=True)
    log = out / "rtl_fm.log"
    raw = out / "capture.pcm"
    wav = out / "received_analog_fm.wav"
    rc = -1
    with log.open("w") as errors, raw.open("wb") as samples:
        try:
            proc = subprocess.Popen(command, stdout=samples, stderr=errors,
                                    start_new_session=True)
        except OSError as error:
            p.error(f"cannot start rtl_fm: {error}")
        print(f"Receiving at {args.freq} MHz for {args.duration}s. No TX initiated.", flush=True)
        try:
            start = time.monotonic()
            while time.monotonic() - start < args.duration:
                if proc.poll() is not None:
                    break
                time.sleep(0.2)
        except KeyboardInterrupt:
            print("Interrupted: stopping RTL capture.", file=sys.stderr)
        finally:
            rc = terminate(proc)
    info["rtl_fm_exit_status"] = rc
    info["capture_utc"] = datetime.now(timezone.utc).isoformat()
    try:
        count = pcm_to_wav(raw, wav)
        raw.unlink()
        info["received_wav"] = str(wav)
        info["samples"] = count
        info["wav_sha256"] = hashlib.sha256(wav.read_bytes()).hexdigest()
        info["spectrum"] = analyze(wav, 0, min(8, count/RATE))
    except (OSError, ValueError, wave.Error) as error:
        info["error"] = str(error)
        print(f"FM receive failed: {error}", file=sys.stderr)
    (out / "report.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(out/"report.json"),
                      "wav": info.get("received_wav"),
                      "assessment": info.get("spectrum", {}).get("assessment"),
                      "error": info.get("error")}, indent=2))
    return 0 if "received_wav" in info else 3


if __name__ == "__main__":
    raise SystemExit(main())
