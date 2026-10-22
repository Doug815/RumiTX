#!/usr/bin/env node
/**
 * RumiTX MCP server for OpenCode. Stdio JSON-RPC, no npm dependencies.
 * Transmit tools are labelled TRANSMIT. They still obey the phone's tx_enabled
 * switch, command token, allowlist, and public-safety deny list.
 *
 * Environment:
 *   RUMITX_API            default http://127.0.0.1:8765
 *   RUMITX_READ_TOKEN     bearer for status, chat, infer, logs, audit, unkey
 *   RUMITX_COMMAND_TOKEN  bearer for program, scan, volume, key, audio
 */
import http from "node:http";
import https from "node:https";

const API = process.env.RUMITX_API || "http://127.0.0.1:8765";
const READ = process.env.RUMITX_READ_TOKEN || "";
const COMMAND = process.env.RUMITX_COMMAND_TOKEN || "";

const tools = [
  tool("rumitx_status", "Read radio status, channel, squelch, helper health, battery, and whether transmit is enabled. Does not key the radio.", {}, false),
  tool("rumitx_channels", "List saved channels. Frequencies and a boolean encrypted flag only. Secrets are never returned.", {}, false),
  tool("rumitx_logs", "Read the bounded radio and agent log tail.", { limit: { type: "integer" } }, false),
  tool("rumitx_tx_audit", "Read the append-only transmit audit (who, token fingerprint, channel, start, stop, duration). The raw token is not included.", { limit: { type: "integer" } }, false),
  tool("rumitx_infer", "Interpret an utterance the way the rumiai client does. Never executes and never keys.", { utterance: { type: "string" } }, false),
  tool("rumitx_chat", "On-device chat. Does not execute tool JSON and does not key the radio.", { message: { type: "string" } }, false),
  tool("rumitx_program_channel", "Program a channel and do not key. Agent programming forces encryption off. Requires the command token.", channelSchema(), true),
  tool("rumitx_activate_channel", "Activate a saved channel by id, index, or name. Does not key. Requires the command token.", { id: { type: "integer" }, channel: { type: "integer" }, name: { type: "string" } }, true),
  tool("rumitx_set_volume", "Set module volume 0..10. Requires the command token.", { level: { type: "integer" } }, true),
  tool("rumitx_scan_start", "Scan a span of at most 2 MHz. Does not key. Requires the command token.", { from_mhz: { type: "number" }, to_mhz: { type: "number" }, step_mhz: { type: "number" } }, true),
  tool("rumitx_scan_stop", "Stop a scan. Requires the command token.", {}, true),
  tool("rumitx_key_ptt", "TRANSMIT: Key PTT on the current channel. Requires tx_enabled on the phone, the command token, and an on-device allowlist entry. Public-safety and out-of-service frequencies are refused. The phone unkeys at max key time (default 15 s). hold=true keeps the key until this HTTP client disconnects, still capped by that timer.", { hold: { type: "boolean" } }, true),
  tool("rumitx_unkey_ptt", "TRANSMIT: Unkey PTT immediately. This stop is always available and does not require the command token.", {}, false),
  tool("rumitx_tx_audio", "TRANSMIT: Send 16 kHz mono 16-bit WAV through VoiceTxBridge and release PTT when playback ends. Same tx_enabled, command token, allowlist, and deny-list gates as keying. Pass wav_base64.", { wav_base64: { type: "string" }, id: { type: "integer" }, channel: { type: "integer" }, name: { type: "string" } }, true)
];

function tool(name, description, properties, command) {
  const required = [];
  if (name === "rumitx_infer") required.push("utterance");
  if (name === "rumitx_chat") required.push("message");
  if (name === "rumitx_tx_audio") required.push("wav_base64");
  if (name === "rumitx_set_volume") required.push("level");
  if (name === "rumitx_scan_start") required.push("from_mhz", "to_mhz");
  return {
    name,
    description,
    inputSchema: { type: "object", properties, required, additionalProperties: false },
    _command: command
  };
}

function channelSchema() {
  return {
    name: { type: "string" },
    rx_mhz: { type: "number" },
    tx_mhz: { type: "number" },
    digital: { type: "boolean" },
    color_code: { type: "integer" },
    talkgroup: { type: "integer" },
    squelch: { type: "integer" },
    save: { type: "boolean" }
  };
}

function send(message) {
  const json = Buffer.from(JSON.stringify(message), "utf8");
  process.stdout.write(`Content-Length: ${json.length}\r\n\r\n`);
  process.stdout.write(json);
}

function result(id, value) {
  send({ jsonrpc: "2.0", id, result: value });
}

