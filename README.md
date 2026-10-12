# RumiTX — 2030

![RumiTX logo](assets/rumitx-logo.png)

### An open replacement for the DOOGEE S80 stock Talkie app

---
> **Note on source code:** The release APK was built from a newer source tree than what's currently committed to this repository. The public source is missing the 2030 holographic UI (it has stock Material Light instead), SMS screens, auto-power management, and the Discovery results list is unfinished. The screenshots and feature descriptions below reflect the release APK. Updated source will be committed when available.
---

## The Problem

The DOOGEE S80 is a rugged phone with a built-in DMR/UHF radio module (DMR007, 400–470 MHz). Fantastic hardware — but the stock Talkie app is holding it back:

- **Clunky, outdated interface** that feels like 2015
- **No easy frequency programming** — manually punching in channels one by one
- **Limited channel management** — no import, no organization
- **Bugs that never get fixed** — the manufacturer moved on

Thousands of S80 owners are stuck with software that doesn't match the hardware's potential. Forum posts going back years ask the same question: *"Is there an alternative to the stock Talkie app?"*

**Until now, the answer was no.**

## What We Did

RumiTX is a from-scratch rebuild of the S80's radio interface, reverse-engineered from the stock app's HIDL HAL (`ITalkiePort`), the DMR007's UART binary protocol, and the kernel's HCT intercom driver.

### v2.3-beta-phase2 — What's Working

**Core Radio:**
- ✅ **RX audio** — Full receive path through the HIDL callback
- ✅ **TX** — Verified via RTL-SDR (on frequency within 100 Hz)
- ✅ **Analog + DMR** channel programming via DMR007 UART frames
- ✅ **Hardware PTT** — Side key + on-screen hold-to-talk
- ✅ **Auto-reconnect** — App automatically reconnects to the radio module if the link drops

**Repeater Database (NEW in v2.3):**
- ✅ **CSV import** — RepeaterBook and CHIRP format, your own personal-use exports
- ✅ **316 repeaters** imported and searchable
- ✅ **Tap to program** — Touch a repeater, module programs instantly
- ✅ **PL/DCS tone programming** — Standard 50-tone CTCSS and 83-code DCS tables; toned repeaters program automatically
- ✅ **Manual tone pickers** — Dropdown shows "100.0 Hz (13)" with module index
- ✅ **Nearest/radius search** — GPS-based repeater sorting

**Offline Maps (NEW in v2.3):**
- ✅ **Mapsforge vector maps** — Full offline rendering, no internet needed
- ✅ **Repeater pins** — Database entries shown on the map
- ✅ **GPS location** — Your position on the offline map

**DMR Identity (NEW in v2.3-phase2):**
- ✅ **Editable callsign and DMR ID** — Set your radio's identity, displayed on main screen
- ✅ **Persistent** — Survives app restarts

**UI:**
- ✅ **2030 holographic interface** — Futuristic dark theme with glowing accents
- ✅ **Channel bank** — Tap to tune, add custom channels
- ✅ **Discovery mode** — Scan for activity

### Coming Soon
- **DMR text messaging** — Pending UART probe to verify module data-frame support
- **ATAK integration** — CoT position sharing, repeater markers, team messaging
