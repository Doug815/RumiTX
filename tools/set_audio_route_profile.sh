#!/usr/bin/env bash
# Safe operator-only test switch for TX_AUDIO's Android playback STREAM.
# Does not key PTT or touch SET_LOOPBACK_TYPE, mixer state, or RF channel.
set -euo pipefail

PROFILE="${1:-}"
case "$PROFILE" in acoustic|voice_call_probe) ;; *)
  echo "Usage: $0 acoustic|voice_call_probe" >&2
  echo "acoustic = working STREAM_MUSIC speaker->mic baseline" >&2
  echo "voice_call_probe = experimental STREAM_VOICE_CALL output; NOT digital injection proof" >&2
  exit 2 ;;
esac
command -v adb >/dev/null || { echo "adb not installed" >&2; exit 1; }
adb get-state >/dev/null
BASE='/data/user/0/com.rumitx.app/files/voice_bridge'
APPUID="$(adb shell su -c 'stat -c %u /data/user/0/com.rumitx.app/files' | tr -d '\r')"
[[ "$APPUID" =~ ^[0-9]+$ ]] || { echo "Could not get RumiTX app UID" >&2; exit 1; }
adb shell su -c "mkdir -p '$BASE'; printf '%s\\n' '$PROFILE' > '$BASE/audio_route_profile.txt'; chown '$APPUID:$APPUID' '$BASE/audio_route_profile.txt'; chmod 600 '$BASE/audio_route_profile.txt'; restorecon '$BASE/audio_route_profile.txt' 2>/dev/null || true"
echo "TX_AUDIO playback profile: $PROFILE (takes effect on next TX request)."
echo "After any probe, revert: bash tools/set_audio_route_profile.sh acoustic"
