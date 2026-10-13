#!/usr/bin/env python3
"""RumiTX v2.3.1 TX_AUDIO signed client for the ThinkPad (Omarchy Linux).

Defaults to DRY RUN. --adb stages a short WAV on the rooted S80 and sends a
signed, replay-protected request. Never keys radio unless both client and the
operator-controlled device allowlist authorize a transmission.
"""
import argparse
import hashlib
import hmac
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import wave

ACTION = "com.rumitx.app.TX_AUDIO"
PREFIX = "/data/user/0/com.rumitx.app/files/voice_bridge"
FIELDS = ("audio", "tx", "rx", "digital", "cc", "tg", "issued", "nonce", "sha256")


def canonical(data):
    return "\n".join(["TX_AUDIO_V1"] + [data[k] for k in FIELDS]).encode("utf-8")


def validate_mhz(value):
    if not re.fullmatch(r"[0-9]{3}\.[0-9]{1,5}", value):
        raise ValueError("use a MHz decimal such as 446.00625")
    if not 400 <= float(value) <= 470:
        raise ValueError("frequency outside the module's 400-470 MHz hardware range")
    return value


def sha_wav(path):
    with wave.open(str(path), "rb") as audio:
        if (audio.getcomptype() != "NONE" or audio.getnchannels() != 1
                or audio.getframerate() != 16000 or audio.getsampwidth() != 2):
            raise ValueError("expected uncompressed 16 kHz mono 16-bit PCM WAV")
        if not 0 < audio.getnframes() <= 15 * 16000:
            raise ValueError("WAV must contain 0-15 s of audio")
    payload = path.read_bytes()
    if (len(payload) < 46 or payload[:4] != b"RIFF"
            or payload[12:16] != b"fmt " or payload[36:40] != b"data"
            or len(payload) != 44 + int.from_bytes(payload[40:44], "little")):
        raise ValueError("WAV must use a canonical 44-byte RIFF PCM header")
    return hashlib.sha256(payload).hexdigest()


def make_request(wav, tx, rx, cc, tg, key, staged_path):
    fields = dict(audio=staged_path, tx=validate_mhz(tx),
                  rx=validate_mhz(rx), digital="1", cc=str(cc), tg=str(tg),
                  issued=str(int(time.time() * 1000)),
                  nonce=secrets.token_hex(16), sha256=sha_wav(wav))
    fields["mac"] = hmac.new(bytes.fromhex(key), canonical(fields),
                             hashlib.sha256).hexdigest()
    return fields


def shell(*args, input_bytes=None):
    subprocess.run(args, input=input_bytes, check=True)


def send_adb(wav, fields):
    base = PREFIX
    staged = fields["audio"]
    shell("adb", "shell", "su", "-c", f"mkdir -p {base}/audio")
    uid = subprocess.check_output(
        ["adb", "shell", "su", "-c", f"stat -c %u {base}"], text=True).strip()
    shell("adb", "shell", "-T", "su", "-c",
          f"cat > {staged}; chmod 600 {staged}; chown {uid}:{uid} {staged}",
          input_bytes=wav.read_bytes())
    args = ["adb", "shell", "am", "broadcast", "-a", ACTION]
    for name in FIELDS + ("mac",):
        args += ["--es", name, fields[name]]
    shell(*args)
    print("Request dispatched; inspect: adb logcat -s RumiTX-TX:I")
    print("A local success is NOT an RF-audio verification.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wav", required=True, type=Path)
    parser.add_argument("--tx", required=True)
    parser.add_argument("--rx")
    parser.add_argument("--cc", type=int, default=1)
    parser.add_argument("--tg", type=int, default=99)
    parser.add_argument("--key", type=Path, default=Path("tx_audio.key"))
    parser.add_argument("--adb", action="store_true",
                        help="LIVE RF REQUEST: only use on operator-authorized channels")
    args = parser.parse_args()
    if not args.wav.is_file() or not args.key.is_file():
        parser.error("WAV or key file missing")
    if not 0 <= args.cc <= 15 or not 1 <= args.tg <= 16777215:
        parser.error("CC or talkgroup out of range")
    secret = args.key.read_text().strip()
    if not re.fullmatch(r"[a-fA-F0-9]{64}", secret):
        parser.error("key must be 32 random bytes encoded as 64 hex characters")
    staged = f"{PREFIX}/audio/{secrets.token_hex(12)}.wav"
    fields = make_request(args.wav, args.tx, args.rx or args.tx,
                          args.cc, args.tg, secret, staged)
    if args.adb:
        send_adb(args.wav, fields)
    else:
        print("DRY RUN: no broadcast, no RF transmission")
        print("TX MHz:", fields["tx"])
        print("WAV SHA256:", fields["sha256"])
        print("Nonce:", fields["nonce"])
        print("To transmit after provisioning and authorization, specify --adb.")


if __name__ == "__main__":
    main()
