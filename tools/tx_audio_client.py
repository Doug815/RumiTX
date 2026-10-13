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
import shlex
import subprocess
import sys
import time
import wave

ACTION = "com.rumitx.app.TX_AUDIO"
PREFIX = "/data/user/0/com.rumitx.app/files/voice_bridge"
FIELDS = ("audio", "tx", "rx", "digital", "cc", "tg", "issued", "nonce", "sha256")


def canonical(data):
    version = "TX_AUDIO_V2" if "tone" in data else "TX_AUDIO_V1"
    values = [data[k] for k in FIELDS]
    if "tone" in data:
        values += [data["tone"], data["tone_source"]]
    return "\n".join([version] + values).encode("utf-8")


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


def make_request(wav, tx, rx, cc, tg, key, staged_path, digital="1", tone=None, tone_source="module"):
    if digital not in ("0", "1"):
        raise ValueError("digital must be 0 (analog FM) or 1 (DMR)")
    tx = validate_mhz(tx)
    rx = validate_mhz(rx)
    if digital == "0":
        if cc != 0 or tg != 0:
            raise ValueError("analog FM requires cc=0 tg=0")

    elif not (0 <= cc <= 15 and 1 <= tg <= 16777215):
        raise ValueError("DMR color code or talkgroup out of range")
    fields = dict(audio=staged_path, tx=tx,
                  rx=rx, digital=digital, cc=str(cc), tg=str(tg),
                  issued=str(int(time.time() * 1000)),
                  nonce=secrets.token_hex(16), sha256=sha_wav(wav))
    if tone is not None:
        if digital != "0":
            raise ValueError("CTCSS tone is analog-only")
        if tone_source not in ("module", "software"):
            raise ValueError("tone source must be module or software")
        match = re.fullmatch(r"([0-9]{2,3}\.[0-9])(?: \(([0-9]{1,2})\))?", tone)
        if not match:
            raise ValueError("tone must be Hz, e.g. 100.0 or 100.0 (13)")
        tones = (67.0,69.3,71.9,74.4,77.0,79.7,82.5,85.4,88.5,91.5,94.8,97.4,100.0,103.5,107.2,110.9,114.8,118.8,123.0,127.3,131.8,136.5,141.3,146.2,151.4,156.7,159.8,162.2,165.5,167.9,171.3,173.8,177.3,179.9,183.5,186.2,189.9,192.8,196.6,199.5,203.5,206.5,210.7,218.1,225.7,229.1,233.6,241.8,250.3,254.1)
        hz = float(match[1])
        if hz not in tones or (match[2] is not None and int(match[2]) != tones.index(hz) + 1):
            raise ValueError("CTCSS frequency/module index conflict or unsupported frequency")
        fields.update(tone=tone, tone_source=tone_source)
    fields["mac"] = hmac.new(bytes.fromhex(key), canonical(fields),
                             hashlib.sha256).hexdigest()
    return fields


def shell(*args, input_bytes=None):
    subprocess.run(args, input=input_bytes, check=True)


def send_adb(wav, fields):
    base = PREFIX
    staged = fields["audio"]
    shell("adb", "shell", "su -c " + shlex.quote(f"mkdir -p {base}/audio"))
    uid = subprocess.check_output(
        ["adb", "shell", "su -c " + shlex.quote(f"stat -c %u {base}")], text=True).strip()
    if not uid.isdecimal() or not re.fullmatch(re.escape(PREFIX) + r"/audio/[0-9a-f]+\.wav", staged):
        raise ValueError("unsafe staging path or owner")
    command = f"set -e; cat > {staged}; chmod 600 {staged}; chown {uid}:{uid} {staged}; restorecon {staged}"
    shell("adb", "shell", "-T", "su -c " + shlex.quote(command), input_bytes=wav.read_bytes())
    args = ["adb", "shell", "am", "broadcast", "-a", ACTION]
    for name in FIELDS + (("tone", "tone_source") if "tone" in fields else ()) + ("mac",):
        args += ["--es", name, fields[name]]
    # Quote the whole remote command: tone convention contains spaces and parentheses.
    shell("adb", "shell", shlex.join(args[2:]))
    print("Request dispatched; inspect: adb logcat -s RumiTX-TX:I")
    print("A local success is NOT an RF-audio verification.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--wav", required=True, type=Path)
    parser.add_argument("--tx", required=True)
    parser.add_argument("--rx")
    parser.add_argument("--mode", choices=("dmr", "analog"), default="dmr",
                        help="DMR digital (default), or narrowband analog FM")
    parser.add_argument("--tone", help="analog CTCSS Hz, e.g. 100.0 or '100.0 (13)'")
    parser.add_argument("--tone-source", choices=("module", "software"), default=None,
                        help="module generator (default) or in-app sine/voice mixer")
    parser.add_argument("--cc", type=int,
                        help="DMR color code (default 1); analog FM must use 0")
    parser.add_argument("--tg", type=int,
                        help="DMR talkgroup (default 99); analog FM must use 0")
    parser.add_argument("--key", type=Path, default=Path("tx_audio.key"))
    parser.add_argument("--adb", action="store_true",
                        help="LIVE RF REQUEST: only use on operator-authorized channels")
    args = parser.parse_args()
    if not args.wav.is_file() or not args.key.is_file():
        parser.error("WAV or key file missing")
    digital = "0" if args.mode == "analog" else "1"
    cc = args.cc if args.cc is not None else (0 if digital == "0" else 1)
    tg = args.tg if args.tg is not None else (0 if digital == "0" else 99)
    if digital == "0":
        if cc != 0 or tg != 0:
            parser.error("analog FM requires cc=0 and tg=0; DMR fields do not apply")
    elif not 0 <= cc <= 15 or not 1 <= tg <= 16777215:
        parser.error("DMR CC or talkgroup out of range")
    if args.tone_source is not None and args.tone is None:
        parser.error("--tone-source requires --tone")
    secret = args.key.read_text().strip()
    if not re.fullmatch(r"[a-fA-F0-9]{64}", secret):
        parser.error("key must be 32 random bytes encoded as 64 hex characters")
    staged = f"{PREFIX}/audio/{secrets.token_hex(12)}.wav"
    fields = make_request(args.wav, args.tx, args.rx or args.tx,
                          cc, tg, secret, staged, digital=digital, tone=args.tone,
                          tone_source=args.tone_source or "module")
    if args.adb:
        send_adb(args.wav, fields)
    else:
        print("DRY RUN: no broadcast, no RF transmission")
        print("Mode:", args.mode)
        print("TX MHz:", fields["tx"])
        print("WAV SHA256:", fields["sha256"])
        print("Nonce:", fields["nonce"])
        print("To transmit after provisioning and authorization, specify --adb.")


if __name__ == "__main__":
    main()
