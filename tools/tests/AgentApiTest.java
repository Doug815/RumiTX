package com.rumitx.app.agent;

import com.rumitx.app.AiTempChannel;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Host-side tests for auth, transmit policy, the command layer, and the local HTTP API. No radio hardware. */
public final class AgentApiTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        tokens();
        policyRails();
        memoryAndWav();
        rotation();
        commandAndHttp();
        if (failures != 0) throw new AssertionError(failures + " assertion(s) failed");
        System.out.println("PASS: agent API auth, transmit policy, command layer, and HTTP");
    }

    private static void tokens() {
        BearerAuth auth = BearerAuth.generate();
        check(BearerAuth.valid(auth.readToken()) && auth.readToken().length() >= 32, "read token length");
        check(!auth.readToken().equals(auth.commandToken()), "tokens differ");
        check(auth.authorize(null) == null, "missing header");
        check(auth.authorize("Bearer nope") == null, "bad token");
        BearerAuth.Session read = auth.authorize("Bearer " + auth.readToken());
        BearerAuth.Session command = auth.authorize("Bearer " + auth.commandToken());
        check(read != null && !read.command, "read session");
        check(command != null && command.command, "command session");
        check(!auth.readToken().equals(read.tokenId), "fingerprint is not the token");
        try {
            auth.replace("short", auth.commandToken());
            check(false, "short token rejected");
        } catch (IllegalArgumentException expected) {
            check(true, "short token rejected");
        }
    }

    private static void policyRails() {
        TxPolicy policy = new TxPolicy();
        check(!policy.enabled(), "tx defaults off");
        check(policy.maxKeySeconds() == 15, "default max key");
        policy.setMaxKeySeconds(999);
        check(policy.maxKeySeconds() == 120, "clamped to helper watchdog");
        policy.setMaxKeySeconds(0);
        check(policy.maxKeySeconds() == 1, "minimum key time");
        policy.setMaxKeySeconds(15);
        long now = 1_000_000L;
        TxPolicy.Decision off = policy.evaluate(446.0, 446.0, true, now);
        check(!off.allowed && off.reason.contains("tx_enabled"), "master switch");
        policy.setEnabled(true);
        TxPolicy.Decision empty = policy.evaluate(446.0, 446.0, true, now);
        check(!empty.allowed && empty.reason.contains("allowlist"), "empty allowlist");
        for (double pd : TxFrequencyPolicy.BELOIT_PD_P25_MHZ) {
            policy.setAllowlist(Collections.singletonList(TxFrequencyPolicy.mhz(pd)));
            TxPolicy.Decision denied = policy.evaluate(pd, pd, true, now);
            check(!denied.allowed && denied.reason.contains("public-safety") && denied.reason.contains("Beloit"),
                    "Beloit deny " + pd);
            check(TxPolicy.allowlistEntryProblem(TxFrequencyPolicy.mhz(pd)) != null, "settings refuse " + pd);
        }
        check(TxFrequencyPolicy.BELOIT_PD_P25_MHZ.length == 7, "seven Beloit channels");
        policy.setAllowlist(Collections.singletonList("461.00000"));
        check(!policy.evaluate(461.0, 461.0, true, now).allowed, "outside licensed service");
        policy.setAllowlist(Collections.singletonList("446.00000"));
        check(policy.evaluate(446.0, 446.0, false, now).reason.contains("command token"), "command token required");
        check(policy.evaluate(446.0, 446.0, true, now).allowed, "ham allowlisted");
        policy.setAllowlist(Collections.singletonList("462.55000"));
        check(policy.evaluate(462.550, 462.550, true, now).allowed, "GMRS allowlisted");
        check(!policy.evaluate(462.551, 462.550, true, now).allowed, "off-channel GMRS stays denied");
        String saved = policy.save();
        TxPolicy loaded = TxPolicy.load("tailscale=true\n" + saved);
        check(loaded.enabled() && loaded.allowlist().contains("462.55000"), "policy round trip");
    }

    private static void memoryAndWav() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) big.append('a');
        String bound = RumiMemory.bound(big.toString());
        check(bound.length() == RumiMemory.MAX_TRANSCRIPT_CHARS, "transcript cap");
        check(RumiMemory.fact("x\n" + repeat('y', 500)).length() == RumiMemory.MAX_FACT_CHARS, "fact cap");
        byte[] wav = WavCheck.silence(100);
        check(WavCheck.durationMs(wav) == 100, "wav duration");
        wav[0] = 'X';
        try {
            WavCheck.durationMs(wav);
            check(false, "bad wav rejected");
        } catch (IllegalArgumentException expected) {
            check(true, "bad wav rejected");
        }
        ChannelCheck.squelch();
    }

    private static void rotation() throws Exception {
        File dir = dir();
        RotatingLog log = new RotatingLog(new File(dir, "t.log"), 1024, 2, true);
        for (int i = 0; i < 80; i++) log.append("line " + i + " " + repeat('z', 40));
        check(new File(dir, "t.log").length() <= 2048, "active log bounded");
        check(log.tail(5).size() == 5, "tail limit");
        check(log.tail(1000).size() <= 80, "tail cannot exceed written lines");
    }

    private static void commandAndHttp() throws Exception {
        Fake radio = new Fake();
        ManualClock clock = new ManualClock();
        ManualScheduler scheduler = new ManualScheduler();
        File dir = dir();
        TxPolicy policy = new TxPolicy();
        CommandLayer layer = new CommandLayer(radio, policy, dir, scheduler, clock);
        CommandLayer.Actor command = CommandLayer.Actor.command("tok-command");
        CommandLayer.Actor read = CommandLayer.Actor.read("tok-read");
        CommandLayer.Outcome denied = layer.key(command);
        check(denied.status == 403 && radio.keys == 0, "default off does not key");
        policy.setEnabled(true);
        policy.setAllowlist(Collections.singletonList("446.00000"));
        CommandLayer.Outcome keyed = layer.key(command);
        check(keyed.status == 200 && radio.keys == 1 && radio.keyed, "allowlisted key");
        check(scheduler.delayMs == 15000, "15 s watchdog armed");
        scheduler.fire();
        check(!radio.keyed && radio.unkeys >= 1, "timeout unkeys");
        clock.now += 3000;
        CommandLayer.Outcome again = layer.key(command, "idem-1");
        check(again.status == 200, "second key");
        int keysAfter = radio.keys;
        CommandLayer.Outcome replay = layer.key(command, "idem-1");
        check(replay == again && radio.keys == keysAfter, "idempotency does not key twice");
        layer.unkey(read, "stop");
        clock.now += 3000;
        radio.tuned.txMhz = 460.050;
        radio.tuned.rxMhz = 460.050;
        policy.setAllowlist(Collections.singletonList("460.05000"));
        int before = radio.keys;
        CommandLayer.Outcome pd = layer.key(command);
        check(pd.status == 403 && radio.keys == before && pd.body.contains("Beloit"), "API refuses Beloit PD");
        radio.tuned.txMhz = 446;
        radio.tuned.rxMhz = 446;
        policy.setAllowlist(Collections.singletonList("446.00000"));
        clock.now += 3000;
        byte[] bad = new byte[]{1, 2, 3};
        check(layer.audio(bad, Json.parse("{}"), command, null).status == 400 && radio.audio == 0, "bad wav skipped");
        byte[] wav = WavCheck.silence(200);
        CommandLayer.Outcome played = layer.audio(wav, Json.parse("{}"), command, null);
        check(played.status == 200 && radio.audio == 1 && !radio.keyed, "mocked audio unkeys");
        ToolCall secret = ToolCall.parse("{\"tool\":\"key_ptt\",\"args\":{\"secret_key\":\"abcd\"}}");
        check(secret != null && secret.error != null, "secret tool rejected");
        int secretMark = radio.keys;
        check(layer.tool(secret, CommandLayer.Actor.model(), null).status == 400 && radio.keys == secretMark,
                "secret tool does not key");
        int keysNow = radio.keys;
        CommandLayer.Outcome inferred = layer.infer("transmit for 5 seconds");
        check(inferred.body.contains("\"executed\":false") && radio.keys == keysNow, "infer never transmits");
        CommandLayer.Outcome hi = layer.chat("hi");
        check(hi.body.contains("Hey there!"), "greeting chat");
        clock.now += 61000;
        for (int i = 0; i < 6; i++) {
            clock.now += 3000;
            radio.tuned.txMhz = 446;
            radio.tuned.rxMhz = 446;
            CommandLayer.Outcome ok = layer.key(CommandLayer.Actor.model());
            check(ok.status == 200, "rate slot " + i);
            layer.unkey(CommandLayer.Actor.model(), "test");
        }
        clock.now += 3000;
        CommandLayer.Outcome limited = layer.key(CommandLayer.Actor.model());
        check(limited.status == 403 && limited.body.contains("rate limit"), "rate limit");

        BearerAuth auth = BearerAuth.generate();
        AgentHttpServer server = new AgentHttpServer(layer, auth);
        int port = server.start(0);
        try {
            try {
                server.bind("0.0.0.0", 9, true);
                check(false, "wildcard bind refused");
            } catch (IllegalArgumentException expected) {
                check(true, "wildcard bind refused");
            }
            HttpResult status = http("GET", port, "/status", auth.readToken(), null);
            check(status.code == 200 && status.body.contains("\"service\":\"rumitx\"")
                    && status.body.contains("\"version\":\"2.4.0-agent-api\"")
                    && status.body.contains("\"mode\"")
                    && !status.body.contains(auth.readToken()), "status shape hides tokens");
            HttpResult forbidden = http("POST", port, "/tx/key", auth.readToken(), "{}");
            check(forbidden.code == 403, "read token cannot key");
            HttpResult missing = http("POST", port, "/tx/allowlist", auth.commandToken(), "{\"allow\":[\"446.00000\"]}");
            check(missing.code == 404, "allowlist is not an API");
            HttpResult stop = http("POST", port, "/tx/stop", auth.readToken(), "{}");
            check(stop.code == 200, "stop endpoint");
            HttpResult audit = http("GET", port, "/tx/audit?limit=20", auth.readToken(), null);
            check(audit.code == 200 && audit.body.contains("\"event\"") && !audit.body.contains(auth.commandToken()),
                    "audit is read-only and token-free");
            String sse = sse(port, auth.readToken());
            check(sse.contains("text/event-stream") && sse.contains("squelch_open"), "rx event stream");
        } finally {
            server.stop();
        }
        check(!radio.keyed, "server stop unkeys");
        File storeDir = dir();
        AgentStore.Loaded loaded = AgentStore.load(storeDir);
        check(!loaded.policy.enabled(), "stored tx defaults off");
        check(loaded.auth.ready(), "tokens created on device");
        String first = loaded.auth.commandToken();
        AgentStore.regenerate(storeDir, loaded.auth);
        AgentStore.Loaded againStore = AgentStore.load(storeDir);
        check(!againStore.auth.commandToken().equals(first), "regenerate replaces tokens");
    }

    private static HttpResult http(String method, int port, String path, String token, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        if (json != null) {
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Idempotency-Key", "test-" + path);
            OutputStream out = conn.getOutputStream();
            out.write(data);
            out.close();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] buf = new byte[8192];
        int n = in == null ? 0 : in.read(buf);
        if (in != null) in.close();
        conn.disconnect();
        return new HttpResult(code, n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8));
    }

    private static String sse(int port, String token) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(2000);
        String req = "GET /events HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token + "\r\n\r\n";
        socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && acc.toString("UTF-8").indexOf("squelch_open") < 0) {
            try {
                int n = socket.getInputStream().read(buf);
                if (n < 0) break;
                acc.write(buf, 0, n);
            } catch (java.net.SocketTimeoutException waited) {
                // Keep reading until the first radio event or the deadline.
            }
        }
        socket.close();
        return new String(acc.toByteArray(), StandardCharsets.UTF_8);
    }

    private static File dir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "rumitx-agent-" + System.nanoTime());
        check(dir.mkdirs(), "tmpdir");
        return dir;
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        Arrays.fill(buf, c);
        return new String(buf);
    }

    private static void check(boolean value, String message) {
        if (!value) {
            failures++;
            System.err.println("FAIL: " + message);
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
    }

    private static final class ManualClock implements CommandLayer.Clock {
        long now = 1_000_000L;
        @Override public long now() { return now; }
    }

    private static final class ManualScheduler implements CommandLayer.Scheduler {
        Runnable task;
        long delayMs;
        @Override public void schedule(long delayMs, Runnable task) { this.delayMs = delayMs; this.task = task; }
        @Override public void cancel() { task = null; }
        void fire() { if (task != null) task.run(); }
    }

    private static final class Fake implements RadioPort {
        final List<RadioPort.ChannelRef> saved = new ArrayList<RadioPort.ChannelRef>();
        RadioPort.ChannelRef tuned = channel(1, "TEST", 446.0, 446.0);
        int keys;
        int unkeys;
        int audio;
        boolean keyed;

        Fake() { saved.add(tuned); }

        @Override public RadioPort.Status status() {
            RadioPort.Status s = new RadioPort.Status();
            s.connected = true;
            s.keyed = keyed;
            s.channel = tuned;
            s.helper = "mock helper";
            s.modelLoaded = false;
            return s;
        }

        @Override public List<RadioPort.ChannelRef> channels() { return saved; }
        @Override public RadioPort.ChannelRef current() { return tuned; }
        @Override public RadioPort.ChannelRef findById(long id) { return tuned != null && tuned.id == id ? tuned : null; }
        @Override public RadioPort.ChannelRef findByIndex(int oneBased) {
            return oneBased >= 1 && oneBased <= saved.size() ? saved.get(oneBased - 1) : null;
        }
        @Override public RadioPort.ChannelRef findByName(String name) {
            return tuned != null && tuned.name.equalsIgnoreCase(name) ? tuned : null;
        }
        @Override public boolean program(RadioPort.ChannelSpec spec) {
            tuned.txMhz = spec.txMhz;
            tuned.rxMhz = spec.rxMhz;
            tuned.squelch = spec.squelch;
            return true;
        }
        @Override public boolean activate(RadioPort.ChannelRef channel) { tuned = channel; return true; }
        @Override public boolean setVolume(int level) { return true; }
        @Override public boolean scanStart(double fromMhz, double toMhz, double stepMhz) { return true; }
        @Override public boolean scanStop() { return true; }
        @Override public boolean keyDown() { keys++; keyed = true; return true; }
        @Override public boolean keyUp() { unkeys++; keyed = false; return true; }
        @Override public String playAudio(byte[] wav, RadioPort.ChannelRef channel) {
            audio++;
            keyed = false;
            return "mocked playback; no RF";
        }
        @Override public String complete(String message) { return "model offline in test"; }
        @Override public List<String> logs() { return Collections.emptyList(); }

        private static RadioPort.ChannelRef channel(long id, String name, double tx, double rx) {
            RadioPort.ChannelRef c = new RadioPort.ChannelRef();
            c.id = id;
            c.index = 1;
            c.name = name;
            c.txMhz = tx;
            c.rxMhz = rx;
            c.squelch = AiTempChannel.LIVE_SQUELCH;
            return c;
        }
    }

    /** Keeps the AI squelch assertion next to the command tests without an Android runtime. */
    private static final class ChannelCheck {
        static void squelch() {
            check(AiTempChannel.LIVE_SQUELCH == 1, "live squelch constant");
        }
    }
}
