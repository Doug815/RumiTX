#!/usr/bin/env python3
"""Analyze a receiver-decoded analog FM WAV for persistent tonal interference.

This is RX audio analysis, NOT transmitter RF/modulation verification.
A strong/stationary tone in a received *silence TX* WAV supports real
audio-path interference. Run the same capture with FM volume 0% and 40%.
No external Python dependencies; no radio transmission initiated.
"""
from __future__ import annotations

import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys
import wave

FREQ_LOW = 150
FREQ_HIGH = 3400
FREQ_STEP = 20
WINDOW_SEC = 0.38
MAX_WAV_SIZE = 60_000_000


def load_wav(path: Path, start: float = 0, duration: float = 8.0):
    if path.stat().st_size > MAX_WAV_SIZE:
        raise ValueError("WAV too large")
    if start < 0 or duration <= 0 or duration > 120:
        raise ValueError("invalid start or duration")
    with wave.open(str(path), "rb") as audio:
        rate, nch, width = (audio.getframerate(), audio.getnchannels(),
                            audio.getsampwidth())
        if not (8000 <= rate <= 192000) or nch not in (1, 2) or width != 2:
            raise ValueError("expected PCM16 mono/stereo 8-192kHz WAV")
        if audio.getcomptype() != "NONE":
            raise ValueError("compressed WAV unsupported")
        audio.setpos(min(audio.getnframes(), round(start * rate)))
        raw = audio.readframes(round(duration * rate))
    samples = array("h")
    samples.frombytes(raw)
    if sys.byteorder == "big":
        samples.byteswap()
    # Select channel 1; the RTL FM capture tool uses mono.
    samples = samples[::nch]
    decimation = max(1, rate // 8000)
    return [float(value) / 32768 for value in samples[::decimation]], rate / decimation


def goertzel(samples, sample_rate: float, freq_hz: int) -> float:
    if not samples:
        return 0.0
    omega = 2 * math.pi * freq_hz / sample_rate
    coefficient = 2 * math.cos(omega)
    before, older = 0.0, 0.0
    for value in samples:
        newest = value + coefficient * before - older
        older, before = before, newest
    power = older * older + before * before - coefficient * older * before
    return max(power, 0.0)


def strongest_tone(samples, sample_rate: float) -> dict:
    if len(samples) < 500:
        return {"valid": False, "reason": "not enough samples"}
    dc = sum(samples) / len(samples)
    normalized = [(sample - dc) *
                  (0.5 - 0.5 * math.cos(2 * math.pi * i / (len(samples)-1)))
                  for i, sample in enumerate(samples)]
    powers = [(frequency, goertzel(normalized, sample_rate, frequency))
              for frequency in range(FREQ_LOW, min(FREQ_HIGH, round(sample_rate * 0.43))+1,
                                     FREQ_STEP)]
    if not powers:
        return {"valid": False, "reason": "sampling rate too low"}
    coarse, top = max(powers, key=lambda item: item[1])
    median = statistics.median(p for _, p in powers)
    # Fine tune the dominant tone; consider ±20 Hz around coarse peak.
    refined = [(f, goertzel(normalized, sample_rate, f))
               for f in range(max(FREQ_LOW, coarse-FREQ_STEP),
                              min(FREQ_HIGH, coarse+FREQ_STEP)+1, 2)]
    peak_hz, peak_power = max(refined, key=lambda item: item[1])
    peak_db = 10 * math.log10(max(peak_power, 1e-20)/max(median, 1e-20))
    rms = math.sqrt(sum(x*x for x in samples) / len(samples))
    return {
        "valid": True, "peak_hz": peak_hz, "peak_over_median_spectrum_db": round(peak_db, 1),
        "rms_dbfs": round(20 * math.log10(max(rms, 1e-9)), 1)
    }


def analyze(path: Path, start=0.0, duration=8.0) -> dict:
    samples, rate = load_wav(path, start, duration)
    window_len = round(rate * WINDOW_SEC)
    windows = [samples[i:i+window_len]
               for i in range(0, len(samples)-window_len+1, window_len)]
    scans = [strongest_tone(chunk, rate) for chunk in windows[:15]]
    valid = [x for x in scans if x.get("valid") and x["rms_dbfs"] > -65]
    if not valid:
        assessment = "no_usable_signal"
        median_peak = None
        median_db = None
        stable = 0.0
    else:
        # Only high-contrast candidates; avoid treating general hiss as whine.
        tonal = [x for x in valid if x["peak_over_median_spectrum_db"] >= 18]
        median_peak = round(statistics.median(x["peak_hz"] for x in tonal)) if tonal else None
        median_db = (round(statistics.median(x["peak_over_median_spectrum_db"]
                                            for x in tonal), 1) if tonal else None)
        stable = (sum(abs(x["peak_hz"] - median_peak) <= 30 for x in tonal)
                  / len(valid)) if tonal else 0
        assessment = ("persistent_tone_candidate" if stable >= 0.6 and len(tonal) >= 2
                      else "signal_without_stable_whine_candidate")
    data = path.read_bytes()
    return {
        "source_file": str(path),
        "sha256": hashlib.sha256(data).hexdigest(),
        "analyzed_span_start_s": start,
        "analyzed_span_duration_s": duration,
        "receiver_sample_rate_after_decimation": round(rate, 2),
        "valid_signal_windows": len(valid),
        "candidate_whine_hz": median_peak,
        "candidate_peak_above_background_db": median_db,
        "stable_tone_window_fraction": round(stable, 3),
        "assessment": assessment,
        "limitations": ("A spectral peak is not proof of feedback. Test the identical "
                        "silence WAV at 0% and 40% phone output, and listen to both "
                        "received analog FM WAVs. DMR quality is a separate comparator.")
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("received_wav", type=Path, help="receiver-decoded analog FM WAV (not original TTS)")
    p.add_argument("--start", type=float, default=0)
    p.add_argument("--duration", type=float, default=8)
    p.add_argument("--output", type=Path, help="JSON report path")
    args = p.parse_args(argv)
    try:
        result = analyze(args.received_wav, args.start, args.duration)
    except (OSError, ValueError, wave.Error, EOFError) as error:
        p.error(str(error))
    doc = json.dumps(result, indent=2)
    print(doc)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(doc + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