function failure(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } });
}

function callPhone(method, path, command, body, hold) {
  const token = command ? COMMAND : (READ || COMMAND);
  if (!token) return Promise.resolve({ status: 0, body: JSON.stringify({ ok: false, error: "RUMITX token is not set" }) });
  const url = new URL(path, API.endsWith("/") ? API : API + "/");
  const payload = body == null ? null : Buffer.from(JSON.stringify(body), "utf8");
  const lib = url.protocol === "https:" ? https : http;
  return new Promise((resolve) => {
    const req = lib.request({
      protocol: url.protocol,
      hostname: url.hostname,
      port: url.port || (url.protocol === "https:" ? 443 : 80),
      path: url.pathname + url.search,
      method,
      headers: {
        Authorization: "Bearer " + token,
        Accept: "application/json",
        "Content-Type": "application/json",
        "Content-Length": payload ? payload.length : 0
      }
    }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => {
        resolve({ status: res.statusCode || 0, body: Buffer.concat(chunks).toString("utf8") });
        if (hold) req.destroy();
      });
    });
    req.setTimeout(hold ? 130000 : 20000, () => {
      req.destroy();
      resolve({ status: 0, body: JSON.stringify({ ok: false, error: "phone API timed out" }) });
    });
    req.on("error", (err) => resolve({ status: 0, body: JSON.stringify({ ok: false, error: err.message }) }));
    if (payload) req.write(payload);
    req.end();
  });
}

async function dispatch(name, args) {
  const body = args && typeof args === "object" ? args : {};
  switch (name) {
    case "rumitx_status": return callPhone("GET", "/status", false, null, false);
    case "rumitx_channels": return callPhone("GET", "/channels", false, null, false);
    case "rumitx_logs": return callPhone("GET", "/logs", false, null, false);
    case "rumitx_tx_audit": return callPhone("GET", "/tx/audit", false, null, false);
    case "rumitx_infer": return callPhone("POST", "/infer", false, { utterance: String(body.utterance || "") }, false);
    case "rumitx_chat": return callPhone("POST", "/chat", false, { message: String(body.message || "") }, false);
    case "rumitx_program_channel": return callPhone("POST", "/channels/program", true, body, false);
    case "rumitx_activate_channel": return callPhone("POST", "/channels/activate", true, body, false);
    case "rumitx_set_volume": return callPhone("POST", "/volume", true, body, false);
    case "rumitx_scan_start": return callPhone("POST", "/scan/start", true, body, false);
    case "rumitx_scan_stop": return callPhone("POST", "/scan/stop", true, body, false);
    case "rumitx_key_ptt": return callPhone("POST", "/tx/key", true, { hold: body.hold === true }, body.hold === true);
    case "rumitx_unkey_ptt": return callPhone("POST", "/tx/unkey", false, {}, false);
    case "rumitx_tx_audio": return callPhone("POST", "/tx/audio", true, body, false);
    default: return { status: 0, body: JSON.stringify({ ok: false, error: "unknown tool" }) };
  }
}

async function onMessage(message) {
  if (!message || message.jsonrpc !== "2.0") return;
  const id = message.id;
  if (id === undefined) return;
  if (message.method === "initialize") {
    result(id, {
      protocolVersion: "2024-11-05",
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "rumitx", version: "2.4.0-agent-api" }
    });
    return;
  }
  if (message.method === "tools/list") {
    result(id, { tools: tools.map(({ _command, ...pub }) => pub) });
    return;
  }
  if (message.method === "ping") {
    result(id, {});
    return;
  }
  if (message.method === "tools/call") {
    const name = message.params && message.params.name;
    const args = message.params && message.params.arguments;
    const response = await dispatch(name, args);
    const text = `HTTP ${response.status}\n${response.body}`;
    const failed = response.status === 0 || response.status >= 400;
    result(id, { content: [{ type: "text", text }], isError: failed });
    return;
  }
  failure(id, -32601, "method not found");
}

let buffer = Buffer.alloc(0);
process.stdin.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd < 0) return;
    const header = buffer.slice(0, headerEnd).toString("utf8");
    const match = /Content-Length:\s*(\d+)/i.exec(header);
    if (!match) {
      buffer = buffer.slice(headerEnd + 4);
      continue;
    }
    const length = Number(match[1]);
    const start = headerEnd + 4;
    if (buffer.length < start + length) return;
    const raw = buffer.slice(start, start + length).toString("utf8");
    buffer = buffer.slice(start + length);
    try { onMessage(JSON.parse(raw)); }
    catch (err) { failure(null, -32700, "parse error"); }
  }
});
process.stdin.resume();
