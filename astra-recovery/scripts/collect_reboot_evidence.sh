#!/usr/bin/env bash
# Read-only. Run from a computer with adb after device reboot.
set -u
OUT="${1:-rumitx-reboot-evidence}"
mkdir -p "$OUT"
echo 'Collection is read-only: does not start a radio, modify GPIO, permissions, or PTT.'
for cmd in \
  'id -Z' \
  'getenforce' \
  'getprop ro.build.fingerprint' \
  'getprop ro.hct_talkie_support_type' \
  'ls -lZ /dev/hctintercom_node /dev/ttyMT1' \
  'ps -A | grep -Ei "talkie|rumitx|hct"' \
  'lshal | grep -i talkie' \
  'cat /proc/interrupts | grep -Ei "hct|speak|gpio|eint"' ; do
  echo "\n### $cmd" >> "$OUT/environment.txt"
  adb shell "$cmd" >> "$OUT/environment.txt" 2>&1 || true
done
adb logcat -d -v threadtime -b all > "$OUT/logcat.txt" 2>&1 || true
adb shell 'dmesg | grep -Ei "talkie|hct|intercom|gpio|eint|speak|aw pa"' > "$OUT/kernel_talkie.txt" 2>&1 || true
printf 'Completed %s. Review/redact logs before sharing; they can contain private activity.\n' "$OUT"
