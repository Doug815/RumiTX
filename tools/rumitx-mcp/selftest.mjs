#!/usr/bin/env node
/** Lists MCP tools and checks the transmit labels. Does not open a radio or a phone API. */
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const server = path.join(path.dirname(fileURLToPath(import.meta.url)), "server.mjs");
const child = spawn(process.execPath, [server], { stdio: ["pipe", "pipe", "inherit"] });
let buffer = Buffer.alloc(0);
const inbox = [];
let waiters = [];

child.stdout.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd < 0) return;
    const header = buffer.slice(0, headerEnd).toString("utf8");
    const match = /Content-Length:\s*(\d+)/i.exec(header);
    if (!match) throw new Error("missing Content-Length");
    const length = Number(match[1]);
    const start = headerEnd + 4;
    if (buffer.length < start + length) return;
    const raw = buffer.slice(start, start + length).toString("utf8");
    buffer = buffer.slice(start + length);
    const message = JSON.parse(raw);
    if (waiters.length) waiters.shift()(message);
    else inbox.push(message);
  }
});

function send(message) {
  const json = Buffer.from(JSON.stringify(message), "utf8");
  child.stdin.write(`Content-Length: ${json.length}\r\n\r\n`);
  child.stdin.write(json);
}

function next() {
  if (inbox.length) return Promise.resolve(inbox.shift());
  return new Promise((resolve) => waiters.push(resolve));
}

send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "selftest", version: "0" } } });
const init = await next();
if (!init.result || init.result.serverInfo.name !== "rumitx") throw new Error("initialize failed");
send({ jsonrpc: "2.0", id: 2, method: "tools/list" });
const listed = await next();
const names = listed.result.tools.map((item) => item.name);
for (const required of ["rumitx_key_ptt", "rumitx_unkey_ptt", "rumitx_tx_audio", "rumitx_status"]) {
  if (!names.includes(required)) throw new Error("missing " + required);
}
for (const item of listed.result.tools) {
  const transmit = item.name === "rumitx_key_ptt" || item.name === "rumitx_unkey_ptt" || item.name === "rumitx_tx_audio";
  const labelled = item.description.startsWith("TRANSMIT:");
  if (transmit !== labelled) throw new Error(item.name + " label mismatch: " + item.description);
}
if (listed.result.tools.some((item) => /read\.token|command\.token|secretKey/.test(JSON.stringify(item)))) {
  throw new Error("tool list exposes a secret");
}
child.kill();
console.log("PASS: MCP transmit tools are labelled and the server speaks stdio JSON-RPC");
