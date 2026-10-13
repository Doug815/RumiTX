#!/usr/bin/env bash
# S80 analog FM RF diagnostic: PTT-only, NO Android audio engine.
# Trusted rooted ADB only. Does NOT itself command radio transmission.
# Requires signed, allowlisted TX_AUDIO with exactly 4.0s all-zero PCM WAV.
set -euo pipefail
MODE="${1:-}"
if [[ "$MODE" != "ptt_only" && "$MODE" != "reset" ]]; then
  echo "Usage: bash tools/set_analog_fm_diag_mode.sh ptt_only|reset" >&2
  echo "ptt_only: arm exactly ONE authenticated analog transmission; auto-disarm before PTT" >&2
  exit 2
fi
command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
adb get-state >/dev/null || { echo "S80 ADB disconnected" >&2; exit 1; }
BASE='/data/user/0/com.rumitx.app/files/voice_bridge'
UID_APP="$(adb shell su -c 'stat -c %u /data/user/0/com.rumitx.app/files' | tr -d '\r')"
[[ "$UID_APP" =~ ^[0-9]+$ ]] || { echo "Unable to resolve RumiTX UID" >&2; exit 1; }
if [[ "$MODE" == "reset" ]]; then
  adb shell su -c "rm -f '$BASE/analog_tx_diagnostic_mode.txt'"
  echo "Analog PTT-only diagnostic disarmed; normal 40% analog playback restored on next TX."
else
  adb shell su -c "mkdir -p '$BASE'; printf '%s\n' ptt_only > '$BASE/analog_tx_diagnostic_mode.txt'; chown '$UID_APP:$UID_APP' '$BASE/analog_tx_diagnostic_mode.txt'; chmod 600 '$BASE/analog_tx_diagnostic_mode.txt'; restorecon '$BASE/analog_tx_diagnostic_mode.txt' 2>/dev/null || true"
  echo "ONE SHOT armed: next signed analog TX_AUDIO with EXACTLY 4s canonical zero PCM"
  echo "will key 1s settle + 4s hold WITHOUT creating MediaPlayer or touching volume."
  echo "File consumed before channel programming/PTT; any error fails closed."
  echo "NOTE: still a real authorized analog RF transmission; no phone WAV playback."
fi
