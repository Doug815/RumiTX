# S80 / RTL-SDR V4 verification, 2026-10-13

These are test results, not a declaration that the app is finished.

## Analog whine

Baseline 20301034 retained during investigation. Clean boot partition copied
as binary: prior 33,601,502-byte dump was expanded by CRLF translation.
Clean image 33,554,432 bytes; kernel gzip decompresses correctly.
Recovered kernel kallsyms and ARM64 disassembly show KEY_DOWN selects
mainmic/headsetmic/mic_switch/PTT GPIOs. It contains no periodic PWM/timer.
SPEAK_ENABLE waits on an event; SPEAK_DISABLE calls HCT_Disable_PA, which
turns off the AW87319 amplifier. Its identified existing ioctl was tested
with a separate descriptor kept open, preventing close from releasing PTT.
The periodic line persisted after amplifier shutdown.

Receiver-offset comparison, transmitter unchanged at 446.00625 MHz:

| Receive tuning | Measured carrier offset | Strong line |
|---|---:|---:|
| nominal | -387.3 Hz | 388 Hz |
| nominal minus 1 kHz | +709.3 Hz | 710 Hz |
| nominal plus 1 kHz | -1310.5 Hz | 1311 Hz |

The line tracks receiver carrier offset; it cannot be attributed to an
independent fixed-frequency transmitter oscillator from these recordings.
Raw CU8 capture plus high-rejection complex filtering and phase-difference
demodulation removes the moving carrier-offset line. No kernel/vendor
patch or speculative app workaround is justified by this evidence.

Silent-WAV baseline capture, confirmed TX seconds 5 through 9:
961 Hz band: -80.81 dB; 1609 Hz band: -81.37 dB; 1680 Hz band: -80.60 dB.
These are largest bins within +/-5 Hz, Hann window, amplitude relative to
2500 Hz FM deviation. They include receiver noise. They are not dBc or a
calibrated transmitter noise specification. The original unspecified
-80 dB requirement cannot be asserted under a different reference.
`tools/analog_iq_demod.py` makes the reference and active TX span explicit.

## CTCSS experimental implementation

V1 no-tone requests retained. V2 signs tone and source as well as all V1
fields. 100.0 and 100.0 (13) accepted; mismatched index, hidden DCS overrides,
DMR tone, and post-signature modification rejected before PTT on the phone.
Module generator and software mixer are mutually exclusive. Software mixer
retains 75% speech and adds continuous 15% sine in a temporary WAV; original
WAV hash remains authenticated and temporary WAV is removed after TX.
Analog split TX/RX can be expressed, but real repeater operation is unverified.

Phone build 20301035 installed using the baseline's local signing key,
so channels/settings were retained. Both tone sources completed on-phone
TX, but raw-IQ RF measurement found roughly 0.0-0.2 Hz of 100 Hz deviation
in silent playback, with no usable CTCSS. Ordinary analog speech was
recovered; automated transcription recovered the count one through five.
This build is experimental and must not be called repeater-capable.
New upstream RX-route diagnostic changes were subsequently preserved in
the combined 20301036 build; all final results are recorded locally.

## Corrected DMR receive

Requested frequency 446.00625 MHz, gain 8, duration 40 seconds, discriminator
WAV saved. DSD-FME reports tuner center 446258250 Hz internally. No user
frequency offset applied. Normal run logged 16 +DMR sync lines, CC1,
source 1234, group 99, and saved 4.68 seconds of decoded 8 kHz voice.
Inversion was not needed. Decoded audio is non-silent; intelligible speech
was not established by the initial recording. Automated transcription
did not reproduce the reference sentence, so this is frame/voice-data
decode success, not verified end-to-end intelligible voice.

## MediaTek microphone route analysis

HAL setMIC1Mode/setMIC2Mode select named ACC, DCC, DMIC and ECM paths
through AudioALSADeviceConfigManager. Pulled audio_device.xml maps these
to Audio_MIC1_Mode_Select / Audio_MIC2_Mode_Select. Built-in Mic1 uses
ADC1 and IN_ADC1 preamps; headset uses ADC2/IN_ADC2. SetAdcPga1/2 apply
configured mixer gain enums. LoopbackManager controls audio routes; modem
and Bluetooth CVSD functions are generic facilities, not evidence that
the external radio uses them. Kernel PMIC helpers identify bias0 register 0x1618 (enable bit 0; VREF
field mask 7 shifted by 4) and bias1 VREF at 0x161a. Actual live values
and voltage encoding were not measured, and neither register was changed.
There is no evidence tying either register to the receiver-offset tone.

Analog and DMR both use KEY_DOWN argument 0 (mainmic high, headsetmic low,
mic_switch low). Host PTT GPIO selection does not depend on analog/DMR.
That choice travels by UART to the external module. No analog-specific
HAL mic routing was established. Internal radio-module bias/ADC/filtering
remains unobserved. Stock RX loopback callbacks can occur at PTT start
even with no MediaPlayer, so the earlier test did not eliminate that
route. No undocumented loopback values or vendor patches were used.

## Final combined-build checks

20301036 installed and tested after preserving upstream commits through
a2ac475. Module and software CTCSS again showed no usable 100 Hz RF tone.
Standalone compilation of the actual CtcssAudio mixer produced exactly
100.0 Hz at 15% peak on the canonical 4-second silence input. This
separates successful app generation from failed on-air tone delivery.
The stock RX-route one-shot guard consumed its flags and rejected PTT
because no successful RX-open callback was active. That refusal is
verified; actual suspend/restore under received RF was not verified.

A baseline repeat at normal polarity, same RF frequency and a source WAV
attenuated by 12 dB, also synchronized and saved decoded audio. Speech
recognition still did not reproduce the known sentence. A failed first
baseline repeat was excluded: TX audit showed radio not yet available
after package restoration, so no RF transmission occurred.

SQLite comparison confirmed all 80 saved channel rows unchanged from
the initial baseline backup. No user channel frequencies were swept.
The signing certificate was retained; no uninstall/data wipe performed.
Test WAVs and RF evidence are in the local investigation directory, and
keys/backups are deliberately excluded from Git.

Final combined 20301036 DMR regression again produced normal +DMR sync
and a saved decoded voice WAV. Baseline 20301034 was restored afterward.
The RX-route probe flags were consumed before its fail-closed refusal;
no diagnostic flag remains armed.
