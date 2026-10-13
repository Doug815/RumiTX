# RumiTX v2.3.1 — TX_AUDIO bridge

**BASE:** https://github.com/Doug815/RumiTX/tree/v2.3.1-source
**FEATURE:** feature/tx-audio-v231

The public main branch is outdated. Never downgrade the DOOGEE S80. This
branch preserves v2.3.1 TAK positions, emergency beacon, GeoChat/messaging,
repeater markers, maps, holo UI, HIDL and PTT helper functionality.

Application ID: com.rumitx.app
Bridge update versionCode: 20301032
Bridge update versionName: 2.3.1-txbridge

Do not install a debug-signed APK over the existing release-signed app. Build
with the SAME original v2.3.1 signing certificate, stored off-repository.
Do not uninstall the installed app to work around a signature mismatch.

## Integration

ThinkPad Omarchy (qwen3:4b verified; voice agent and dashboard built)
 -> TTS 16 kHz mono PCM WAV (max 15 seconds)
 -> tools/tx_audio_client.py HMAC request
 -> USB ADB -> com.rumitx.app.TX_AUDIO dynamic RadioService receiver
 -> singleton RadioController programChannel/pttDown/pttUp
 -> MediaPlayer audio via speaker -> acoustic microphone pickup -> DMR vocoder.

The preexisting /dev/ttyMT1 at 57600 8N1 uses binary DMR007. The helper
/data/local/tmp/rumitx-ptt at localhost:45921 is SINGLE CLIENT. The bridge
creates no second helper socket or serial connection. RadioService owns
the existing singleton. The PttDispatcher blocks competing radio commands
and PTT-down during bridge TX; PTT-up remains available.

## Remote command API

Action: com.rumitx.app.TX_AUDIO
All command extras are STRING fields:
  audio   absolute file under /data/user/0/com.rumitx.app/files/voice_bridge/audio/
  tx      MHz decimal; required
  rx      optional RX MHz (defaults TX)
  digital optional, only 1 in this version
  cc      optional 0-15, default 1
  tg      optional 1-16777215, default 99

Authentication extras:
  issued  13-digit UTC epoch milliseconds, <=90-second clock skew
  nonce   32 lowercase hex characters (persisted, replay prevented)
  sha256  64 lowercase hex characters (entire WAV file digest)
  mac     HMAC-SHA256 signature as 64 hex characters

Signature canonical UTF-8 payload is these 10 newline-separated items:
TX_AUDIO_V1, audio, tx, rx, digital, cc, tg, issued, nonce, sha256.
The client signs exact values and the server validates SHA-256 and full
WAV contents before any PTT command.

Result action: com.rumitx.app.TX_RESULT, package-scoped. Contains success,
message, tx, sha256 and rf_verified=false. Remote observation:
adb logcat -s RumiTX-TX:I
Audit: private voice_bridge/tx_events.jsonl

## Security and RF authorization

The 400-470 MHz hardware tuning range does not confer authority to transmit.
An explicit operator-controlled file allowed_channels.csv must list every
allowed MHz,digital,color_code,talkgroup tuple, one per line, for example:

YOUR_ALLOWED_MHZ,1,1,99

Only use real MHz values allowed for the operator, mode and service.
No channel is enabled by default. Pair by running:
bash tools/provision_tx_audio.sh

The pairing process creates a 32-byte random shared key in tx_audio.key.
NEVER commit keys, allowlists, signing certificates or personal radio IDs.
The receiver fails closed without key and allowlist.

Bridge enforces authentication, replay prevention, strict 44-byte WAV
header at 16kHz, mono 16-bit, max 15 seconds, SHA, TX cooldown and
single-session radio lock. PTT-up always attempted in final cleanup.
Afterward it attempts to restore the previously selected operator channel.
A hardware/helper watchdog remains mandatory; process crash or a blocking
kernel call cannot be guaranteed safe by app code alone. The existing helper
source defaults to a 120-second TX timeout and supports the startup variable
RUMITX_TX_TIMEOUT. For authorized supervised voice testing, configure a
shorter independent watchdog (for example 30 seconds), confirm that the
running helper actually uses that limit, and verify watchdog PTT release.
Do not restart or replace the active helper during a transmission.

## Sender and tests

Produce canonical WAV:
ffmpeg -i input.wav -ac 1 -ar 16000 -sample_fmt s16 output.wav

Dry run (does NOT transmit):
python3 tools/tx_audio_client.py --wav output.wav --tx YOUR_ALLOWED_MHZ

Live on an explicitly permitted channel with a suitable receiver/test setup:
python3 tools/tx_audio_client.py --wav output.wav --tx YOUR_ALLOWED_MHZ --adb

The dynamic receiver exists only when RadioService is running; start
RumiTX once after boot, then no touchscreen actions are needed for requests.
Acoustic speaker-to-microphone coupling is the working baseline reported
by the operator. MediaPlayer completion is NOT proof of intelligible DMR
audio on the air. SET_LOOPBACK_TYPE is NOT modified or validated here.
Use an authorized DMR listening receiver and record **decoded audio**
in addition to RTL-SDR RF observations.

