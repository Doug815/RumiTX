#!/usr/bin/env python3
"""Receive and record DMR voice on a ThinkPad with RTL-SDR V4 + DSD-FME.

LISTEN-ONLY by default. Live TX requires both --send and --ack-authorized,
and still passes through TX_AUDIO's signed client and device allowlist.
Never opens, programs, or modifies the S80's radio transport itself.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

from analyze_dmr_voice import examine
from tx_audio_client import validate_mhz, sha_wav

REPO_TOOLS = Path(__file__).resolve().parent
# -7 MUST precede -P in DSD-FME, per upstream usage documentation.
DSD_REFERENCE = "https://github.com/lwvmobile/dsd-fme/blob/audio_work/examples/cygwin_bat/complete_usage_options.txt"


def build_decoder_command(program: str, freq: str, gain: int, ppm: int,
                          device: int, output: Path) -> list[str]:
    validate_mhz(freq)
    if not 0 <= gain <= 49 or not -100 <= ppm <= 100 or device < 0:
        raise ValueError("decoder device/gain/ppm outside supported range")
    audio = output / "decoded_wavs"
    events = output / "dmr_events.log"
    return [
        program, "-fs",                         # DMR BS/MS simplex decoding
        "-i", f"rtl:{device}:{freq}M:{gain}:{ppm}:12:0:2",
        "-o", "null",                           # audio saved to WAV, not speakers
        "-7", str(audio), "-P",                 # per-call decoded audio WAVs
        "-J", str(events),
    ]


def make_sender_command(wav: Path, freq: str, cc: int, tg: int,
                        key: Path) -> list[str]:
    validate_mhz(freq)
    if not 0 <= cc <= 15 or not 1 <= tg <= 16777215:
        raise ValueError("invalid DMR CC or TG")
    if not wav.is_file():
        raise ValueError("TX WAV file does not exist")
    # This validation is read-only and runs BEFORE launching the receiver.
    sha_wav(wav)
    if not key.is_file():
        raise ValueError("TX HMAC key file missing; run device provisioning first")
    return [
        sys.executable, str(REPO_TOOLS / "tx_audio_client.py"),
        "--wav", str(wav), "--tx", freq, "--cc", str(cc),
        "--tg", str(tg), "--key", str(key), "--adb"
    ]


def parser_for_tests() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--freq", required=True,
                   help="DMR RX frequency MHz, e.g. 446.00625")
    p.add_argument("--duration", type=int, default=40,
                   help="maximum receive duration, 8-180 seconds (default 40)")
    p.add_argument("--gain", type=int, default=8,
                   help="RTL tuner gain 0-49 (start LOW to avoid TX overload)")
    p.add_argument("--ppm", type=int, default=0, help="RTL tuner PPM calibration")
    p.add_argument("--device", type=int, default=0, help="RTL SDR device index")
    p.add_argument("--decoder", default="dsd-fme", help="DSD-FME executable")
    p.add_argument("--output", type=Path,
                   help="directory for decoded voice, logs and JSON report")
    p.add_argument("--reference", type=Path,
                   help="original TTS WAV, for ENVELOPE comparison only")
    p.add_argument("--send", action="store_true",
                   help="explicit live TX through existing signed TX_AUDIO client")
    p.add_argument("--ack-authorized", action="store_true",
                   help="operator confirms selected channel/mode/power authorization")
    p.add_argument("--wav", type=Path,
                   help="canonical 16kHz mono WAV to send with --send")
    p.add_argument("--cc", type=int, default=1)
    p.add_argument("--tg", type=int, default=99)
    p.add_argument("--key", type=Path, default=Path("tx_audio.key"))
    p.add_argument("--dry-run", action="store_true",
                   help="print planned commands without opening SDR or transmitting")
    return p


def stop_group(process: subprocess.Popen, grace: float = 5.0) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGINT)
        process.wait(timeout=grace)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=2)


def execute(args: argparse.Namespace) -> int:
    try:
        freq = validate_mhz(args.freq)
        if not 8 <= args.duration <= 180:
            raise ValueError("duration must be 8-180 seconds")
        if args.send and not args.ack_authorized:
            raise ValueError("live TX requires --ack-authorized")
        if args.send and args.wav is None:
            raise ValueError("live TX requires --wav")
        if args.send and args.duration < 35:
            raise ValueError("allow at least 35s receive window for bridged TX")
        if args.send:
            sender = make_sender_command(args.wav, freq, args.cc, args.tg, args.key)
        else:
            sender = None
        if args.reference and not args.reference.is_file():
            raise ValueError("reference WAV not found")
        # Don't fabricate a recording by reusing an old directory.
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        directory = args.output or (Path("dmr_voice_tests") / stamp)
        if directory.exists() and any(directory.iterdir()):
            raise ValueError("output directory must be new or empty")
        command = build_decoder_command(args.decoder, freq, args.gain,
                                        args.ppm, args.device, directory)
    except (OSError, ValueError) as error:
        print(f"Configuration error: {error}", file=sys.stderr)
        return 2

    planned = {
        "mode": "live_send_and_receive" if args.send else "receive_only",
        "frequency_mhz": freq, "capture_seconds": args.duration,
        "decoder_command": command,
        "sender_command": (["[signed TX_AUDIO request via ADB; key redacted]"]
                           if sender else None),
        "decoder_reference": DSD_REFERENCE,
        "note": "no radio transmission in default receive-only mode"
    }
    if args.dry_run:
        print(json.dumps(planned, indent=2))
        return 0

    if not shutil.which(args.decoder) and not Path(args.decoder).is_file():
        print("DSD-FME executable not found. Follow docs/DMR_RECEIVE_VOICE_VERIFICATION.md",
              file=sys.stderr)
        return 2
    directory.mkdir(parents=True, exist_ok=True)
    (directory/"decoded_wavs").mkdir(exist_ok=True)
    started = datetime.now(timezone.utc).isoformat()
    planned["start_utc"] = started
    (directory/"session.json").write_text(json.dumps(planned, indent=2)+"\n")
    log_file = directory / "dsd_decoder_console.log"
    send_result = None
    decoder_rc = None
    process = None
    print(f"Starting DSD-FME listen on {freq} MHz; output: {directory}", flush=True)
    print("Important: transmitter-to-SDR proximity can overload the tuner. Use low gain, safe separation/attenuation.", flush=True)
    try:
        with log_file.open("w", encoding="utf-8", errors="replace") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            start = time.monotonic()
            sender_launched = False
            while time.monotonic() - start < args.duration:
                if process.poll() is not None:
                    print("DSD-FME exited before capture time finished; review decoder console.",
                          file=sys.stderr)
                    break
                if sender is not None and not sender_launched and time.monotonic() - start >= 3.0:
                    sender_launched = True
                    print("Receiver armed. Sending one HMAC-protected TX_AUDIO request.", flush=True)
                    try:
                        sent = subprocess.run(sender, capture_output=True, text=True,
                                              timeout=35, check=False)
                        send_result = {
                            "exit_code": sent.returncode,
                            "stdout": sent.stdout[-3000:],
                            "stderr": sent.stderr[-3000:],
                            "note": "ADB broadcast completion is NOT decoded RF audio proof"
                        }
                    except (OSError, subprocess.TimeoutExpired) as error:
                        send_result = {"error": str(error)}
                    (directory/"send_result.json").write_text(
                        json.dumps(send_result, indent=2)+"\n")
                time.sleep(0.2)
    except (OSError, KeyboardInterrupt) as error:
        print(f"Receiver interrupted: {error}", file=sys.stderr)
    finally:
        if process is not None:
            stop_group(process)
            decoder_rc = process.returncode

    audio_paths = sorted((directory/"decoded_wavs").rglob("*.wav"))
    report = examine(audio_paths, args.reference or (args.wav if args.send else None))
    report.update({
        "frequency_mhz": freq,
        "rx_decoder": "DSD-FME",
        "decoder_exit_code": decoder_rc,
        "decoder_console_log": str(log_file),
        "event_log": str(directory/"dmr_events.log"),
        "sender": send_result,
        "note": "Assessment is receiver-side voice evidence, not a verification of direct digital TX injection."
    })
    (directory/"report.json").write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps({"report": str(directory/"report.json"),
                      "assessment": report["assessment"],
                      "saved_decoded_wavs": len(audio_paths),
                      "send_result": send_result}, indent=2))
    if report["assessment"] == "no_valid_decoded_wave":
        print("No decoded audio. Review tuning/RTL gain/DMR sync and receiver log.", file=sys.stderr)
        return 3
    return 0


def main(argv=None) -> int:
    p = parser_for_tests()
    args = p.parse_args(argv)
    return execute(args)


if __name__ == "__main__":
    raise SystemExit(main())
