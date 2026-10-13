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
  digital optional; 1 = DMR (default), 0 = analog narrowband FM simplex
  cc      DMR only, 0-15 default 1; analog must be 0 (default for analog)
  tg      DMR only, 1-16777215 default 99; analog must be 0 (default for analog)
  rx      analog v1 must equal tx (simplex only; split analog unverified)

Analog v1 channel settings are narrowband FM (gwb=0), no CTCSS/DCS
tones (carrier squelch), and default squelch 4. Receivers with an active
tone-squelch requirement will NOT open until tone support is added.
Talkgroup/color code have NO analog meaning and are 0/0 only for
backward-compatible signed V1 request fields.

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
# analog FM simplex without CTCSS/DCS, separate explicit opt-in:
YOUR_ALLOWED_ANALOG_MHZ,0,0,0

Only use real MHz values allowed for the operator, mode and service.
Add actual numeric frequencies only after confirming service authorization;
the literal placeholders above are not valid allowlist entries.
An approved DMR frequency does not automatically authorize analog FM.
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

Prepare a canonical, speech-conditioned TX WAV on the ThinkPad:
python3 tools/prepare_tx_speech.py input.wav output.wav

This produces a strict RIFF 44-byte header with 16 kHz mono PCM16,
high-pass/low-pass speech filtering, a compressor, and a peak limiter.
Unlike an ordinary ffmpeg WAV export, it avoids an extra LIST metadata
chunk that the strict v1 TX_AUDIO WAV parser would reject. The tool
rejects input audio over 15 seconds rather than silently truncating it.

Select the DEFAULT acoustic baseline explicitly:
bash tools/set_audio_route_profile.sh acoustic

Only for a supervised A/B audio-routing experiment:
bash tools/set_audio_route_profile.sh voice_call_probe
# After test, restore:
bash tools/set_audio_route_profile.sh acoustic

For read-only audio-path snapshots, before and during an authorized test:
bash tools/audio_injection_survey.sh idle
bash tools/audio_injection_survey.sh during_tx

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

## Analog FM TTS transmit (new v2.3.1 TX_AUDIO feature)

**Source-grounded capability:** RadioController.programChannel() selects
DMR007 command 68 35 for analog, 68 36 for digital. Channel.buildProgramFrame()
already constructs the analog 68 35 payload (RX/TX frequency, bandwidth,
squelch, CTCSS/DCS if separately configured). Existing PTT methods issue
the same UART send-start and single-client native PTT helper commands.
The bridge now selects analog when signed digital=0, rather than hard-
coding DMR. The same MediaPlayer acoustic speaker->mic coupling and
one-second pre-playback PTT settling are reused.

**Not yet hardware-verified:** Existing Dmr007Frame source marks 68 35 analog
programming as reverse-engineered but not validated live on the DOOGEE S80.
Build success does NOT establish that analog audio is modulated, or that
the existing radio pttDown() returns a sendstart ACK when analog is active.
The first device test should validate 68 35 channel ACK, module PTT
sendstart, actual analog FM RF, intelligible audio on a second analog
receiver, and successful PTT release / former-channel restoration.

The ThinkPad sender defaults to DMR for backward compatibility. For an
authorized analog test channel configured in allowed_channels.csv:

    python3 tools/tx_audio_client.py --wav prepared.wav --tx YOUR_ALLOWED_ANALOG_MHZ --mode analog
    # With adequate independent FM monitoring and operator approval ONLY:
    python3 tools/tx_audio_client.py --wav prepared.wav --tx YOUR_ALLOWED_ANALOG_MHZ --mode analog --adb

The first line is a NO-RF dry run. The second may key RF. The sender
HMAC-signs digital=0, cc=0, tg=0, then invokes the same TX_AUDIO intent.
A raw adb broadcast with only digital=0 is NOT authenticated; the existing
HMAC fields and allowlist are mandatory. Analog/split-frequency requests
or analog CC/TG not 0/0 are rejected rather than guessed.

The module's 68 35 frame uses gwb=0 for narrowband (reported ~12.5 kHz)
and 0 for no tone. Receiver radios must have compatible analog FM
frequency, bandwidth and open/carrier squelch. Repeater tones, split
TX/RX, wideband mode, TX power selection and other analog features
are deliberately OUT OF SCOPE for this safe first iteration.

