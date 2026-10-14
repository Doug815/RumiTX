# S80 analog tone modes and independent directions

The S80 firmware `ver0.5.1_470M_TONE` expects these analog mode bytes:
1 carrier / no tone, 2 CTCSS, 3 normal DCS, 4 inverted DCS.
Baseline20301034 sends mode0 for None and mode1 for CTCSS. These are
not the S80's firmware modes, despite matching a related NiceRF module's
published older protocol. Do not substitute that older protocol here.

The factory image was copied read-only from `/system/etc/talkie/update.bin`.
SHA256: 559a54ea77c3c70aefe08b0772bde3bfd48f18d165b12de450bfd3c7bbd53233.
It contains the same version string as the installed radio module.
No firmware flashing, register writes, new ioctls or vendor patches were used.

Evidence from Thumb disassembly, addresses relative to image start:
- Analog SET35 handler0x2244a..0x22484 copies payload SQL+10 to channel+0x1c,
  RX mode+11 to +0x1d, RX code+12 to +0x1e, TX mode+13 to +0x1f,
  and TX code+14 to +0x20, with no mode conversion.
- Receive task0x1ce8c..0x1cea8 masks RX mode with7. Mode1 calls carrier
  squelch routine0x1cf2a; modes2..4 use both carrier and tone GPIO inputs.
  Mode0 reaches0x1cefa without calling either squelch-opening routine.
- RX configuration0x19f02: mode2 reads CTCSS table and programs register4a;
  modes3/4 program DCS with normal/inverted detection; mode1 programs
  the no-tone route. Mode0 skips those configuration branches.
- TX configuration0x19d8a: mode1 bypasses tone generation; mode2 reads
  the CTCSS table; modes3/4 select DCS normal/inverted generation.

This explains why baseline can receive at SQL0 (separate monitor bypass)
but fails to open at SQL1 with mode0, and why earlier module CTCSS tests
using mode1 emitted no tone. RF acceptance must still be measured.

Build20301039 uses the correct firmware modes and preserves independent
TX/RX settings. The separate baseline bug mirrored TX-only tones into RX
and vice versa. None stays carrier mode1/code0 in its own direction.
Existing frequency-to-code chart is unchanged. DMR programming is unchanged.

Validation:
- Debug build, seven bridge contract tests, fourteen analog probe tests,
  thirteen digital voice receive contract tests pass.
- ToneSettingsTest verifies actual frame bytes for independent None,
  CTCSS mode2, normal/inverted DCS modes3/4, polarity rejection, and DMR.
- Earlier installed20301037 tests verified independent field programming
  and all81 channel rows were preserved, but used the old wrong mode IDs;
  those ACK/readback tests did not validate firmware tone semantics.
- Installed20301039 on S80 and ran the test-only dex against actual APK
  classes: PASS. Its actual RadioController programmed the physical module
  and CHECK_AC confirmed RXNone mode1/code0, TX100Hz mode2/code13,
  then restored both directions to mode1/code0 at Tester SQL1. No PTT.
- RF reception and actual transmitted CTCSS still need external-radio
  acceptance; do not claim RF reception from an ACK or idle GPIO.
