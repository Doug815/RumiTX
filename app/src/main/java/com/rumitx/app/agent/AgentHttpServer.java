package com.rumitx.app.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local HTTP API. Binds loopback unless the owner opts into extra interfaces. No wildcard bind. */
public final class AgentHttpServer {
    private static final int JSON_LIMIT = 8192;
    private final CommandLayer layer;
    private final BearerAuth auth;
    private final ThreadPoolExecutor pool;
    private final List<ServerSocket> sockets = new ArrayList<ServerSocket>();
    private final List<ServerSocket> extras = new ArrayList<ServerSocket>();
    private volatile boolean running;

    public AgentHttpServer(CommandLayer layer, BearerAuth auth) {
        this.layer = layer;
        this.auth = auth;
        this.pool = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16),
                new java.util.concurrent.ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "RumiTX-agent-http");
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized int start(int port) throws Exception {
        return bind("127.0.0.1", port, false);
    }

    public synchronized int bind(String host, int port, boolean extra) throws Exception {
        if (host == null || host.length() == 0 || "0.0.0.0".equals(host) || "::".equals(host) || "*".equals(host)) {
            throw new IllegalArgumentException("refusing wildcard bind");
        }
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(host, port), 16);
        sockets.add(server);
        if (extra) extras.add(server);
        running = true;
        final ServerSocket listen = server;
        Thread accept = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(listen); }
        }, "RumiTX-agent-accept-" + server.getLocalPort());
        accept.setDaemon(true);
        accept.start();
        return server.getLocalPort();
    }

    public synchronized void setExtraHosts(List<String> hosts) throws Exception {
        for (int i = 0; i < extras.size(); i++) {
            ServerSocket server = extras.get(i);
            sockets.remove(server);
            try { server.close(); } catch (Exception ignored) { /* already closing */ }
        }
        extras.clear();
        if (hosts == null) return;
        for (int i = 0; i < hosts.size(); i++) bind(hosts.get(i), AgentInfo.PORT, true);
    }

    public synchronized void stop() {
        running = false;
        for (int i = 0; i < sockets.size(); i++) {
            try { sockets.get(i).close(); } catch (Exception ignored) { /* shutting down */ }
        }
        sockets.clear();
        extras.clear();
        pool.shutdownNow();
        layer.forceUnkey("server stop");
    }

    private void acceptLoop(ServerSocket server) {
        while (running) {
            try {
                final Socket socket = server.accept();
                socket.setSoTimeout(8000);
                try {
                    pool.execute(new Runnable() {
                        @Override public void run() { handle(socket); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    try { socket.close(); } catch (Exception ignored) { /* queue is full */ }
                }
            } catch (Exception e) {
                if (running) continue;
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            Req req = read(in);
            if (req == null) {
                write(socket, 400, Json.error("bad request"));
                return;
            }
            route(socket, in, req);
        } catch (Exception e) {
            try { write(socket, 500, Json.error("internal error")); }
            catch (Exception ignored) { /* client gone */ }
        } finally {
            try { socket.close(); } catch (Exception ignored) { /* closed */ }
        }
    }

    private void route(Socket socket, InputStream in, Req req) throws Exception {
        String path = req.path;
        boolean commandRoute = commandPath(req.method, path);
        BearerAuth.Session session = auth.authorize(req.header("authorization"));
        if (session == null) {
            write(socket, 401, Json.error("unauthorized"));
            return;
        }
        CommandLayer.Actor actor = session.command
                ? CommandLayer.Actor.command(session.tokenId)
                : CommandLayer.Actor.read(session.tokenId);
        if (commandRoute && !session.command && !unkeyPath(req.method, path)) {
            write(socket, 403, Json.error("command token required"));
            return;
        }
        int limit = queryInt(req.query, "limit", 100);
        if ("GET".equals(req.method) && "/status".equals(path)) {
            write(socket, 200, layer.statusBody());
        } else if ("GET".equals(req.method) && "/channels".equals(path)) {
            write(socket, 200, layer.channelsBody());
        } else if ("GET".equals(req.method) && "/logs".equals(path)) {
            CommandLayer.Outcome out = layer.logs(limit);
            write(socket, out.status, out.body);
        } else if ("GET".equals(req.method) && "/tx/audit".equals(path)) {
            CommandLayer.Outcome out = layer.audit(limit);
            write(socket, out.status, out.body);
        } else if ("GET".equals(req.method) && "/events".equals(path)) {
            events(socket, req);
        } else if ("POST".equals(req.method) && "/infer".equals(path)) {
            Json.Value body = jsonBody(req);
            CommandLayer.Outcome out = layer.infer(body.str("utterance", ""));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/chat".equals(path)) {
            Json.Value body = jsonBody(req);
            String message = body.str("message", body.str("utterance", ""));
            CommandLayer.Outcome out = layer.chat(message);
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/command".equals(path)) {
            CommandLayer.Outcome out = layer.command(jsonBody(req), actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/tool".equals(path)) {
            ToolCall call = ToolCall.parse(bodyText(req));
            if (call == null) {
                write(socket, 400, Json.error("tool JSON required"));
                return;
            }
            CommandLayer.Outcome out = layer.tool(call, actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/channels/program".equals(path)) {
            Json.Value body = jsonBody(req);
            CommandLayer.Outcome out = layer.command(wrap("program_channel", body), actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/channels/activate".equals(path)) {
            CommandLayer.Outcome out = layer.command(wrap("set_channel", jsonBody(req)), actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/volume".equals(path)) {
            CommandLayer.Outcome out = layer.command(wrap("set_volume", jsonBody(req)), actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/scan/start".equals(path)) {
            CommandLayer.Outcome out = layer.command(wrap("scan_start", jsonBody(req)), actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/scan/stop".equals(path)) {
            CommandLayer.Outcome out = layer.command(wrap("scan_stop", jsonBody(req)), actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/tx/key".equals(path)) {
            Json.Value body = req.body.length == 0 ? Json.parse("{}") : jsonBody(req);
            boolean hold = body.bool("hold", false);
            CommandLayer.Outcome out = layer.key(actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
            if (hold && out.status == 200) holdUntilClose(socket, in, actor);
        } else if ("POST".equals(req.method) && ("/tx/unkey".equals(path) || "/tx/stop".equals(path))) {
            CommandLayer.Outcome out = layer.unkey(actor, "stop");
            write(socket, out.status, out.body);
        } else if ("POST".equals(req.method) && "/tx/audio".equals(path)) {
            byte[] wav = req.body;
            Json.Value args = Json.parse("{}");
            String type = req.header("content-type");
            if (type == null || type.indexOf("application/json") >= 0) {
                args = jsonBody(req);
                wav = null;
                String b64 = args.str("wav_base64", "");
                if (!b64.isEmpty()) {
                    try { wav = java.util.Base64.getDecoder().decode(b64); }
                    catch (IllegalArgumentException bad) { wav = null; }
                }
            }
            CommandLayer.Outcome out = layer.audio(wav, args, actor, req.header("idempotency-key"));
            write(socket, out.status, out.body);
        } else {
            write(socket, 404, Json.error("not found"));
        }
    }

    private void holdUntilClose(Socket socket, InputStream in, CommandLayer.Actor actor) {
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(125000);
            while (running && in.read() != -1) { /* client keeps the key until it drops */ }
        } catch (SocketTimeoutException ignored) {
            // Max hold elapsed.
        } catch (Exception ignored) {
            // Client disconnected.
        }
        layer.unkey(actor, "disconnect");
    }

    private void events(Socket socket, Req req) throws Exception {
        OutputStream out = socket.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        socket.setSoTimeout(0);
        String previous = "";
        long beat = 0;
        boolean release = "1".equals(queryParam(req.query, "release"));
        try {
            while (running && !socket.isClosed()) {
                String data = layer.statusBody();
                long now = System.currentTimeMillis();
                if (!data.equals(previous) || now - beat > 15000) {
                    String frame = "event: radio\ndata: " + data + "\n\n";
                    out.write(frame.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    previous = data;
                    beat = now;
                }
                Thread.sleep(400);
            }
        } catch (Exception ignored) {
            // Subscriber gone.
        } finally {
            if (release) layer.unkey(CommandLayer.Actor.watchdog("sse"), "sse disconnect");
        }
    }

    private static boolean commandPath(String method, String path) {
        if (!"POST".equals(method)) return false;
        return "/command".equals(path) || "/tool".equals(path) || "/channels/program".equals(path)
                || "/channels/activate".equals(path) || "/volume".equals(path)
                || "/scan/start".equals(path) || "/scan/stop".equals(path)
                || "/tx/key".equals(path) || "/tx/audio".equals(path)
                || "/tx/unkey".equals(path) || "/tx/stop".equals(path);
    }

    private static boolean unkeyPath(String method, String path) {
        return "POST".equals(method) && ("/tx/unkey".equals(path) || "/tx/stop".equals(path));
    }

    private static Json.Value wrap(String intent, Json.Value args) {
        String raw = new Json.Obj().put("intent", intent).raw("args", args == null ? "{}" : Json.emit(args)).toString();
        return Json.parse(raw);
    }

    private static Json.Value jsonBody(Req req) {
        String text = bodyText(req);
        if (text.isEmpty()) return Json.parse("{}");
        return Json.parse(text);
    }

    private static String bodyText(Req req) {
        if (req.body == null || req.body.length == 0) return "";
        return new String(req.body, StandardCharsets.UTF_8);
    }

    private static void write(Socket socket, int status, String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 409 ? "Conflict" : "Error";
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\nCache-Control: no-store\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    private static Req read(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (head.size() < 8192 && matched < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            char c = (char) b;
            if ((matched == 0 || matched == 2) && c == '\r') matched++;
            else if ((matched == 1 || matched == 3) && c == '\n') matched++;
            else matched = c == '\r' ? 1 : 0;
        }
        if (matched < 4) return null;
        String headerText = new String(head.toByteArray(), StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) return null;
        String[] start = lines[0].split(" ");
        if (start.length < 2) return null;
        Req req = new Req();
        req.method = start[0];
        String rawPath = start[1];
        int q = rawPath.indexOf('?');
        req.path = q < 0 ? rawPath : rawPath.substring(0, q);
        req.query = q < 0 ? "" : rawPath.substring(q + 1);
        if (req.path.indexOf("..") >= 0) return null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) req.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
        }
        int length = 0;
        if (req.headers.containsKey("content-length")) length = Integer.parseInt(req.headers.get("content-length"));
        int max = "/tx/audio".equals(req.path) ? WavCheck.MAX_BYTES : JSON_LIMIT;
        if (length < 0 || length > max) throw new IllegalArgumentException("body too large");
        if ("POST".equals(req.method) && !req.headers.containsKey("content-length")) throw new IllegalArgumentException("content-length required");
        byte[] body = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(body, off, length - off);
            if (n < 0) throw new IllegalArgumentException("truncated body");
            off += n;
        }
        req.body = body;
        return req;
    }

    private static int queryInt(String query, String key, int fallback) {
        String value = queryParam(query, key);
        if (value == null) return fallback;
        try {
            int n = Integer.parseInt(value);
            if (n < 1) return 1;
            return Math.min(n, 400);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.length() == 0) return null;
        String[] parts = query.split("&");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0 && parts[i].substring(0, eq).equals(key)) return parts[i].substring(eq + 1);
        }
        return null;
    }

    private static final class Req {
        String method;
        String path;
        String query = "";
        final Map<String, String> headers = new HashMap<String, String>();
        byte[] body = new byte[0];

        String header(String name) { return headers.get(name); }
    }
}