An operator's amateur license does not by itself permit transmission
on every RF channel inside the hardware's 400-470 MHz range. Analog
and digital emissions each need their own lawful authorization.

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


### MediaTek HAL source cross-check (upstream analogue, NOT the S80 binary)

Public MediaTek V3 audio sources contain a `LoopbackManager.h` enum mapping
`3 = AP_REF_MIC_AFE_LOOPBACK` (reference mic input) and
`3 = LOOPBACK_OUTPUT_SPEAKER` (output destination). On that implementation
`SET_LOOPBACK_TYPE=3,3` means **reference microphone -> speaker** and is a
factory mic-loopback route, **not Android PCM playback -> DMR uplink**.
The source also explicitly distinguishes modem-acoustic-mic loopback
types 21-26; those too are input-microphone test routes, not evidence
of a MediaPlayer-to-DMR vocoder insertion point.

Sources:
- https://github.com/nofearnohappy/vendor_AOSP/blob/4e7966126c5475a20df2733bcadf358694711550/mediatek/proprietary/hardware/audio/common/V3/include/LoopbackManager.h
- https://github.com/nofearnohappy/vendor_AOSP/blob/4e7966126c5475a20df2733bcadf358694711550/mediatek/proprietary/hardware/audio/common/V3/aud_drv/LoopbackManager.cpp
- https://github.com/LineageOS/android_device_xiaomi_mt6785-common/blob/f93a39c4f4a51982be591e326324d70b2dc847ab/rootdir/bin/setup_mainmic2headphone.sh

**Caveat:** These are related MediaTek platforms, not the S80's
`audio.primary.mt6763.so` binary. Verify its actual enum/parser and
codec route before extrapolating. Given this discovery, changing
`SET_LOOPBACK_TYPE` is **deprioritized**. The defensible research task
is mapping the S80's hardware **TX ADC/mic input** and testing whether
the AP playback mixer has a real insertion path before that input.
Ordinary telephony-uplink and BGS examples for cellular modems do not
automatically apply to an external DMR vocoder.

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

A GitHub Actions workflow produces a *debug test build* and verified
SHA256SUMS.txt on success. The CI artifact is not an installable upgrade
to an app signed with another certificate. Prior DMR TX_AUDIO control
sequence was validated on the S80 by the operator. Analog FM TX_AUDIO
has NOT yet been tested on S80 hardware or independently RF verified.


## RTL-SDR V4: verify actual received DMR speech

The reported acoustic versus voice_call_probe A/B test measured 29.8 dB
versus 30.4 dB RF above noise. Both transmissions keyed. Neither proves
that the generated spoken message survived DMR vocoding. Acoustic output
was restored as the known-good default; preserve the 1,000 ms delay
between successful PTT key-down and playback start.

The feature branch includes a **receive-only by default** DSD-FME
verification tool for Doug's Omarchy ThinkPad and RTL-SDR Blog V4:

    python3 tools/dmr_rx_capture.py --freq 446.00625 --dry-run
    python3 tools/dmr_rx_capture.py --freq 446.00625 --duration 40 --gain 8

When a separate operator-authorized transmission occurs, it saves
per-call decoded DMR speech WAVs in the session directory, DMR
event/decoder logs, and a JSON report.

For a difficult-to-decode test, add --save-demod to retain 48 kHz FM
discriminator audio in dmr_discriminator_48k.wav (NOT decoded voice,
and NOT raw I/Q). This recording supports independent offline replays
without transmitting again. DSD-FME documents -6 to capture raw 48 kHz
demodulated audio and accepts 48 kHz mono WAV as -i input. The feature
is OFF by default to conserve disk space:

    python3 tools/dmr_rx_capture.py --freq 446.00625 --duration 40 --save-demod

Replay the saved 48 kHz discriminator WAV into DSD-FME offline using its
-fs and -i WAV options, then compare the newly decoded voice file. For an independently captured
decoded WAV (e.g. from SDRTrunk), compare timing with the original TTS:

    python3 tools/analyze_dmr_voice.py RECEIVED_WAV_DIRECTORY --reference prepared.wav

