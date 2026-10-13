#!/usr/bin/env python3
"""Analyze received DMR-decoded WAVs without mistaking RF carrier for speech.

This tool never accesses radio hardware. It reports decoded WAV presence,
energy and optional timing-envelope similarity to a reference TTS file.
It does NOT certify intelligible words: confirm by listening or an
independent ASR transcript of the received WAV.
"""
from __future__ import annotations
import argparse
from array import array
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys
import wave

WINDOW_SEC = 0.04
MAX_WAV_BYTES = 40_000_000


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for buf in iter(lambda: stream.read(65536), b""):
            sha.update(buf)
    return sha.hexdigest()


def envelope(path: Path) -> dict:
    """Return 40ms normalized frame RMS plus metadata; requires PCM16 WAV."""
    if path.stat().st_size > MAX_WAV_BYTES:
        raise ValueError("WAV too large for safe review")
    with wave.open(str(path), "rb") as audio:
        rate = audio.getframerate()
        channels = audio.getnchannels()
        width = audio.getsampwidth()
        nframes = audio.getnframes()
        if rate < 4000 or rate > 192000 or channels not in (1, 2) or width != 2:
            raise ValueError("expected 16-bit mono or stereo PCM WAV")
        if audio.getcomptype() != "NONE":
            raise ValueError("compressed WAV unsupported")
        frame_step = max(1, round(rate * WINDOW_SEC))
        level = []
        while True:
            block = audio.readframes(frame_step)
            if not block:
                break
            values = array("h")
            values.frombytes(block)
            if sys.byteorder == "big":
                values.byteswap()
            if not values:
                continue
            # A TDMA decoder can place slot A/B in separate stereo channels.
            # Preserve the louder channel instead of averaging a silent slot.
            if channels == 1:
                energy = sum(int(x) * int(x) for x in values) / len(values)
            else:
                channel_energies = []
                for c in range(channels):
                    samples = values[c::channels]
                    channel_energies.append(
                        sum(int(x) * int(x) for x in samples) / max(1, len(samples)))
                energy = max(channel_energies)
            level.append(math.sqrt(energy) / 32768.0)
    peak = max(level, default=0)
    mean_energy = sum(x*x for x in level)/max(len(level), 1)
    active = sum(x >= 0.01 for x in level)
    rms = math.sqrt(mean_energy)
    return {
        "path": str(path),
        "sha256": digest(path),
        "samplerate_hz": rate,
        "channels": channels,
        "sample_width_bytes": width,
        "duration_seconds": round(nframes / rate, 3),
        "peak_rms_dbfs": round(20 * math.log10(max(peak, 1e-9)), 2),
        "overall_rms_dbfs": round(20 * math.log10(max(rms, 1e-9)), 2),
        "active_fraction_over_minus40dbfs": round(active / max(len(level), 1), 3),
        "non_silent_candidate": active >= 8 and peak >= 0.02,
        "envelope": level,
    }


def centered_similarity(reference: list[float], received: list[float]) -> dict:
    """Envelope timing only, NOT a content/intelligibility proof."""
    if len(reference) < 20 or len(received) < 20:
        return {"max_envelope_correlation": None, "offset_seconds": None}
    # Allow start timing to differ by up to five seconds.
    max_shift = min(125, len(reference) + len(received))
    best = (-2.0, 0)
    for offset in range(-max_shift, max_shift + 1):
        ra = max(0, -offset)
        rb = max(0, offset)
        count = min(len(reference) - ra, len(received) - rb)
        if count < 20:
            continue
        a = reference[ra:ra+count]
        b = received[rb:rb+count]
        ma = sum(a)/count
        mb = sum(b)/count
        va = sum((x-ma)**2 for x in a)
        vb = sum((y-mb)**2 for y in b)
        if va < 1e-8 or vb < 1e-8:
            continue
        corr = sum((x-ma)*(y-mb) for x,y in zip(a,b)) / math.sqrt(va*vb)
        if corr > best[0]:
            best = (corr, offset)
    if best[0] < -1:
        return {"max_envelope_correlation": None, "offset_seconds": None}
    return {"max_envelope_correlation": round(best[0], 3),
            "offset_seconds": round(best[1]*WINDOW_SEC, 2),
            "interpretation": "Timing-envelope agreement only; listen to decoded audio to confirm words"}


def examine(recordings: list[Path], reference: Path | None = None) -> dict:
    ref = None
    ref_error = None
    if reference is not None:
        try:
            ref = envelope(reference)
        except (OSError, ValueError, EOFError, wave.Error) as error:
            ref_error = str(error)
    entries = []
    for wav_path in recordings:
        try:
            m = envelope(wav_path)
            if ref:
                m.update(centered_similarity(ref["envelope"], m["envelope"]))
            m.pop("envelope", None)
            entries.append(m)
        except (OSError, ValueError, EOFError, wave.Error) as error:
            entries.append({"path": str(wav_path), "error": str(error),
                            "non_silent_candidate": False})
    valid = sum(1 for r in entries if "error" not in r)
    non_silent = sum(1 for r in entries if r.get("non_silent_candidate"))
    # This is intentionally not named "transmission_verified".
    evidence = ("decoded_audio_candidate" if non_silent else
                "decoded_wave_silent" if valid else
                "no_valid_decoded_wave")
    return {
        "analyzed_utc": datetime.now(timezone.utc).isoformat(),
        "assessment": evidence,
        "claims": {
            "demodulated_voice_candidate": bool(non_silent),
            "intelligible_words_verified": False,
            "true_digital_audio_injection_verified": False,
            "note": "Carrier RSSI and playback completion are not proof of spoken message."
        },
        "reference_sha256": ref["sha256"] if ref else None,
        "reference_error": ref_error,
        "files": entries
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="folder containing decoder's received WAVs")
    parser.add_argument("--reference", type=Path, help="original transmitted TTS WAV")
    parser.add_argument("--output", type=Path, help="JSON report file")
    args = parser.parse_args(argv)
    if not args.directory.is_dir():
        parser.error("directory does not exist")
    wavs = sorted(args.directory.rglob("*.wav"))
    report = examine(wavs, args.reference)
    result = json.dumps(report, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result + "\n", encoding="utf-8")
    print(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
