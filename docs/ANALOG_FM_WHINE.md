# DOOGEE S80 analog FM whine: mitigation and diagnostic protocol

## Findings

Operator reports analog FM TX_AUDIO voice is intelligible but has a
prominent high-pitched whine. DMR with the same WAV does not exhibit it.
No recorded received-FM audio, whine frequency or silent-key test was
supplied. Acoustic feedback, vendor mic monitor, speaker-amplifier EMI,
analog mic gain or FM modulator effects remain hypotheses.

Code inspection:
- Previous VoiceTxBridge forced Android STREAM_MUSIC to MAXIMUM during
  acoustic TX, including analog FM.
- StockAudioRoute, after successful HIDL RX callback, sets
  SET_MIC_NUM=2, RESET_MIC2_COUNTER=0 and SET_LOOPBACK_TYPE=3, 3.
  On similar MediaTek platforms the latter is mic-to-speaker loopback;
  its exact S80 TX behavior is NOT verified. Feedback requires a closed
  monitoring route, not speaker-to-mic pickup alone.
- Existing DMR007 68 35 analog programming, 68 36 DMR, single-client
  radio controller/helper, and 1000 ms post-PTT/pre-WAV delay work and
  must remain unchanged.

## Code fix candidate in this PR

Analog FM digital=0 now defaults to 40% speaker STREAM_MUSIC volume
instead of 100%; other output profiles use the same analog-only gain
cap. The old stream volume is restored in finally after transmission.
Digital=1 DMR audio routing and output level are UNCHANGED.
The new private analog_tx_volume_percent.txt setting accepts 0..100
and is validated BEFORE PTT. No vendor HAL parameters are modified.
CI tests cover isolation, request signing, and PTT safety.

After privately signing/installing the compatible APK on the S80:

    bash tools/set_analog_fm_volume.sh 40
    bash tools/set_analog_fm_volume.sh 20
    bash tools/set_analog_fm_volume.sh 0
    bash tools/set_analog_fm_volume.sh reset

Value 0 is for controlled analog silence/mic-path isolation tests only.
Check the Android log tag RumiTX-TX for ANALOG_FM_GAIN percent=N and
the actual stream volume step. Reset removes the file (default 40%).

## Conclusive A/B whine diagnosis using receive-side FM audio

Generate fully valid, canonical 16 kHz mono PCM16 test WAVs (this never
keys the transmitter):

    python3 tools/make_analog_fm_test_wavs.py --duration 4

Produces analog_fm_diag_sources/silence.wav (all-zero samples) and
analog_fm_diag_sources/tone_1khz.wav (low-level 1 kHz calibration tone).

Use ONLY an RF frequency, service, emission, power and equipment the
operator is authorized to use. Set a second FM radio to carrier/open
squelch, no required CTCSS tone, with narrowband FM.

Use two terminals. On the ThinkPad first set FM_MHZ to the actual
authorized numeric MHz channel. Terminal A starts the SDR RECEIVER:

    python3 tools/analog_fm_rx_capture.py --freq "$FM_MHZ" --duration 25

This opens RTL-SDR V4 through rtl_fm, produces a received FM WAV,
decoder log and JSON spectrum report. It NEVER commands S80 RF TX.

Terminal B must send exactly one signed, approved analog FM TX_AUDIO:

    bash tools/set_analog_fm_volume.sh 40
    python3 tools/tx_audio_client.py --mode analog --tx "$FM_MHZ" --wav analog_fm_diag_sources/silence.wav --adb

Capture once. Repeat a SECOND received recording with the same silence
WAV but analog playback at 0%:

    bash tools/set_analog_fm_volume.sh 0
    python3 tools/tx_audio_client.py --mode analog --tx "$FM_MHZ" --wav analog_fm_diag_sources/silence.wav --adb

Then a THIRD recording with an ordinary prepared 16 kHz TTS WAV at 40%:

    bash tools/set_analog_fm_volume.sh 40
    python3 tools/tx_audio_client.py --mode analog --tx "$FM_MHZ" --wav prepared.wav --adb

Reset the analog volume afterward:

    bash tools/set_analog_fm_volume.sh reset

Each receive session saves analog_fm_rx_tests/TIMESTAMP with a WAV.
Inspect the actual TX portion (adjust start seconds to the keyed audio):

    python3 tools/analyze_analog_fm_whine.py analog_fm_rx_tests/TIMESTAMP/received_analog_fm.wav --start 3 --duration 6 --output whine_report.json

The pure-Python detector measures persistent spectral peaks from
150-3400Hz. Only analyze WAVs from the **RF receiver**, not original
TTS. The peak is an objective candidate, not proof of acoustic
feedback. Listen to recordings and compare identical receiver gain,
distance and channel settings.

## Diagnostic decision tree

1. Whine decreases substantially at 40% versus old 100% while voice
   remains intelligible: amplifier/mic acoustic overdrive is implicated;
   fine-tune 20-60% with comparable RF receive recordings.
2. Whine remains even during the 0%-speaker silence WAV: the tone is
   generated downstream or by the mic/analog radio path, not in WAV
   playback. Check HIDL audio loopback, mic gain, power supply and
   analog modulator without changing working firmware blindly.
3. Whine during 40%-speaker silence but not 0%: monitor/amp activity
   is implicated even without audible TTS. Verify vendor loopback state.
4. Silence clean but speech whines: compare analog 20% vs 40% and
   controlled VOICE_CALL audio output, then analyze acoustic/AGC behavior.
5. Whine only on SDR but absent from a separate analog FM receiver:
   investigate receiver gain, overload, USB power and FM de-emphasis.

