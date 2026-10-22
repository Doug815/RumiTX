# RumiTX MCP server

Small OpenCode MCP server for the phone's local agent API. It has no npm dependencies. Transmit tools are named and described with a `TRANSMIT:` prefix so they are obvious in the tool list.

Do not give this server to the memory librarian. Do not write the bearer tokens into the shared memory store. See `docs/OPENCODE_SHARED_MEMORY.md`.

## Windows (pink monster)

1. Install Node.js 18 or newer.
2. Copy `tools/rumitx-mcp/` to the PC, for example `C:\rumitx\tools\rumitx-mcp\`.
3. On the phone, open RumiTX once, then open AGENT and copy the read and command tokens. Transmit stays off until you turn **Transmit enabled** on and add allowlisted frequencies.
4. Forward the loopback API:

```
adb forward tcp:8765 tcp:8765
```

5. Point OpenCode at `opencode.snippet.json`. Replace the token placeholders. The command array is the local MCP shape OpenCode expects (`type`, `command`, `environment`).
6. Check the tool list. `rumitx_key_ptt`, `rumitx_unkey_ptt`, and `rumitx_tx_audio` must show the TRANSMIT label. `rumitx_status` must not.

`adb forward` reaches `127.0.0.1:8765` on the phone. The phone refuses `0.0.0.0`. If the PC is on the same Tailscale network and AGENT has **Also listen on Tailscale** checked, set `RUMITX_API` to `http://100.x.y.z:8765`. The rumiai companion speaks HTTPS and has no port; put Tailscale Serve or a reverse proxy on 443 in front of this API. This app does not terminate TLS.

## Self-test

`node tools/rumitx-mcp/selftest.mjs` talks to the server over stdio and checks labels. It does not connect to a phone and does not transmit.

## Environment

| Variable | Use |
| --- | --- |
| `RUMITX_API` | Default `http://127.0.0.1:8765` |
| `RUMITX_READ_TOKEN` | Status, channels, logs, audit, infer, chat, and unkey |
| `RUMITX_COMMAND_TOKEN` | Program, activate, volume, scan, key, and audio |
