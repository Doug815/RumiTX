#!/usr/bin/env bash
# Rooted DOOGEE S80: change ONLY TX_AUDIO analog-FM speaker gain.
# Does not transmit, modify the DMR audio path or touch vendor HAL routing.
set -euo pipefail

usage() {
  echo "Usage: bash tools/set_analog_fm_volume.sh <0..100|reset>" >&2
  echo "Current analog-only default after reset is 40% of STREAM_MUSIC max." >&2
  exit 2
}
SETTING="${1:-}"
if [[ "$SETTING" != reset && ! "$SETTING" =~ ^(100|[0-9]{1,2})$ ]]; then usage; fi
command -v adb >/dev/null || { echo "adb is not installed" >&2; exit 1; }
adb get-state >/dev/null || { echo "No ADB device" >&2; exit 1; }
BASE='/data/user/0/com.rumitx.app/files/voice_bridge'
APPUID="$(adb shell su -c 'stat -c %u /data/user/0/com.rumitx.app/files' | tr -d '\r')"
[[ "$APPUID" =~ ^[0-9]+$ ]] || { echo "Cannot detect RumiTX UID" >&2; exit 1; }

if [[ "$SETTING" == reset ]]; then
  adb shell su -c "rm -f '$BASE/analog_tx_volume_percent.txt'"
  echo "Analog-FM speaker gain restored to default 40% on next TX_AUDIO."
else
  adb shell su -c "mkdir -p '$BASE'; printf '%s\n' '$SETTING' > '$BASE/analog_tx_volume_percent.txt'; chown '$APPUID:$APPUID' '$BASE/analog_tx_volume_percent.txt'; chmod 600 '$BASE/analog_tx_volume_percent.txt'; restorecon '$BASE/analog_tx_volume_percent.txt' 2>/dev/null || true"
  echo "Analog-FM speaker volume set to $SETTING% for the NEXT signed TX_AUDIO request."
fi
echo "DMR playback, TX radio helper and HIDL audio routing remain unchanged."
