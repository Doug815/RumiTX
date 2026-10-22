#!/usr/bin/env bash
# Host protocol test for rumitx-ptt. RUMITX_HOST_TEST skips the intercom device.
# Do not install this binary on a phone. It does not transmit RF.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
gcc -Wall -Wextra -o /tmp/rumitx-ptt-host -DRUMITX_HOST_TEST "$ROOT/native-source/rumitx-ptt.c"
gcc -Wall -Wextra -fsyntax-only "$ROOT/native-source/rumitx-ptt.c"
PORT=45931
RUMITX_TCP=1 RUMITX_PORT="$PORT" /tmp/rumitx-ptt-host >/tmp/rumitx-ptt-host.log 2>&1 &
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT
python3 - "$PORT" <<'PY'
import socket, sys, time
port = int(sys.argv[1])
deadline = time.time() + 5

def connect():
    last = None
    while time.time() < deadline:
        try:
            s = socket.create_connection(("127.0.0.1", port), 1)
            s.settimeout(2)
            return s
        except OSError as exc:
            last = exc
            time.sleep(0.05)
    raise SystemExit("helper did not accept: %s" % last)

def read_line(sock):
    data = b""
    while b"\n" not in data:
        chunk = sock.recv(1)
        if not chunk:
            break
        data += chunk
    return data.decode()

def command(sock, line):
    sock.sendall((line + "\n").encode())
    return read_line(sock)

one = connect()
ready = read_line(one)
assert ready == "READY\n", ready
assert command(one, "ping") == "OK ping\n"
assert command(one, "down") == "OK down\n"
status = command(one, "status")
assert status == "OK status keyed=1 timeout=120\n", status
one.close()
time.sleep(0.2)
two = connect()
assert read_line(two) == "READY\n"
status = command(two, "status")
assert status == "OK status keyed=0 timeout=120\n", status
assert command(two, "ping") == "OK ping\n"
two.close()
print("PASS: host helper reconnects, answers ping, and unkeys on disconnect")
PY