The receiver supports **explicit**, device-allowlisted live-test
transmission using --send --ack-authorized --wav prepared.wav, passing
through the existing signed TX_AUDIO client. No second RumiTX radio
helper/serial connection is opened.

Upstream DSD-FME audio_work documents -fs simplex, -i rtl direct RTL,
-7 output directory -P per-call decoded speech WAV, and -J event log:
https://github.com/lwvmobile/dsd-fme/blob/audio_work/examples/cygwin_bat/complete_usage_options.txt
Installation for Arch/Omarchy is documented upstream:
https://github.com/lwvmobile/dsd-fme/blob/audio_work/examples/Install_Notes.md
Check current RTL-SDR V4 drivers and MBELib library/licensing before use;
do not blindly install outdated prebuilt dependencies. SDRTrunk with
JMBE is another supported DMR speech decoding route.

**Evidence levels:** RF detected < DMR sync < non-silent decoded WAV <
independently verified spoken words. A real decoded speech WAV can still
come from **acoustic** speaker-to-mic coupling. Direct digital audio
injection requires additional isolated routing tests; the analyzer
deliberately does not assert it. Use low RTL gain to avoid saturating
the receiver front end with a nearby transmitting S80.


## DSD-FME 252 kHz tuning and no-sync investigation — 2026-10-12

**Observed:** TX_AUDIO worked on the S80 with STREAM_MUSIC and VOICE_CALL.
RTL-SDR showed RF activity but DSD-FME did not report DMR sync and
created 44-byte, empty TEMP WAV headers. Gain 20/40 did not help.

**Critical finding — the +252 kHz offset is INTERNAL TO DSD-FME.**
In rtl_sdr_fm.cpp, optimal_settings sets tuner center to the requested
channel + capture_sample_rate/4; rtlsdr_callback digitally shifts IQ
back to the requested RF channel. For 12/24 kHz bandwidth, the
capture rate is 1,008,000 samples/sec and the offset +252,000 Hz.
It is DC-spike avoidance, not a receiver frequency error.

For a station physically transmitting on 446.00625 MHz:
- Correct receiver channel argument: 446.00625M
- Expected RTL hardware tuner center: 446.25825 MHz
- WRONG manually compensated argument: 445.75425M; DSD-FME would
  digitally demodulate 445.75425 MHz rather than the actual channel.

Upstream: https://github.com/lwvmobile/dsd-fme/blob/audio_work/src/rtl_sdr_fm.cpp

The capture tool now reports RF target and expected RTL center
separately and parses explicit DMR sync messages. RF activity or
a 44-byte WAV is not evidence of successfully decoded DMR traffic.

### Receive-only baseline (does not key the phone)

    python3 tools/dmr_rx_capture.py --freq 446.00625 --gain 8 --rtl-bw-khz 12 --duration 40 --save-demod

Use your actual authorized RF test frequency and transmit separately
only as needed. Save a discriminator WAV for offline decoder replay.

### If DMR sync is still absent

1. Verify session.json requests actual RF frequency and that the
   DSD-FME tuner log prints that frequency plus the internal offset.
2. Check the RF waterfall for DMR 4FSK-modulated bursts, rather than
   assuming every keyed RF carrier contains standard DMR data.
3. Decrease RTL gain (try --gain 0 automatic or --gain 8) or separate
   transmitter/SDR antennas to avoid RF front-end overload. Gain
   20/40 can make saturation worse, not better.
4. Test reverse DMR polarity as an isolated comparison:

    python3 tools/dmr_rx_capture.py --freq 446.00625 --gain 8 --invert-dmr --duration 40 --save-demod

5. Change ONLY ONE variable at a time to compare --decoder-mode auto
   (DSD-FME -fa), --rtl-bw-khz 24 or --rtl-volume 1.
6. Decode a known-good unencrypted DMR signal on the same SDR and
   decoder setup before concluding the S80's framing is non-standard.
7. If a reference DMR signal syncs but the S80 never does, investigate
   the DMR007 module's actual transmitted modulation, channel mode,
   symbol timing and HIDL/PTT TX status rather than focusing on speakers.

No physical DMR sync normally means tune/signal/demodulation must be
checked BEFORE hypothesizing encryption or voice scrambling.
The exact words cannot be verified until a separate RF receiver
decodes actual speech samples. 44-byte WAVs contain no PCM speech.
