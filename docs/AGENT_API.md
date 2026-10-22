# RumiTX agent API 2.4.0

Local API inside the foreground radio service. It stays up when the activity closes and starts again after boot, once the app has been opened at least once (Android's stopped-app rule).

Default bind is `127.0.0.1:8765`. The process refuses `0.0.0.0`, `::`, and `*`. AGENT settings can add the phone's Tailscale (or `tun`) IPv4 address. Carrier CGNAT on `wlan` or `rmnet` is not treated as Tailscale.

There is no analytics and no phone-home. Tokens live in the app's private `agent/` directory.

## Tokens

The phone generates two bearer tokens. Both are 32 bytes of hex. They are shown in AGENT so they can be copied to the PC. Regenerate replaces both files immediately.

| Token | Can |
| --- | --- |
| Read | `GET /status`, `/channels`, `/logs`, `/tx/audit`, `/events`; `POST /infer`, `/chat`, `/tx/unkey`, `/tx/stop` |
| Command | Everything the read token can do, plus program, activate, volume, scan, `POST /tool`, `POST /command`, `POST /tx/key`, `POST /tx/audio` |

`POST /tx/allowlist` does not exist (404). The allowlist is edited only on the device.

Send `Authorization: Bearer <token>`. Optional `Idempotency-Key` on command posts. A repeated key returns the first result and does not key twice.

## Transmit rails

Transmit is off on a fresh install. Doug turns on **Transmit enabled** in AGENT. That is the master switch.

A key or audio request must also pass all of these:

1. Command token (the model tool path counts as command authority, and still passes the rest).
2. Current TX and RX frequencies are on the on-device allowlist (64 entries max, match within 0.00002 MHz). AGENT stores each entry as `allowed_channels=` in the private policy file. The HTTP API cannot write that list.
3. Neither frequency is on the built-in deny list. Beloit PD P25 is denied by name at 460.050, 460.075, 460.200, 460.250, 460.350, 460.450, and 460.475 MHz (within 0.0025 MHz). Putting one of those on the allowlist does not override the deny.
4. Both frequencies are inside licensed service: amateur 70 cm, 420.000–450.000 MHz inclusive, or an exact US GMRS channel center within 0.001 MHz. Anything else is outside licensed service.
5. At most 6 keys per minute and a 2 second gap after the last unkey.
6. Max key time, default 15 seconds, configurable from 1 to 120 seconds. 120 seconds is the helper watchdog ceiling.

The radio unkeys on that timer, on `POST /tx/unkey` or `POST /tx/stop`, on API server stop, on helper client disconnect, and on audio completion or error. `POST /tx/key` with `{"hold":true}` keeps the key until that HTTP client drops or the max key time fires, whichever is first. `GET /events?release=1` unkeys when the event stream drops.

Manual PTT on the screen and the hardware key do not go through this policy. The signed external `TX_AUDIO` broadcast still requires its HMAC, DMR-only rule, nonce, and operator CSV. Agent audio is in-process, after this policy, and uses the same speaker playback and finally-unkey path.

Programming a channel does not key. API programming forces encryption off (`encry` `0`, `secretKey` `0`). JSON fields named `encry`, `secret`, `secret_key`, `secretKey`, `encryption_key`, or `aeskey` are rejected. Activating a saved channel programs the saved row and does not return a key. Status may include `"encrypted": true|false`.

`compose_dmr_text` is staged and does not send SMS. Voice phrases such as "transmit" stay unsupported and do not key. A model reply that is only a JSON tool object is executed through the same command layer, still subject to these rails.

## Audit

`files/agent/tx-audit.log` is append-only, fsynced, and rotated (about 64 KiB times 4 generations). Each line records who (`api`, `model`, `ui`, `watchdog`), the token fingerprint (8 hex characters, not the token), channel, event (`start`, `stop`, `deny`, `fail`), and duration. `GET /tx/audit` is read-only.

The voice bridge keeps its own rotated `tx_events.jsonl` for the playback path. Nonces older than the replay window are trimmed.

## Screen

AGENT opens the settings. While the radio is keyed, the main screen shows TRANSMITTING and a transmit notification (id 242) is posted. The radio foreground notification (id 241) title gains the same word.

## Routes

`GET /status`

```json
{
  "service": "rumitx",
  "version": "2.4.0-agent-api",
  "llm": "on-device",
  "radio": { "mode": "idle", "squelch_open": false, "rssi": null }
}
```

`llm` is the string `on-device` or `not-loaded`. `radio.mode` is `disconnected`, `tx`, `scan`, `rx`, or `idle`. `rssi` is null because the module does not report a number. `squelch_open` follows the receiver flag. `battery_pct` is present when the system reports it.

`GET /channels` returns id, index, name, `tx_mhz`, `rx_mhz`, digital, encrypted, squelch, color code, talkgroup.

`GET /logs?limit=100` tails the bounded agent log and the in-memory radio log.

`GET /events` is server-sent events, one `radio` event with the status JSON. The first event is written with the headers.

`POST /infer` `{"utterance":"..."}` with the read token. Response shape matches the rumiai client: `action.intent`, `action.args`, `confirmation_required`, `executed: false`. It never runs the action. `set_frequency` sets `confirmation_required` true. A transmit utterance is `unsupported` and `executed` false.

`POST /chat` `{"message":"..."}` with the read token. Response includes `answer` and `response`. Greetings are answered locally. Tool JSON in the user message is refused. The model is used only when Offline AI has loaded it.

`POST /command` `{"intent","args"}` with the command token. `result.state` is what the rumiai client reads.

| Intent | Effect |
| --- | --- |
| `set_channel` | Activate saved channel `args.channel` (1-based) or `args.name` |
| `set_frequency` | Program RX/TX. Does not key. `result.state` is `applied` |
| `program_channel` | Same as set_frequency |
| `set_volume` | `args.level` 0..10 |
| `scan_start` / `scan_stop` | Listen scan, span at most 2 MHz, step 0.005..1. Does not key |
| `key_ptt` / `unkey_ptt` | Transmit rails above |
| `compose_dmr_text` | `result.state` is `staged`. Nothing is sent |

`POST /tool` is one JSON object `{"tool","args"}` for the same operations: `status`, `channels`, `logs`, `tx_audit`, `program_channel`, `activate_channel`, `set_volume`, `scan_start`, `scan_stop`, `key_ptt`, `unkey_ptt`, `tx_audio`, `chat`.

`POST /channels/program`, `/channels/activate`, `/volume`, `/scan/start`, `/scan/stop` are the same operations as the intents.

`POST /tx/key` optional `{"hold":true}`. `POST /tx/unkey` and `POST /tx/stop` always return 200 with `radio_ack`. `POST /tx/audio` is JSON `{"wav_base64":"..."}` or a raw WAV body. WAV must be canonical 16 kHz mono 16-bit PCM, at most 15 seconds, and not longer than the configured max key time.

Errors: 401 bad token, 403 policy or read token on a command route, 404 unknown route or channel, 409 already keyed or no channel, 400 bad JSON, 502 the radio did not acknowledge.

## PC and rumiai

`adb forward tcp:8765 tcp:8765` then `http://127.0.0.1:8765`. The OpenCode MCP server is `tools/rumitx-mcp/`. Transmit tools are labelled `TRANSMIT:`.

The rumiai preview is an HTTPS client with no port. This process speaks HTTP. Put Tailscale Serve or a reverse proxy on 443 in front of the forward if that client must connect. Do not turn on a wildcard listener to make that work.

## Install

This build is signed with the Android debug key that Gradle creates. It is not signed with the RumiTX Local Debug keystore. `adb install -r` against the installed app fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Uninstalling deletes `/data/data/com.rumitx.app`, including channels, the GGUF, voice-bridge keys, and the new tokens. Back up on the rooted phone before uninstalling:

```
adb shell su -c 'cp /data/data/com.rumitx.app/databases/rumitx.db /sdcard/rumitx-backup.db'
adb shell su -c 'tar -C /data/data/com.rumitx.app/files -cf /sdcard/rumitx-files-backup.tar .'
```

Copy those files off the phone. Restore only after you accept that this APK is a different signer. Open the app once after install so the foreground service and the boot receiver are allowed to run. Transmit stays off until AGENT says otherwise.

## Test without RF

Do not press HOLD TO TALK. Do not install a helper built with `-DRUMITX_HOST_TEST`.

1. `bash tools/test_agent_api.sh` runs the JVM policy, auth, command-layer, HTTP, and tone tests. Beloit PD is denied even if it is written onto the allowlist. The default policy does not key.
2. `bash tools/test_ptt_host.sh` compiles the helper with `-DRUMITX_HOST_TEST`, checks ping, a second connection, and unkey on disconnect. The flag skips the intercom device. A phone binary must be built without that flag.
3. `node tools/rumitx-mcp/selftest.mjs` checks MCP labels and does not open a socket to the radio.
4. On a bench with the service up and transmit still off, `curl` to `POST /tx/key` with the command token returns 403 and the module is not keyed.

## What changed

Compared with 2.3.2-llama-bundled (versionCode 20301050): the local API, shared command layer, on-device model tool calls, PC MCP server, transmit rails above, boot start, and the AGENT screen. Independent TX and RX tone modes (carrier when that direction is None). AI temporary channels keep squelch level 1 and do not copy the RX tone onto TX. The PTT helper accepts the next client after a disconnect, answers `ping` and `status`, and unkeys if the client drops. Chat memory is capped.

The `uploads/` patch series and the notes that described it were not in this workspace, so those four behaviors were taken from the current tree and from the tone-mode fix on `feature/tx-audio-v231` rather than applied with `git am`.

Compared with the older 2.3.1 tone path: that build used one tone type and copied a zero from the other direction, which kept the firmware out of the receive-open path. This build does not. Manual PTT, the voice-TX busy guard, and the HMAC broadcast checks are still in place. Encryption fields are still rejected on the agent path.
