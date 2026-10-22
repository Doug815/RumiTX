# OpenCode shared memory and this radio

Doug's OpenCode setup on the PC uses a shared memory MCP (`mcp-server.mjs`), a memory librarian, and a tool router so agents can leave notes for each other. Those files were not in this repository (`oc/` was absent), so this note is the fit, not a copy of that server.

## What talks to the phone

`tools/rumitx-mcp/server.mjs` is a separate MCP server. OpenCode launches it with the command array in `tools/rumitx-mcp/opencode.snippet.json`. It forwards tool calls to the phone over `adb forward tcp:8765 tcp:8765` (or a Tailscale address if AGENT has that listener on).

The phone is the source of truth for radio state. Agents should call `rumitx_status` when they need the channel, squelch, or whether transmit is enabled. They should not cache a bearer token in memory.

## What must not receive transmit

The memory librarian and any agent whose job is storing notes must not be given `rumitx_key_ptt`, `rumitx_unkey_ptt`, or `rumitx_tx_audio`. Those three tools are labelled `TRANSMIT:` so a router can filter on that prefix.

Do not write `RUMITX_READ_TOKEN` or `RUMITX_COMMAND_TOKEN` into the memory store, a chat log, or a librarian summary. The phone shows the tokens on the AGENT screen. If they leak, regenerate them there. The audit log stores an 8-hex fingerprint, not the token.

Transmit still does nothing until the phone's **Transmit enabled** switch is on and the channel is on the on-device allowlist. The allowlist is not an MCP tool. Public-safety frequencies, including the Beloit PD P25 channels, are refused on the phone even if a note in memory claims they are allowed.

## Suggested routing

- Librarian: memory tools only.
- Radio agent: the rumitx MCP server, including the labelled transmit tools, only on the machine where Doug has already turned transmit on.
- A memory note may record that a channel was programmed. It must not be treated as permission to key.
