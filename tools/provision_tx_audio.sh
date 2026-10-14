#!/usr/bin/env bash
# Rooted-S80 one-time local pairing. Does not key or transmit.
set -euo pipefail
cd "$(dirname "$0")/.."
KEY="tx_audio.key"
CHANNELS="allowed_channels.csv"
BASE="/data/user/0/com.rumitx.app/files/voice_bridge"
if [[ ! -f "$CHANNELS" ]]; then
  echo "Create allowed_channels.csv with only operator-authorized channels." >&2
  echo "Format: MHz,digital,color_code,talkgroup" >&2
  exit 2
fi
if [[ ! -f "$KEY" ]]; then
  umask 077
  python3 - "$KEY" <<'PY'
import secrets,sys
with open(sys.argv[1], 'w') as out: out.write(secrets.token_hex(32)+'\n')
PY
fi
chmod 600 "$KEY"
grep -Eq '^[0-9a-fA-F]{64}$' "$KEY" || { echo "Invalid key" >&2; exit 3; }
adb shell su -c "mkdir -p '$BASE/audio'"
APPUID="$(adb shell su -c 'stat -c %u /data/user/0/com.rumitx.app/files' | tr -d '\r')"
[[ "$APPUID" =~ ^[0-9]+$ ]] || { echo "Cannot determine RumiTX UID" >&2; exit 4; }
adb shell -T su -c "cat > '$BASE/tx_audio.key'; chmod 600 '$BASE/tx_audio.key'; chown '$APPUID:$APPUID' '$BASE/tx_audio.key'" < "$KEY"
adb shell -T su -c "cat > '$BASE/allowed_channels.csv'; chmod 600 '$BASE/allowed_channels.csv'; chown '$APPUID:$APPUID' '$BASE/allowed_channels.csv'" < "$CHANNELS"
adb shell su -c "chown -R '$APPUID:$APPUID' '$BASE'; chmod 700 '$BASE' '$BASE/audio'; restorecon -R '$BASE' 2>/dev/null || true"
echo "TX_AUDIO paired with explicit authorized-channel allowlist."
