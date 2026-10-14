#!/usr/bin/env bash
# One-shot analog FM PTT-only experiment with stock HIDL RX route temporarily OFF.
# WARNING: receiver monitoring may be interrupted for the four-second TX test.
# Uses ONLY the verified HAL loopback-off setting SET_LOOPBACK_TYPE=0;
# leaves mic selection/gain untouched and restores loopback=3,3 afterward.
# Arm does NOT transmit; only next separately authorized, signed TX_AUDIO
# using 4.0 s zero PCM can key the analog module.
set -euo pipefail

MODE="${1:-}"
if [[ "$MODE" != "arm" && "$MODE" != "reset" ]]; then
  echo "Usage: bash tools/set_analog_rx_route_probe.sh arm|reset" >&2
  echo "arm: one authorized analog PTT-only TX with RX audio route temporarily suspended" >&2
  echo "reset: remove BOTH one-shot mode flags without transmitting" >&2
  exit 2
fi
command -v adb >/dev/null || { echo "Missing adb" >&2; exit 1; }
adb get-state >/dev/null || { echo "S80 not connected over ADB" >&2; exit 1; }

# ADB joins argv into a remote shell command; quote the complete su payload.
root_cmd() { adb shell "su -c $(python3 -c 'import shlex,sys; print(shlex.quote(sys.argv[1]))' "$1")"; }
BASE='/data/user/0/com.rumitx.app/files/voice_bridge'
APPUID="$(root_cmd 'stat -c %u /data/user/0/com.rumitx.app/files' | tr -d '\r')"
[[ "$APPUID" =~ ^[0-9]+$ ]] || { echo "Cannot read RumiTX UID" >&2; exit 1; }

if [[ "$MODE" == reset ]]; then
  root_cmd "rm -f '$BASE/analog_rx_route_probe.txt' '$BASE/analog_tx_diagnostic_mode.txt'"
  echo "RX route / PTT-only one-shot flags disarmed. No changes to active audio routing."
else
  # Validate package version before enabling this mode; old builds won't
  # implement automatic RX restoration or fail-closed RX-route diagnostics.
  VERSION="$(adb shell dumpsys package com.rumitx.app | grep -m1 'versionCode=' || true)"
  if [[ ! "$VERSION" =~ versionCode=20301037 ]]; then
    echo "Refusing to arm: requires installed RumiTX versionCode 20301037." >&2
    echo "Detected: $VERSION" >&2
    exit 3
  fi
  # Set both flags in one rooted command. The files must be owned by app UID.
  root_cmd "set -e; mkdir -p '$BASE'; \
printf '%s\n' ptt_only > '$BASE/analog_tx_diagnostic_mode.txt'; \
printf '%s\n' suspend_stock_rx > '$BASE/analog_rx_route_probe.txt'; \
chown '$APPUID:$APPUID' '$BASE/analog_tx_diagnostic_mode.txt' '$BASE/analog_rx_route_probe.txt'; \
chmod 600 '$BASE/analog_tx_diagnostic_mode.txt' '$BASE/analog_rx_route_probe.txt'; \
restorecon '$BASE/analog_tx_diagnostic_mode.txt' '$BASE/analog_rx_route_probe.txt' 2>/dev/null || true"
  echo "One-shot RX route probe ARMED. Next signed, allowlisted analog TX_AUDIO"
  echo "with exactly 4s all-zero WAV temporarily applies SET_LOOPBACK_TYPE=0 ONLY."
  echo "No MediaPlayer. No volume mutation. 1s post-PTT settle + 4s key hold."
  echo "The probe is disarmed before radio keying and restores the RX route"
  echo "after PTT UP. Verify received audio and HIDL RX after the test."
  echo "If radio RX is not restored, restart RumiTX radio service normally."
fi