## Operator-confirmed transmit timing (2026-10-12)

**PTT DOWN -> wait 1,000 ms -> WAV playback -> PTT UP immediately.**
The one-second pause belongs strictly BEFORE WAV playback, after the TX
microphone/radio has keyed. It is NOT a post-WAV tail. The pause must be
cancellable, and PTT must be released without additional delay on failure,
shutdown, loss of helper, or playback error. The controller exposes
`isKeyed()`; the bridge checks it during the pre-playback wait.

## Audio routing research: controlled experiment (not a digital-injection claim)

Report source: ASTRA_AUDIO_INJECTION_FINDINGS.txt (investigator: Rumi).
StockAudioRoute.java sets `SET_MIC_NUM=1` on prepare, then on positive
HIDL RX state sets `SET_MIC_NUM=2`, `RESET_MIC2_COUNTER=0`, and
`SET_LOOPBACK_TYPE=3, 3`; stop restores the stock settings.
The MediaTek HAL binary contains loopback-related strings/symbols, but
these DO NOT establish a valid playback-to-DMR-TX route or what numeric
loopback values mean. Zero ALSA PCM substreams were observed in RUNNING
state in an active TX test, which is evidence for a hardware/DSP bypass
but does not categorically rule out AP audio injection.

A voluntary **Android output-stream probe** is now available without
modifying HAL mixer state or adding new DMR007/serial/helper clients.
Only provisioned root/app-private access can set the routing profile:

- Default: no `voice_bridge/audio_route_profile.txt`, or `acoustic`:
  MediaPlayer STREAM_MUSIC and existing speaker->mic baseline.
- `voice_call_probe`: MediaPlayer STREAM_VOICE_CALL, NO MODE_IN_CALL and
  NO vendor `setParameters()` modifications. This MAY route to an
  earpiece or other output; **it is not presumed to be digital injection**.
  The bridge logs `AUDIO_ROUTE profile=...`.

Set/revert profiles from a trusted ADB shell, retaining app-private ownership:
```bash
# Only AFTER developer/operator verification of SELinux and local files:
adb shell su -c 'printf "%s\\n" voice_call_probe > /data/user/0/com.rumitx.app/files/voice_bridge/audio_route_profile.txt'
# Revert to the baseline after testing:
adb shell su -c 'printf "%s\\n" acoustic > /data/user/0/com.rumitx.app/files/voice_bridge/audio_route_profile.txt'
```
If the test phone cannot read these files due to ownership/SELinux context,
have the app owner write them or restore the original label; do not
disable SELinux globally.

**Minimum evidence:** (A) a clean WAV with known speech and a call sign,
(B) exact PTT/WAV timestamps, (C) decoded DMR voice recording from a
separate authorized receiver, (D) note whether the phone speaker or
earpiece audibly emitted the clip, (E) post-test HIDL RX and channel
recovery. Compare identical audio and radio conditions across both
profiles. A speaker/earpiece still sounding while the DMR receiver hears
audio cannot distinguish acoustic pickup from genuine digital routing.
Use a properly controlled isolation experiment before making that claim.

`tools/audio_injection_survey.sh` performs read-only ADB snapshots of
AudioFlinger/AudioPolicy, ALSA status, and optional tinymix information.
It does not key the radio or modify mixer controls. Capture before and
during a supervised, legally authorized TX session; compare diffs.
**Do not blindly scan undocumented SET_LOOPBACK_TYPE values in production.**
A separate reversible HAL lab build should only follow a verified
parameter mapping and independent PTT watchdog validation.

## Release gates

1. Verify this branch is derived from v2.3.1-source, never old main.
2. Confirm package ID and versionCode greater than installed v2.3.1.
3. Install Android SDK API 33 (target SDK stays 27); run
   bash tools/build_hidl_compile_stubs.sh before Gradle 7.5 with JDK 17.
   The jar is compileOnly and must NEVER be bundled; device firmware owns HIDL.
4. Sign privately using the original release certificate (NOT debug key).
5. Compare signing cert SHA-256 against the installed phone APK.
6. Reject bad MAC, replay, old timestamp, bad audio hash, unauthorized
   channel, out-of-range frequency and simultaneous manual PTT without RF.
7. Observe PTT-down then PTT-up on valid test and all failure paths.
8. Confirm previous channel returns, RX audio, TAK, GeoChat, beacons,
   markers, maps, messages and channel bank continue functioning.
9. Prove acoustic audio works over-the-air with RTL-SDR V4. Report any
   failed or unverified conditions explicitly.

A GitHub Actions workflow produces a *debug test build* only if CI passes.
That artifact is not an installable upgrade to a differently-signed release.
No on-device or RF validation has yet been performed here.