Do not apply an arbitrary software notch filter to the source WAV:
a tone created after the S80 microphone/audio stage will not be
removed by prefiltering the original speech.

## Safety and acceptance criteria

Do not retune vendor SET_LOOPBACK_TYPE numerically in production;
it is used for stock RX functionality. Existing HIDL RX, manual PTT,
DRM/DMR service, TAK, beacons, channel bank and DMR voice are hard
regression gates. Preserve one-second pre-playback delay, immediate
PTT-up failure cleanup and prior operator channel restoration.

A green CI APK is a COMPILE/CONTRACT pass, not verified whine removal.
GitHub Actions generates APK, SHA256SUMS.txt, BUILD_INFO.txt; use a
matching private release certificate before installing. No mismatch
workaround; never upload a private signing key.


## 2026-10-13: 40%-versus-0% silent-WAV evidence and next isolation

Operator inspected receiver-demodulated FM WAVs and heard a prominent
whine with signed 4-second silence at BOTH gain levels. TX_RESULT
confirmed requested 40% and 0%, and analog gain was restored to 40%.

| Condition | Dominant received tone | Harmonic spacing | Prominence |
| --- | --- | --- | --- |
| Silent WAV, 40% stream gain | 961 Hz | about 240 Hz | 48.6 dB |
| Silent WAV, 0% stream gain | 1609 Hz | about 400-402 Hz | ~48 dB |

The 961Hz tone disappeared into noise at 0%, but a new 1609Hz tone
appeared. The approximate fourth harmonics suggest a periodic source
with fundamental near 240 Hz or 400 Hz, but that physical cause is
NOT yet established. Android STREAM_MUSIC volume can change codec,
power or mixer behavior even when playing zero samples. Muting
does not prove the speaker amplifier shuts off; do not conclude
the FM modulator itself is solely responsible.

### Next test: real analog FM PTT with NO Android audio output

New build versionCode 20301034 adds a hard-limited ONE-SHOT analog
PTT-only diagnostic. For that request the bridge neither creates,
prepares nor starts MediaPlayer, and it never adjusts stream volume.
The original 1000ms post-key settle remains. Radio keys for exactly
4000ms after settle; the normal finally clause always attempts PTT UP.
No analog gain, DMR audio, stock HIDL RX routing or DMR007 settings
are changed. The setting cannot linger after a valid attempt: it is
consumed before channel programming. No undocumented HAL commands.

A valid signed TX_AUDIO request is still required and MUST supply an
exactly four-second all-zero canonical 16kHz mono PCM WAV. A nonzero
sample or the wrong duration fails before RF keying.

From the PR #1 checkout on the Omarchy ThinkPad:

    git fetch origin
    git switch feature/tx-audio-v231
    git pull --ff-only
    python3 tools/make_analog_fm_test_wavs.py --duration 4
    bash tools/set_analog_fm_diag_mode.sh ptt_only

That last command arms only the private local setting using rooted
ADB; it does NOT transmit. Then listen on an independently authorized
FM channel with RTL-SDR or a second analog radio. Choose the actual
authorized frequency in FM_MHZ, never assume 400-470MHz hardware
range equals transmit authorization.

Terminal A (receive only, start first):

    python3 tools/analog_fm_rx_capture.py --freq "$FM_MHZ" --duration 25

Terminal B (single operator-approved signed TX request):

    python3 tools/tx_audio_client.py --mode analog --tx "$FM_MHZ" --wav analog_fm_diag_sources/silence.wav --adb

Expected log markers:
- ANALOG_PTT_ONLY_ARMED
- ANALOG_PTT_ONLY_NO_AUDIO_ENGINE
- PTT_DOWN_CONFIRMED
- ANALOG_PTT_ONLY_START
- ANALOG_PTT_ONLY_COMPLETE
- TX_RESULT success=true ... diagnostic=ptt_only_no_media_player

Any failure still triggers the normal PTT-up cleanup. The normal
radio channel is restored. A single diagnostic request consumes the
private mode file before RF programming. If necessary, manually
disarm the config with:

    bash tools/set_analog_fm_diag_mode.sh reset

A TX_RESULT success for this test means the controlled key/hold test
completed, NOT that spoken voice was emitted or that interference is
solved. Continue analyzing the actual receiver-demodulated FM WAV.

Interpretation:
1. Whine disappears with PTT-only: MediaPlayer initialization,
   STREAM_MUSIC output, volume setting or powered audio path is
   implicated. Follow up by isolating player prepared-vs-started in
   another reversible build, not by guessing vendor loopback values.
2. Whine remains at a similar 400Hz-family spacing without playback:
   RF can pick up this tone independently of the app player. Investigate
   persistent HIDL mic monitoring, powered codec path, microphone bias,
   local power switching or analog FM circuitry.
3. Harmonics shift with receiver gain or receiver/USB power:
   rule out receiver overload or SDR USB power artifacts using a
   second analog radio and constant receiver settings.

An immediately available extra check on the EXISTING build (no APK
required): select the same analog channel in the S80 UI and make
a brief manual physical-PTT transmission WITHOUT TX_AUDIO running,
recorded by an independent analog radio or RTL-SDR receiver.
This removes bridge playback and bridge volume changes, although
stock HIDL audio setup may remain active. Repeat with S80 on battery
if safe, without the ThinkPad USB charging connection, using the
physical PTT and a separately powered receiver.

No unsigned diagnostic broadcast field exists. The private local mode
setting is intentionally separate from the signed V1 message format.
