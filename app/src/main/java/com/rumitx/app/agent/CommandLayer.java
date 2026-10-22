package com.rumitx.app.agent;

import com.rumitx.app.AiTempChannel;
import com.rumitx.app.RadioIntentParser;
import com.rumitx.app.RumiChatRouter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One command path for the UI, the local API, and on-device model tool calls.
 * Transmit methods all go through {@link TxPolicy}. Programming a channel does not key the radio.
 */
public final class CommandLayer {
    public interface Clock { long now(); }

    public interface Scheduler {
        void schedule(long delayMs, Runnable task);
        void cancel();
    }

    public interface Job { Outcome run(); }

    public static final class Actor {
        public final String who;
        public final String tokenId;
        public final boolean command;

        public Actor(String who, String tokenId, boolean command) {
            this.who = who;
            this.tokenId = tokenId;
            this.command = command;
        }

        public static Actor read(String tokenId) { return new Actor("api", tokenId, false); }
        public static Actor command(String tokenId) { return new Actor("api", tokenId, true); }
        public static Actor model() { return new Actor("model", "model", true); }
        public static Actor device() { return new Actor("ui", "device", true); }
        public static Actor watchdog(String tokenId) { return new Actor("watchdog", tokenId, true); }
    }

    public static final class Outcome {
        public final int status;
        public final String body;
        public final String plain;
        public final boolean executed;

        Outcome(int status, String body, String plain, boolean executed) {
            this.status = status;
            this.body = body;
            this.plain = plain;
            this.executed = executed;
        }
    }

    private enum TxState { IDLE, KEYED }

    private final RadioPort port;
    private final TxPolicy policy;
    private final RotatingLog events;
    private final RotatingLog audit;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Object commandLock = new Object();
    private final Object stateLock = new Object();
    private final LinkedHashMap<String, Outcome> idempotency = new LinkedHashMap<String, Outcome>(32, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Outcome> eldest) { return size() > 64; }
    };
    private TxState txState = TxState.IDLE;
    private Actor keyActor = Actor.device();
    private double keyTx;
    private double keyRx;
    private long keyStartedMs;

    public CommandLayer(RadioPort port, TxPolicy policy, File dir, Scheduler scheduler, Clock clock) {
        this.port = port;
        this.policy = policy;
        this.scheduler = scheduler;
        this.clock = clock;
        this.events = new RotatingLog(new File(dir, "agent.log"), 64 * 1024, 3, false);
        this.audit = new RotatingLog(new File(dir, "tx-audit.log"), 64 * 1024, 4, true);
    }

    public static Scheduler executorScheduler() { return new ExecutorScheduler(); }

    public String statusBody() {
        RadioPort.Status s = port.status();
        String mode = !s.connected ? "disconnected"
                : s.keyed ? "tx"
                : s.scanning ? "scan"
                : (s.receiving || s.squelchOpen) ? "rx"
                : "idle";
        Json.Obj radio = new Json.Obj()
                .put("mode", mode)
                .put("connected", s.connected)
                .put("keyed", s.keyed || txState == TxState.KEYED)
                .put("receiving", s.receiving)
                .put("squelch_open", s.squelchOpen || s.receiving)
                .put("scanning", s.scanning)
                .put("helper", s.helper == null ? "" : s.helper)
                .put("tx_enabled", policy.enabled())
                .put("max_key_seconds", policy.maxKeySeconds())
                .put("allowlist_count", policy.allowlist().size())
                .put("volume", s.volume)
                .raw("rssi", s.rssi == null ? "null" : String.format(Locale.US, "%.1f", s.rssi.doubleValue()))
                .raw("battery_pct", s.batteryPercent < 0 ? "null" : Integer.toString(s.batteryPercent))
                .raw("channel", s.channel == null ? "null" : s.channel.json());
        return new Json.Obj()
                .put("ok", true)
                .put("service", AgentInfo.SERVICE)
                .put("version", AgentInfo.VERSION)
                .put("llm", s.modelLoaded ? "on-device" : "not-loaded")
                .put("radio", radio)
                .toString();
    }

    public String channelsBody() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"channels\":[");
        List<RadioPort.ChannelRef> list = port.channels();
        int n = Math.min(list.size(), 200);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i).json());
        }
        sb.append("]}");
        return sb.toString();
    }

    public Outcome logs(int limit) {
        List<String> lines = new ArrayList<String>();
        lines.addAll(events.tail(limit));
        List<String> radio = port.logs();
        if (radio != null) {
            int start = Math.max(0, radio.size() - limit);
            for (int i = start; i < radio.size(); i++) lines.add(radio.get(i));
        }
        if (lines.size() > limit) lines = lines.subList(lines.size() - limit, lines.size());
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.quote(lines.get(i)));
        }
        sb.append("]}");
        return ok(200, sb.toString(), "log lines " + lines.size(), false);
    }

    public Outcome audit(int limit) {
        List<String> lines = audit.tail(limit);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"audit\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(lines.get(i));
        }
        sb.append("]}");
        return ok(200, sb.toString(), "audit lines " + lines.size(), false);
    }

    public Outcome infer(String utterance) {
        if (utterance == null || utterance.trim().isEmpty()) return err(400, "utterance is required", false);
        if (utterance.length() > 500) return err(400, "utterance exceeds 500 characters", false);
        RadioIntentParser.Intent intent = RadioIntentParser.parse(utterance);
        String name;
        String args;
        boolean confirm = false;
        if (intent.type == RadioIntentParser.Type.CHANNEL_NUMBER) {
            name = "set_channel";
            args = new Json.Obj().put("channel", intent.channelNumber).toString();
        } else if (intent.type == RadioIntentParser.Type.CHANNEL_NAME) {
            name = "set_channel";
            args = new Json.Obj().put("name", intent.channelName).toString();
        } else if (intent.type == RadioIntentParser.Type.FREQUENCY) {
            name = "set_frequency";
            confirm = true;
            Json.Obj a = new Json.Obj().put("rx", intent.frequencyMHz).put("tx", intent.txFrequencyMHz);
            if (intent.plHz != 0) a.put("pl", intent.plHz);
            if (intent.dcsCode != null) a.put("dcs", intent.dcsCode);
            args = a.toString();
        } else if (intent.type == RadioIntentParser.Type.UNSUPPORTED) {
            name = "unsupported";
            args = new Json.Obj().put("error", intent.error == null ? "unsupported" : intent.error).toString();
        } else {
            name = "chat";
            args = "{}";
        }
        String body = new Json.Obj()
                .put("ok", true)
                .raw("action", new Json.Obj().put("intent", name).raw("args", args).toString())
                .put("confirmation_required", confirm)
                .put("executed", false)
                .toString();
        return ok(200, body, "interpreted " + name + " (not executed)", false);
    }

    public Outcome command(Json.Value body, Actor actor, String idempotencyKey) {
        if (body == null || !body.isObject()) return err(400, "JSON object required", false);
        if (Json.hasSecret(body)) return err(400, "encryption fields are not accepted", false);
        final String intent = body.str("intent", "");
        final Json.Value args = body.obj("args") == null ? Json.parse("{}") : body.obj("args");
        if (args != null && Json.hasSecret(args)) return err(400, "encryption fields are not accepted", false);
        return once(idempotencyKey, new Job() {
            @Override public Outcome run() { return dispatchIntent(intent, args, actor); }
        });
    }

    public Outcome tool(ToolCall call, Actor actor, String idempotencyKey) {
        if (call == null) return err(400, "tool call required", false);
        if (call.error != null) return err(400, call.error, false);
        final ToolCall found = call;
        return once(idempotencyKey, new Job() {
            @Override public Outcome run() { return dispatchTool(found.tool, found.args, actor); }
        });
    }

    public Outcome chat(String message) {
        if (message == null || message.trim().isEmpty()) return err(400, "message is required", false);
        if (message.length() > 500) return err(400, "message exceeds 500 characters", false);
        if (ToolCall.parse(message) != null) {
            return ok(200, new Json.Obj().put("ok", true)
                    .put("answer", "Chat does not execute tool calls. Use the tool endpoint with a command token.")
                    .toString(), "chat refused a tool call", false);
        }
        String greeting = RumiChatRouter.greeting(message);
        String answer = greeting != null ? greeting : port.complete(message);
        if (answer == null) answer = "";
        if (answer.length() > 3000) answer = answer.substring(0, 3000);
        events.append("chat chars=" + message.length());
        return ok(200, new Json.Obj().put("ok", true).put("answer", answer).put("response", answer).toString(), answer, false);
    }

    public Outcome key(Actor actor) { return key(actor, null); }

    public Outcome key(Actor actor, String idempotencyKey) {
        return once(idempotencyKey, new Job() {
            @Override public Outcome run() { return keyNow(actor); }
        });
    }

    public Outcome unkey(Actor actor, String reason) {
        return unkeyNow(actor, reason == null ? "unkey" : reason);
    }

    public void forceUnkey(String reason) {
        unkeyNow(Actor.watchdog("service"), reason == null ? "stop" : reason);
    }

    public Outcome audio(byte[] wav, Json.Value args, Actor actor, String idempotencyKey) {
        final byte[] payload = wav;
        final Json.Value opts = args == null ? Json.parse("{}") : args;
        return once(idempotencyKey, new Job() {
            @Override public Outcome run() { return audioNow(payload, opts, actor); }
        });
    }

    private Outcome dispatchIntent(String intent, Json.Value args, Actor actor) {
        if ("set_channel".equals(intent)) return activate(args, actor);
        if ("set_frequency".equals(intent) || "program_channel".equals(intent)) return program(args, actor, false);
        if ("set_volume".equals(intent)) return volume(args, actor);
        if ("scan_start".equals(intent)) return scan(args, actor, true);
        if ("scan_stop".equals(intent)) return scan(args, actor, false);
        if ("key_ptt".equals(intent)) return keyNow(actor);
        if ("unkey_ptt".equals(intent)) return unkeyNow(actor, "command");
        if ("compose_dmr_text".equals(intent)) {
            String text = args.str("text", args.str("message", ""));
            events.append("stage dmr text chars=" + text.length());
            String body = new Json.Obj().put("ok", true).put("executed", false)
                    .raw("result", new Json.Obj().put("state", "staged").put("detail", "DMR text was not sent").toString())
                    .toString();
            return ok(200, body, "DMR text staged, not sent", false);
        }
        return err(400, "unsupported intent", false);
    }

    private Outcome dispatchTool(String tool, Json.Value args, Actor actor) {
        if (args != null && Json.hasSecret(args)) return err(400, "encryption fields are not accepted", false);
        if ("status".equals(tool)) return ok(200, statusBody(), "status", false);
        if ("channels".equals(tool)) return ok(200, channelsBody(), "channels", false);
        if ("logs".equals(tool)) return logs(100);
        if ("tx_audit".equals(tool)) return audit(100);
        if ("program_channel".equals(tool)) return program(args, actor, true);
        if ("activate_channel".equals(tool)) return activate(args, actor);
        if ("set_volume".equals(tool)) return volume(args, actor);
        if ("scan_start".equals(tool)) return scan(args, actor, true);
        if ("scan_stop".equals(tool)) return scan(args, actor, false);
        if ("key_ptt".equals(tool)) return keyNow(actor);
        if ("unkey_ptt".equals(tool)) return unkeyNow(actor, "tool");
        if ("tx_audio".equals(tool)) return audioNow(decodeWav(args), args, actor);
        if ("chat".equals(tool)) return chat(args.str("message", ""));
        return err(400, "unknown tool", false);
    }

    private Outcome program(Json.Value args, Actor actor, boolean fromTool) {
        if (!actor.command) return err(403, "command token required", false);
        synchronized (stateLock) {
            if (txState == TxState.KEYED) return err(409, "unkey before programming", false);
        }
        if (port.status().keyed) return err(409, "unkey before programming", false);
        RadioPort.ChannelSpec spec = new RadioPort.ChannelSpec();
        spec.name = args.str("name", fromTool ? "Agent" : "Remote");
        if (spec.name.length() > 40) return err(400, "channel name is too long", false);
        spec.rxMhz = firstNum(args, -1, "rx_mhz", "rx", "frequency");
        spec.txMhz = args.has("tx_mhz") || args.has("tx") ? firstNum(args, spec.rxMhz, "tx_mhz", "tx") : spec.rxMhz;
        spec.digital = args.bool("digital", false);
        spec.colorCode = (int) args.num("color_code", args.num("cc", 1));
        spec.talkgroup = (int) args.num("talkgroup", args.num("tg", 1));
        spec.timeslot = (int) args.num("timeslot", 1);
        spec.rxCtcss = args.num("rx_ctcss", args.num("pl", 0));
        spec.txCtcss = args.num("tx_ctcss", 0);
        spec.rxDcs = emptyToNull(args.str("rx_dcs", args.str("dcs", "")));
        spec.txDcs = emptyToNull(args.str("tx_dcs", ""));
        spec.squelch = (int) args.num("squelch", AiTempChannel.LIVE_SQUELCH);
        spec.bandwidthKhz = (int) args.num("bandwidth_khz", 12);
        spec.save = args.bool("save", false);
        String problem = freqProblem(spec.rxMhz);
        if (problem != null) return err(400, "RX " + problem, false);
        problem = freqProblem(spec.txMhz);
        if (problem != null) return err(400, "TX " + problem, false);
        if (spec.squelch < 0 || spec.squelch > 9) return err(400, "squelch must be 0..9", false);
        boolean ok;
        try { ok = port.program(spec); }
        catch (RuntimeException e) { return err(400, safe(e.getMessage()), false); }
        events.append("program " + TxFrequencyPolicy.mhz(spec.rxMhz) + " ok=" + ok);
        if (!ok) return result(502, false, "failed", "The radio did not acknowledge programming");
        return result(200, true, "applied", "Programmed " + TxFrequencyPolicy.mhz(spec.rxMhz) + " MHz. No transmission started.");
    }

    private Outcome activate(Json.Value args, Actor actor) {
        if (!actor.command) return err(403, "command token required", false);
        synchronized (stateLock) {
            if (txState == TxState.KEYED) return err(409, "unkey before changing channels", false);
        }
        RadioPort.ChannelRef ref = resolve(args);
        if (ref == null) return err(404, "channel not found", false);
        boolean ok;
        try { ok = port.activate(ref); }
        catch (RuntimeException e) { return err(400, safe(e.getMessage()), false); }
        events.append("activate " + ref.id + " " + TxFrequencyPolicy.mhz(ref.txMhz) + " ok=" + ok);
        if (!ok) return result(502, false, "failed", "Channel was not programmed");
        return result(200, true, "applied", "Activated " + ref.name + " at " + TxFrequencyPolicy.mhz(ref.rxMhz) + " MHz. No transmission started.");
    }

    private Outcome volume(Json.Value args, Actor actor) {
        if (!actor.command) return err(403, "command token required", false);
        int level = (int) args.num("level", -1);
        if (level < 0 || level > 10) return err(400, "volume must be 0..10", false);
        boolean ok = port.setVolume(level);
        events.append("volume " + level + " ok=" + ok);
        if (!ok) return result(502, false, "failed", "Volume was not applied");
        return result(200, true, "applied", "Volume set to " + level);
    }

    private Outcome scan(Json.Value args, Actor actor, boolean start) {
        if (!actor.command) return err(403, "command token required", false);
        if (!start) {
            port.scanStop();
            events.append("scan stop");
            return result(200, true, "applied", "Scan stopped");
        }
        synchronized (stateLock) {
            if (txState == TxState.KEYED) return err(409, "unkey before scanning", false);
        }
        double from = firstNum(args, -1, "from_mhz", "from");
        double to = firstNum(args, -1, "to_mhz", "to");
        double step = args.num("step_mhz", 0.0125);
        String problem = freqProblem(from);
        if (problem != null) return err(400, problem, false);
        problem = freqProblem(to);
        if (problem != null) return err(400, problem, false);
        if (to < from) return err(400, "scan end is below the start", false);
        if (to - from > 2.0) return err(400, "scan span is limited to 2 MHz", false);
        if (step < 0.005 || step > 1.0) return err(400, "scan step must be 0.005..1 MHz", false);
        boolean ok = port.scanStart(from, to, step);
        events.append("scan " + TxFrequencyPolicy.mhz(from) + ".." + TxFrequencyPolicy.mhz(to));
        if (!ok) return result(502, false, "failed", "Scan did not start");
        return result(200, true, "applied", "Scanning. This does not key the transmitter.");
    }

    private Outcome keyNow(Actor actor) {
        if (!actor.command) return err(403, "command token required", false);
        RadioPort.ChannelRef channel;
        synchronized (stateLock) {
            if (txState == TxState.KEYED) return err(409, "already transmitting", false);
            channel = port.current();
            if (channel == null) return err(409, "no channel selected", false);
            TxPolicy.Decision decision = policy.evaluate(channel.txMhz, channel.rxMhz, true, clock.now());
            if (!decision.allowed) {
                audit(actor, "deny", channel.txMhz, channel.rxMhz, 0, decision.reason);
                events.append("tx deny " + decision.reason);
                return err(403, decision.reason, false);
            }
            txState = TxState.KEYED;
            keyActor = actor;
            keyTx = channel.txMhz;
            keyRx = channel.rxMhz;
            keyStartedMs = clock.now();
        }
        boolean keyed = false;
        String failure = "PTT key was not acknowledged";
        try {
            keyed = port.keyDown();
        } catch (RuntimeException e) {
            failure = safe(e.getMessage());
        }
        if (!keyed) {
            try { port.keyUp(); } catch (RuntimeException ignored) { /* still clear local state */ }
            synchronized (stateLock) { txState = TxState.IDLE; }
            policy.markIdle(clock.now());
            audit(actor, "fail", channel.txMhz, channel.rxMhz, 0, failure);
            return err(502, failure, false);
        }
        final Actor owner = actor;
        policy.markKeyed(clock.now());
        audit(actor, "start", channel.txMhz, channel.rxMhz, 0, "key");
        events.append("tx start " + TxFrequencyPolicy.mhz(channel.txMhz) + " token=" + actor.tokenId);
        scheduler.schedule(policy.maxKeyMillis(), new Runnable() {
            @Override public void run() { unkeyNow(Actor.watchdog(owner.tokenId), "timeout"); }
        });
        String plain = "Transmitter keyed on " + TxFrequencyPolicy.mhz(channel.txMhz)
                + " MHz. It will unkey within " + policy.maxKeySeconds() + " s.";
        return result(200, true, "keyed", plain);
    }

    private Outcome unkeyNow(Actor actor, String reason) {
        boolean wasKeyed;
        double tx;
        double rx;
        long started;
        Actor owner;
        synchronized (stateLock) {
            wasKeyed = txState == TxState.KEYED;
            tx = keyTx;
            rx = keyRx;
            started = keyStartedMs;
            owner = keyActor;
            txState = TxState.IDLE;
        }
        scheduler.cancel();
        boolean ack = false;
        try { ack = port.keyUp(); }
        catch (RuntimeException ignored) { ack = false; }
        long duration = wasKeyed ? Math.max(0, clock.now() - started) : 0;
        policy.markIdle(clock.now());
        if (wasKeyed || ack) {
            audit(owner == null ? actor : owner, "stop", tx, rx, duration, reason + " ack=" + ack);
            events.append("tx stop " + reason + " ms=" + duration);
        }
        String state = wasKeyed ? "unkeyed" : "already_unkeyed";
        String body = new Json.Obj().put("ok", true).put("executed", true)
                .raw("result", new Json.Obj().put("state", state).put("radio_ack", ack)
                        .put("duration_ms", duration).put("detail", reason).toString())
                .toString();
        return ok(200, body, "Unkey " + reason + (ack ? " acknowledged" : " attempted"), true);
    }

    private Outcome audioNow(byte[] wav, Json.Value args, Actor actor) {
        if (!actor.command) return err(403, "command token required", false);
        if (wav == null) wav = decodeWav(args);
        if (wav == null) return err(400, "WAV payload is required", false);
        int duration;
        try { duration = WavCheck.durationMs(wav); }
        catch (RuntimeException e) { return err(400, safe(e.getMessage()), false); }
        if (duration > policy.maxKeySeconds() * 1000) {
            return err(400, "audio is longer than max key time (" + policy.maxKeySeconds() + " s)", false);
        }
        RadioPort.ChannelRef channel;
        synchronized (stateLock) {
            if (txState == TxState.KEYED) return err(409, "already transmitting", false);
            channel = resolve(args);
            if (channel == null) channel = port.current();
            if (channel == null) return err(409, "no channel selected", false);
            TxPolicy.Decision decision = policy.evaluate(channel.txMhz, channel.rxMhz, true, clock.now());
            if (!decision.allowed) {
                audit(actor, "deny", channel.txMhz, channel.rxMhz, 0, decision.reason);
                return err(403, decision.reason, false);
            }
            txState = TxState.KEYED;
            keyActor = actor;
            keyTx = channel.txMhz;
            keyRx = channel.rxMhz;
            keyStartedMs = clock.now();
        }
        policy.markKeyed(clock.now());
        audit(actor, "start", channel.txMhz, channel.rxMhz, 0, "audio");
        final Actor owner = actor;
        scheduler.schedule(policy.maxKeyMillis(), new Runnable() {
            @Override public void run() {
                try { port.keyUp(); } catch (RuntimeException ignored) { /* bridge also unkeys */ }
            }
        });
        String message;
        boolean ok = false;
        try {
            message = port.playAudio(wav, channel);
            ok = true;
        } catch (RuntimeException e) {
            message = safe(e.getMessage());
        } finally {
            scheduler.cancel();
            try { port.keyUp(); } catch (RuntimeException ignored) { /* already unkeyed by the bridge */ }
            synchronized (stateLock) { txState = TxState.IDLE; }
            policy.markIdle(clock.now());
        }
        long elapsed = Math.max(0, clock.now() - keyStartedMs);
        audit(owner, "stop", channel.txMhz, channel.rxMhz, elapsed, ok ? "audio" : message);
        events.append("tx audio " + TxFrequencyPolicy.mhz(channel.txMhz) + " ok=" + ok);
        if (!ok) return err(502, message, false);
        return result(200, true, "played", message == null ? "Audio finished and PTT was released" : message);
    }

    private RadioPort.ChannelRef resolve(Json.Value args) {
        if (args == null) return null;
        if (args.has("id")) return port.findById((long) args.num("id", -1));
        if (args.has("channel") || args.has("index")) {
            int index = (int) args.num("channel", args.num("index", -1));
            return port.findByIndex(index);
        }
        if (args.has("name")) return port.findByName(args.str("name", ""));
        if (args.has("channel_id")) return port.findById((long) args.num("channel_id", -1));
        return null;
    }

    private static byte[] decodeWav(Json.Value args) {
        if (args == null) return null;
        String b64 = args.str("wav_base64", "");
        if (b64.isEmpty()) return null;
        try { return java.util.Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException e) { return null; }
    }

    private Outcome once(String key, Job job) {
        if (key != null && (key.length() > 80 || key.indexOf('\n') >= 0)) return err(400, "bad idempotency key", false);
        synchronized (commandLock) {
            if (key != null && !key.isEmpty()) {
                Outcome previous = idempotency.get(key);
                if (previous != null) return previous;
            }
            Outcome outcome = job.run();
            if (key != null && !key.isEmpty()) idempotency.put(key, outcome);
            return outcome;
        }
    }

    private void audit(Actor actor, String event, double tx, double rx, long durationMs, String reason) {
        String who = actor == null ? "unknown" : actor.who;
        String token = actor == null ? "" : actor.tokenId;
        audit.append(new Json.Obj()
                .put("ts", clock.now())
                .put("event", event)
                .put("actor", who)
                .put("token", token == null ? "" : token)
                .put("tx_mhz", TxFrequencyPolicy.mhz(tx))
                .put("rx_mhz", TxFrequencyPolicy.mhz(rx))
                .put("duration_ms", durationMs)
                .put("detail", reason == null ? "" : reason)
                .toString());
    }

    private static String freqProblem(double mhz) {
        if (!Double.isFinite(mhz) || mhz < 400.0 || mhz > 470.0) return "frequency must be inside 400–470 MHz";
        return null;
    }

    private static double firstNum(Json.Value args, double fallback, String a, String b) {
        if (args.has(a)) return args.num(a, fallback);
        if (b != null && args.has(b)) return args.num(b, fallback);
        return fallback;
    }

    private static double firstNum(Json.Value args, double fallback, String a, String b, String c) {
        if (args.has(a)) return args.num(a, fallback);
        if (args.has(b)) return args.num(b, fallback);
        if (args.has(c)) return args.num(c, fallback);
        return fallback;
    }

    private static String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String safe(String message) {
        if (message == null || message.isEmpty()) return "request failed";
        String clean = message.replaceAll("[A-Fa-f0-9]{32,}", "[redacted]");
        return clean.length() > 240 ? clean.substring(0, 240) : clean;
    }

    private static Outcome ok(int status, String body, String plain, boolean executed) {
        return new Outcome(status, body, plain, executed);
    }

    private static Outcome err(int status, String message, boolean executed) {
        return new Outcome(status, Json.error(message), message, executed);
    }

    private static Outcome result(int status, boolean executed, String state, String detail) {
        String body = new Json.Obj().put("ok", status < 400).put("executed", executed)
                .raw("result", new Json.Obj().put("state", state).put("detail", detail).toString())
                .toString();
        return ok(status, body, detail, executed);
    }

    /** Daemon scheduler used on the phone. Tests pass their own scheduler. */
    public static final class ExecutorScheduler implements Scheduler {
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "RumiTX-tx-watch");
                t.setDaemon(true);
                return t;
            }
        });
        private ScheduledFuture<?> future;

        @Override public synchronized void schedule(long delayMs, Runnable task) {
            cancel();
            future = exec.schedule(task, Math.max(1, delayMs), TimeUnit.MILLISECONDS);
        }

        @Override public synchronized void cancel() {
            if (future != null) future.cancel(false);
            future = null;
        }

        public void shutdown() { exec.shutdownNow(); }
    }
}
