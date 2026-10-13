package com.rumitx.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated RumiTX v2.3.1 file-audio transmitter.
 * RadioController remains the ONLY UART / helper client.
 * v1 audio routing is acoustic speaker -> built-in microphone, not PCM injection.
 * Local playback success is NOT proof of on-air audio.
 */
public final class VoiceTxBridge {
    public static final String ACTION = "com.rumitx.app.TX_AUDIO";
    public static final String RESULT_ACTION = "com.rumitx.app.TX_RESULT";
    private static final String TAG = "RumiTX-TX";
    private static final int MAX_TX_MS = 15000;
    // Operator-verified: key first, then allow ONE SECOND for the mic/TX
    // path to stabilize before playing the WAV. No post-playback delay.
    private static final long PRE_PLAYBACK_SETTLE_MS = 1000L;
    // Laboratory-only analog FM PTT with NO MediaPlayer and NO stream mutation.
    // The test is one-shot, exactly four seconds, signed and channel-allowlisted.
    private static final int ANALOG_PTT_ONLY_HOLD_MS = 4000;
    private static final long CLOCK_SKEW_MS = 90000;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Set<String> nonces = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
        r -> new Thread(r, "RumiTX-voice-tx"));
    private final Context app;
    private final RadioController radio;
    private final File root;
    private volatile CountDownLatch completion;
    // Existing HIDL adapter, injected by RadioService; never a second HIDL owner.
    private volatile HidlAdapter hidlRxProbeAdapter;
    private long previousEnd = -100000;

    public VoiceTxBridge(Context context, RadioController controller) {
        app = context.getApplicationContext();
        radio = controller;
        root = new File(app.getFilesDir(), "voice_bridge");
        if (!root.exists() && !root.mkdirs())
            Log.e(TAG, "Cannot create voice bridge directory");
    }

    public static boolean isTransmitting() { return BUSY.get(); }

    /** Same HIDL instance already owned by the radio foreground service. */
    public void attachHidlRxProbe(HidlAdapter existingAdapter) {
        hidlRxProbeAdapter = existingAdapter;
    }

    public BroadcastReceiver receiver() {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION.equals(intent.getAction())) return;
                if (closing.get() || !BUSY.compareAndSet(false, true)) {
                    reply(false, "BUSY or stopping", "", "");
                    return;
                }
                // Broadcasts run on the UI thread. Parsing the allowlist,
                // HMAC/replay ledger and fsync must not block that thread.
                final Intent requestIntent = new Intent(intent);
                try {
                    worker.execute(() -> {
                        try {
                            Request request = authorize(requestIntent);
                            transmit(request);
                        } catch (Exception error) {
                            Log.e(TAG, "TX_AUDIO rejected before keying", error);
                            BUSY.set(false);
                            reply(false, "REJECTED: " + error.getMessage(), "", "");
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    BUSY.set(false);
                    reply(false, "Service stopping", "", "");
                }
            }
        };
    }

    private static String required(Intent intent, String key) {
        String s = intent.getStringExtra(key);
        if (s == null || s.length() == 0 || s.length() > 1024)
            throw new IllegalArgumentException("invalid " + key);
        return s;
    }
    private static String optional(Intent intent, String key, String fallback) {
        String s = intent.getStringExtra(key);
        return s == null ? fallback : s;
    }
    private static double mhz(String value) {
        if (!value.matches("[0-9]{3}\\.[0-9]{1,5}"))
            throw new IllegalArgumentException("invalid frequency format");
        double v = Double.parseDouble(value);
        if (v < 400.0 || v > 470.0)
            throw new IllegalArgumentException("frequency outside 400-470 MHz");
        return v;
    }
    private static byte[] decodeHex(String s) {
        if (s.length() % 2 != 0 || !s.matches("[0-9a-fA-F]+"))
            throw new IllegalArgumentException("invalid hexadecimal string");
        byte[] result = new byte[s.length()/2];
        for (int i = 0; i < result.length; ++i)
            result[i] = (byte)Integer.parseInt(s.substring(2*i,2*i+2), 16);
        return result;
    }
    private static String firstLine(File f) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            return reader.readLine();
        }
    }
    private boolean allowed(String candidate) throws IOException {
        File f = new File(root, "allowed_channels.csv");
        if (!f.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("#") && candidate.equals(line)) return true;
            }
        }
        return false;
    }
    private void rememberNonce(String nonce) throws IOException {
        synchronized (nonces) {
            if (nonces.contains(nonce)) throw new SecurityException("replayed request");
            File file = new File(root, "nonces.txt");
            if (file.isFile()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null)
                        if (nonce.equals(line.trim())) throw new SecurityException("replayed request");
                }
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((nonce + "\n").getBytes(StandardCharsets.US_ASCII));
                out.getFD().sync();
            }
            nonces.add(nonce);
        }
    }
    private Request authorize(Intent i) throws Exception {
        Request r = new Request();
        r.audio = required(i, "audio");
        r.tx = required(i, "tx");
        r.rx = optional(i, "rx", r.tx);
        r.digital = optional(i, "digital", "1");
        r.tone = i.hasExtra("tone") ? required(i, "tone") : null;
        r.toneSource = optional(i, "tone_source", "module");
        if (i.hasExtra("tone_source") && r.tone == null)
            throw new SecurityException("tone_source requires a signed tone");
        for (String field : new String[]{"txCtcssHz", "rxCtcssHz", "txDcsCode", "rxDcsCode", "ctcss", "dcs", "tone_index"})
            if (i.hasExtra(field)) throw new SecurityException("conflicting tone field: " + field + "; use signed tone only");
        if (r.tone != null) {
            if (!"0".equals(r.digital)) throw new SecurityException("CTCSS tone is analog-only");
            if (!r.toneSource.equals("module") && !r.toneSource.equals("software"))
                throw new SecurityException("tone_source must be module or software");
            java.util.regex.Matcher toneMatch = java.util.regex.Pattern.compile("([0-9]{2,3}\\.[0-9])(?: \\(([0-9]{1,2})\\))?").matcher(r.tone);
            if (!toneMatch.matches()) throw new SecurityException("tone must be Hz, e.g. 100.0 or 100.0 (13)");
            r.toneHz = Double.parseDouble(toneMatch.group(1));
            int index = ToneIndex.ctcss(r.toneHz);
            if (index == 0 || (toneMatch.group(2) != null && Integer.parseInt(toneMatch.group(2)) != index))
                throw new SecurityException("CTCSS frequency/module index conflict");
        }
        // Unspecified DMR metadata defaults to 0/0 for analog FM.
        // The values remain part of the existing HMAC V1 signed payload.
        r.cc = optional(i, "cc", "0".equals(r.digital) ? "0" : "1");
        r.tg = optional(i, "tg", "0".equals(r.digital) ? "0" : "99");
        r.issued = required(i, "issued");
        r.nonce = required(i, "nonce").toLowerCase(Locale.US);
        r.sha = required(i, "sha256").toLowerCase(Locale.US);
        String signature = required(i, "mac");
        if (!r.issued.matches("[0-9]{13}") || !r.nonce.matches("[0-9a-f]{32}") ||
                !r.sha.matches("[0-9a-f]{64}") || !signature.matches("[0-9a-fA-F]{64}"))
            throw new SecurityException("invalid request authentication fields");
        if (Math.abs(System.currentTimeMillis() - Long.parseLong(r.issued)) > CLOCK_SKEW_MS)
            throw new SecurityException("expired request");
        r.txMHz = mhz(r.tx);
        r.rxMHz = mhz(r.rx);
        if (!r.digital.equals("0") && !r.digital.equals("1"))
            throw new SecurityException("digital must be 0 (analog FM) or 1 (DMR)");
        r.colorCode = Integer.parseInt(r.cc);
        r.talkgroup = Integer.parseInt(r.tg);
        if (r.digital.equals("1")) {
            if (r.colorCode < 0 || r.colorCode > 15 ||
                    r.talkgroup < 1 || r.talkgroup > 16777215)
                throw new SecurityException("invalid DMR CC / talkgroup");
        } else {
            // v1 analog path supports SIMPLEX, no CTCSS/DCS, narrowband.
            // DMR CC/TG are signed compatibility fields and MUST be 0/0.
            // Reject hidden DMR settings instead of silently misapplying them.
            if (r.colorCode != 0 || r.talkgroup != 0)
                throw new SecurityException("analog FM requires cc=0 and tg=0");
            // The 68 35 frame carries independent TX/RX; restore the previous channel after TX.
        }

        File path = new File(r.audio).getCanonicalFile();
        String base = new File(root, "audio").getCanonicalPath() + File.separator;
        if (!path.getPath().startsWith(base) || !path.isFile() || !path.canRead())
            throw new SecurityException("WAV must be staged in the app-private audio directory");
        r.file = path;

        File keyFile = new File(root, "tx_audio.key");
        if (!keyFile.isFile()) throw new SecurityException("no provisioned TX key");
        String keyHex = firstLine(keyFile);
        if (keyHex == null || !keyHex.matches("[0-9a-fA-F]{64}"))
            throw new SecurityException("invalid provisioned TX key");
        String canonical = String.join("\n", "TX_AUDIO_V1", r.audio, r.tx, r.rx,
                r.digital, r.cc, r.tg, r.issued, r.nonce, r.sha);
        if (r.tone != null)
            canonical = canonical.replaceFirst("TX_AUDIO_V1", "TX_AUDIO_V2") + "\n" + r.tone + "\n" + r.toneSource;
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(decodeHex(keyHex), "HmacSHA256"));
        if (!MessageDigest.isEqual(hmac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                decodeHex(signature))) throw new SecurityException("incorrect HMAC");

        // RF coverage 400-470 MHz does NOT mean transmission is authorized.
        String entry = String.format(Locale.US, "%.5f,%s,%d,%d", r.txMHz,
                r.digital, r.colorCode, r.talkgroup);
        if (!allowed(entry)) throw new SecurityException("channel not approved");
        rememberNonce(r.nonce); // persistent replay prevention BEFORE PTT
        return r;
    }

    private static String ascii(byte[] b, int off, int n) {
        return new String(b, off, n, StandardCharsets.US_ASCII);
    }
    private static int u16(byte[] b,int off) {
        return (b[off]&255) | ((b[off+1]&255)<<8);
    }
    private static int u32(byte[] b,int off) {
        return u16(b,off) | (u16(b,off+2)<<16);
    }
    private int validateWav(Request r) throws Exception {
        File file = r.file;
        if (file.length() < 46 || file.length() > 480044)
            throw new IOException("WAV size unsupported");
        byte[] head = new byte[44];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(head) != 44) throw new IOException("short WAV header");
        }
        if (!ascii(head,0,4).equals("RIFF") || !ascii(head,8,4).equals("WAVE")
                || !ascii(head,12,4).equals("fmt ") || !ascii(head,36,4).equals("data")
                || u32(head,16) != 16 || u16(head,20) != 1 || u16(head,22) != 1
                || u32(head,24) != 16000 || u32(head,28) != 32000
                || u16(head,32) != 2 || u16(head,34) != 16)
            throw new IOException("expected canonical 44-byte 16kHz 16-bit mono PCM WAV");
        int dataSize = u32(head,40);
        if (dataSize < 2 || dataSize > 480000 || dataSize % 2 != 0
                || dataSize + 44L != file.length() || u32(head,4) + 8L != file.length())
            throw new IOException("invalid data chunk length");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[8192];
            int n;
            while ((n = input.read(data)) != -1) sha.update(data, 0, n);
        }
        if (!MessageDigest.isEqual(sha.digest(), decodeHex(r.sha)))
            throw new SecurityException("WAV checksum mismatch");
        return (int)(dataSize * 1000L / 32000L);
    }

    private void transmit(Request request) {
        // The bridge owns the logical session through BUSY and PttDispatcher.
        // RadioController's individual hardware operations are synchronized.
        // Do NOT hold its monitor during a full WAV: it must remain possible
        // for an emergency/manual PTT-UP or helper watchdog to make progress.
        transmitExclusive(request);
    }

    private String routeProfile() throws IOException {
        // Operator-controlled, app-private experimentation switch.
        // No HAL parameters are changed: this is only an Android output
        // stream A/B test, NOT confirmed direct digital microphone injection.
        File profile = new File(root, "audio_route_profile.txt");
        if (!profile.isFile()) return "acoustic";
        String value = firstLine(profile);
        if ("acoustic".equals(value) || "voice_call_probe".equals(value))
            return value;
        throw new IOException("invalid audio_route_profile.txt; use acoustic or voice_call_probe");
    }

    /**
     * Analog-only attenuation for speaker-to-mic acoustic coupling.
     * Stock baseline forced STREAM_MUSIC to 100%, which can overload the
     * narrowband FM TX mic path. Default to 40% for FM; DMR stays unchanged.
     * Operator can choose 0..100 via app-private analog_tx_volume_percent.txt,
     * with 0 used ONLY for supervised silence/mic-path isolation tests.
     *
     * Deliberately avoid undocumented vendor setParameters()/loopback changes:
     * the existing HIDL RX routing must not be disturbed.
     */
    private int analogPlaybackPercent() throws IOException {
        File setting = new File(root, "analog_tx_volume_percent.txt");
        if (!setting.isFile()) return 40;
        String value = firstLine(setting);
        if (value == null || !value.matches("(100|[0-9]{1,2})"))
            throw new IOException("analog_tx_volume_percent.txt must be 0..100");
        return Integer.parseInt(value);
    }

    /**
     * One-shot test: remove the app-side output stream from the FM TX chain.
     *
     * The caller still supplies an authenticated, SHA-checked, exactly 4s
     * all-zero 16k PCM WAV. It is NEVER opened as a MediaPlayer and the
     * AudioManager volume is NEVER read or changed in this diagnostic.
     * The private mode file is consumed BEFORE RF channel programming.
     *
     * Do not expand this path to DMR. Never enable through unsigned
     * broadcast extras: only trusted owner ADB access can opt in.
     */
    private boolean consumeAnalogPttOnlyDiagnostic(Request r, int wavMillis)
            throws IOException {
        if (!"0".equals(r.digital)) return false;
        File mode = new File(root, "analog_tx_diagnostic_mode.txt");
        if (!mode.isFile()) return false;
        String selected = firstLine(mode);
        if (!"ptt_only".equals(selected))
            throw new IOException("invalid analog_tx_diagnostic_mode.txt; use ptt_only");
        if (wavMillis != ANALOG_PTT_ONLY_HOLD_MS)
            throw new IOException("ptt_only requires exactly 4 seconds of signed silence");
        // validateWav() already verified canonical RIFF and SHA-256.
        // Verify all PCM bytes are zero so a mistakenly configured voice
        // request fails closed rather than being silently dropped.
        try (RandomAccessFile source = new RandomAccessFile(r.file, "r")) {
            source.seek(44);
            byte[] block = new byte[4096];
            int n;
            while ((n = source.read(block)) != -1) {
                for (int i = 0; i < n; i++)
                    if (block[i] != 0)
                        throw new IOException("ptt_only requires an all-zero PCM WAV");
            }
        }
        // One-shot guard: if deletion fails, refuse to key the transmitter.
        if (!mode.delete())
            throw new IOException("could not consume one-shot diagnostic mode file");
        Log.w(TAG, "ANALOG_PTT_ONLY_ARMED duration_ms=" +
                ANALOG_PTT_ONLY_HOLD_MS + " no MediaPlayer, no volume change");
        return true;
    }

    /**
     * Distinct one-shot owner-only RX-route experiment, layered onto the
     * existing four-second, signed, all-zero analog PTT-only diagnostic.
     * The secondary file is not a TX_AUDIO broadcast extra, and a normal
     * analog voice request is rejected if this file was left armed.
     */
    private boolean consumeAnalogRxRouteProbe(Request request, boolean pttOnly)
            throws IOException {
        if (!"0".equals(request.digital)) return false; // DMR is untouched.
        File setting = new File(root, "analog_rx_route_probe.txt");
        if (!setting.isFile()) return false;
        if (!pttOnly)
            throw new IOException("RX route probe requires the signed analog ptt_only test");
        String value = firstLine(setting);
        if (!"suspend_stock_rx".equals(value))
            throw new IOException("invalid analog_rx_route_probe.txt; use suspend_stock_rx");
        if (hidlRxProbeAdapter == null)
            throw new IOException("HIDL adapter not initialized; cannot run RX route probe");
        if (!setting.delete())
            throw new IOException("cannot disarm one-shot RX route probe; TX rejected");
        Log.w(TAG, "ANALOG_RX_ROUTE_PROBE_ARMED one-shot, no player, no volume changes");
        return true;
    }

    private void holdAnalogPttOnly() throws IOException, InterruptedException {
        final long deadline = SystemClock.elapsedRealtime() + ANALOG_PTT_ONLY_HOLD_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (closing.get()) throw new IOException("diagnostic stopped during analog PTT");
            if (!radio.isKeyed()) throw new IOException("radio unkeyed during analog diagnostic");
            Thread.sleep(Math.min(50L, Math.max(1L, deadline - SystemClock.elapsedRealtime())));
        }
        if (closing.get() || !radio.isKeyed())
            throw new IOException("analog diagnostic cancelled or unkeyed");
    }

    private void awaitMicReady() throws IOException, InterruptedException {
        final long target = SystemClock.elapsedRealtime() + PRE_PLAYBACK_SETTLE_MS;
        while (SystemClock.elapsedRealtime() < target) {
            if (closing.get()) throw new IOException("cancelled during PTT settling");
            if (!radio.isKeyed()) throw new IOException("PTT released before audio started");
            Thread.sleep(Math.min(50L, Math.max(1L, target - SystemClock.elapsedRealtime())));
        }
        if (closing.get() || !radio.isKeyed())
            throw new IOException("PTT released or cancelled before playback");
    }

    private void transmitExclusive(Request r) {
        boolean pttAttempted = false;
        boolean programmed = false;
        Channel previousChannel = null;
        boolean success = false;
        String note = "not started";
        MediaPlayer player = null;
        File mixedAudio = null;
        AudioManager manager = (AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        int oldVolume = -1;
        int streamType = AudioManager.STREAM_MUSIC;
        String outputProfile = "acoustic";
        int analogPercent = -1;
        boolean pttOnlyDiagnostic = false;
        boolean rxRouteProbeRequested = false;
        boolean rxRouteSuspended = false;
        HidlAdapter rxProbe = null;
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean audioError = new AtomicBoolean();
        completion = done;
        long began = SystemClock.elapsedRealtime();
        try {
            if (closing.get()) throw new IOException("bridge closing");
            if (SystemClock.elapsedRealtime() - previousEnd < 2000)
                throw new IOException("TX cooldown");
            if (radio == null || !radio.isConnected() || radio.isKeyed())
                throw new IOException("radio unavailable or manually keyed");
            int millis = validateWav(r);
            pttOnlyDiagnostic = consumeAnalogPttOnlyDiagnostic(r, millis);
            rxRouteProbeRequested = consumeAnalogRxRouteProbe(r, pttOnlyDiagnostic);
            if (pttOnlyDiagnostic && r.tone != null)
                throw new IOException("PTT-only diagnostic conflicts with CTCSS");
            File playback = r.file;
            if (r.tone != null && r.toneSource.equals("software")) {
                mixedAudio = File.createTempFile("ctcss-", ".wav", root);
                CtcssAudio.mix(r.file, mixedAudio, r.toneHz);
                playback = mixedAudio;
            }
            if (pttOnlyDiagnostic) {
                // Do not even open MediaPlayer / change stream volume here.
                outputProfile = "ptt_only_no_media_player";
                Log.w(TAG, "ANALOG_TX_DIAGNOSTIC profile=ptt_only, " +
                        "no Android audio playback/volume modification");
            } else {
                outputProfile = routeProfile();
                // Resolve invalid settings BEFORE channel programming and PTT.
                // Never apply attenuation to the proven DMR audio path.
                if ("0".equals(r.digital))
                    analogPercent = analogPlaybackPercent();
                streamType = "voice_call_probe".equals(outputProfile)
                        ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
                Log.i(TAG, "AUDIO_ROUTE profile=" + outputProfile +
                        " stream=" + streamType + " (HAL uplink NOT verified)");
            }
            previousChannel = radio.currentChannel();
            Channel ch = new Channel();
            ch.name = "Voice TX temporary";
            ch.txFreqMHz = r.txMHz;
            ch.rxFreqMHz = r.rxMHz;
            ch.isDigital = r.digital.equals("1");
            if (ch.isDigital) {
                ch.colorCode = r.colorCode;
                ch.talkgroup = r.talkgroup;
            } else {
                // Channel.buildProgramFrame() selects DMR007 68 35 analog FM.
                // No tones means ordinary analog radios can receive in CSQ.
                ch.bandwidthKhz = 12;  // narrowband: 68 35 gwb=0
                ch.txCtcssHz = r.tone != null && r.toneSource.equals("module") ? r.toneHz : 0;
                ch.rxCtcssHz = 0;
                ch.txDcsCode = null;
                ch.rxDcsCode = null;
                ch.squelchLevel = 4;
            }
            Log.i(TAG, "TX_AUDIO mode=" + (ch.isDigital ? "DMR" : "ANALOG_FM") +
                    " tx=" + r.tx + " rx=" + r.rx + " tone=" + r.tone + " source=" + r.toneSource);
            if (!radio.programChannel(ch))
                throw new IOException((ch.isDigital ? "DMR" : "analog FM") +
                        " channel programming failed; no PTT issued");
            programmed = true;
            if (closing.get()) throw new IOException("cancelled before TX");

            if (!pttOnlyDiagnostic) {
                player = new MediaPlayer();
                player.setAudioStreamType(streamType);
                player.setDataSource(playback.getAbsolutePath());
                player.setOnCompletionListener(mp -> done.countDown());
                player.setOnErrorListener((mp, what, extra) -> {
                    audioError.set(true);
                    done.countDown();
                    return true;
                });
                player.prepare();
                if (manager != null) {
                    oldVolume = manager.getStreamVolume(streamType);
                    if ("0".equals(r.digital)) {
                        // FM route was being driven at max volume; reduce amplifier
                        // level while preserving the TTS samples/MediaPlayer gain.
                        int max = manager.getStreamMaxVolume(streamType);
                        int target = Math.max(0, Math.min(max,
                                Math.round(max * analogPercent / 100.0f)));
                        manager.setStreamVolume(streamType, target, 0);
                        Log.i(TAG, "ANALOG_FM_GAIN percent=" + analogPercent +
                                " stream=" + streamType + " volume=" + target +
                                "/" + max + " (restore=" + oldVolume + ")");
                    } else if ("acoustic".equals(outputProfile)) {
                        // Existing field-tested DMR baseline: unchanged.
                        manager.setStreamVolume(streamType,
                                manager.getStreamMaxVolume(streamType), 0);
                    }
                } else if ("0".equals(r.digital)) {
                    // Cannot apply gain reduction without an AudioManager.
                    throw new IOException("AudioManager unavailable for analog gain control");
                }
            } else {
                Log.i(TAG, "ANALOG_PTT_ONLY_NO_AUDIO_ENGINE: skip MediaPlayer " +
                        "creation, prepare(), start(), and all stream volume changes");
            }
            if (rxRouteProbeRequested) {
                // StockAudioRoute changes ONLY SET_LOOPBACK_TYPE=3,3 -> 0.
                // Mic gain, MIC_NUM, speak_enable/HIDL ownership and DMR stay put.
                rxProbe = hidlRxProbeAdapter;
                if (rxProbe == null ||
                        !rxProbe.suspendStockRxRouteForAnalogProbe())
                    throw new IOException("stock RX route not open or suspension failed; no PTT");
                rxRouteSuspended = true;
                Log.w(TAG, "ANALOG_RX_ROUTE_PROBE_OFF before PTT; RX may be muted");
            }
            pttAttempted = true;
            if (!radio.pttDown()) throw new IOException("PTT DOWN not acknowledged");
            Log.i(TAG, "PTT_DOWN_CONFIRMED; start 1000 ms pre-playback settle");
            awaitMicReady(); // deliberate ONE-SECOND pause AFTER key
            if (pttOnlyDiagnostic) {
                Log.w(TAG, "ANALOG_PTT_ONLY_START: 4000ms keyed hold, no WAV playback");
                holdAnalogPttOnly();
                Log.w(TAG, "ANALOG_PTT_ONLY_COMPLETE: no WAV playback attempted");
                note = "mode=ANALOG_FM; diagnostic=ptt_only_no_media_player" +
                        "; rx_route_probe=" + (rxRouteProbeRequested ? "stock_rx_suspended" : "baseline") +
                        "; hold_ms=" + ANALOG_PTT_ONLY_HOLD_MS +
                        "; no playback or stream gain changed; RF audio NOT verified";
            } else {
                Log.i(TAG, "PRE_PLAYBACK_SETTLE_COMPLETE; starting WAV");
                player.start();
                if (!done.await(Math.min(MAX_TX_MS + 3000, millis + 3000), TimeUnit.MILLISECONDS))
                    throw new IOException("playback timeout");
                if (audioError.get()) throw new IOException("audio playback failure");
                if (closing.get()) throw new IOException("cancelled during TX");
                note = "mode=" + (r.digital.equals("1") ? "DMR" : "ANALOG_FM") +
                        "; gain_pct=" + (analogPercent < 0 ? "baseline" : analogPercent) +
                        "; route=" + outputProfile +
                        "; local playback complete; received RF audio NOT verified";
            }
            success = true;
        } catch (Exception error) {
            note = error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, "TX failed: " + note, error);
        } finally {
            try { if (player != null) player.stop(); } catch (Exception ignored) {}
            try { if (player != null) player.release(); } catch (Exception ignored) {}
            if (pttAttempted) {
                boolean released = false;
                try { released = radio.pttUp(); } catch (Exception error) {
                    Log.e(TAG, "PTT UP threw", error);
                }
                if (!released) {
                    success = false;
                    note += "; CRITICAL: PTT release not acknowledged";
                    Log.e(TAG, "CRITICAL PTT RELEASE FAILURE: check helper watchdog");
                }
            }
            // TX_AUDIO must not permanently replace the operator's selected channel.
            // Restore only after PTT is confirmed released; never retune an active TX.
            if (programmed && previousChannel != null && radio.isConnected() && !radio.isKeyed()) {
                try {
                    if (!radio.programChannel(previousChannel)) {
                        success = false;
                        note += "; prior channel restoration failed";
                    }
                } catch (Exception error) {
                    success = false;
                    note += "; channel restore exception: " + error.getClass().getSimpleName();
                    Log.e(TAG, "Cannot restore prior channel", error);
                }
            }
            // Restore the stock RX loopback only AFTER confirmed PTT UP.
            // Never reactivate microphone monitoring while the radio reports
            // that it is still keyed. If unkey fails, require operator recovery.
            if (rxRouteSuspended) {
                if (radio.isKeyed()) {
                    success = false;
                    note += "; CRITICAL: still keyed; loopback stays OFF; " +
                            "unkey radio and restart radio service before RX";
                    Log.e(TAG, "ANALOG_RX_ROUTE_PROBE_RESTORE_DEFERRED: radio still keyed");
                } else {
                    try {
                        boolean restored = rxProbe.restoreStockRxRouteAfterAnalogProbe();
                        Log.w(TAG, "ANALOG_RX_ROUTE_PROBE_RESTORED invoked=" + restored);
                        if (!restored) {
                            success = false;
                            note += "; stock RX loopback restoration failed; restart radio service";
                        }
                    } catch (Exception restoreFailure) {
                        success = false;
                        note += "; RX loopback restoration exception; restart radio service";
                        Log.e(TAG, "ANALOG_RX_ROUTE_PROBE_RESTORE_FAILED", restoreFailure);
                    }
                }
            }
            previousEnd = SystemClock.elapsedRealtime();
            if (manager != null && oldVolume >= 0) {
                try { manager.setStreamVolume(streamType, oldVolume, 0); }
                catch (Exception error) { Log.w(TAG, "volume restoration failed", error); }
            }
            audit(r, SystemClock.elapsedRealtime()-began, success, note);
            reply(success, note, r.tx, r.sha);
            if (mixedAudio != null && !mixedAudio.delete()) Log.w(TAG, "Cannot remove temporary CTCSS WAV");
            completion = null;
            BUSY.set(false);
        }
    }

    private void audit(Request r, long elapsed, boolean success, String note) {
        try (FileOutputStream out = new FileOutputStream(new File(root, "tx_events.jsonl"), true)) {
            note = note.replace("\\", "\\\\").replace("\"", "'")
                    .replace("\n", " ").replace("\r", " ");
            String json = "{\"timestamp_ms\":" + System.currentTimeMillis()
                    + ",\"tx_mhz\":\"" + r.tx + "\",\"rx_mhz\":\"" + r.rx
                    + "\",\"digital\":\"" + r.digital + "\""
                    + ",\"duration_ms\":" + elapsed + ",\"sha256\":\"" + r.sha
                    + "\",\"success\":" + success + ",\"note\":\"" + note + "\"}\n";
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException error) { Log.e(TAG, "TX audit write failed", error); }
    }
    private void reply(boolean ok, String message, String freq, String sha) {
        Intent result = new Intent(RESULT_ACTION);
        result.setPackage(app.getPackageName());
        result.putExtra("success", ok);
        result.putExtra("message", message);
        result.putExtra("tx", freq);
        result.putExtra("sha256", sha);
        result.putExtra("rf_verified", false);
        app.sendBroadcast(result);
        Log.i(TAG, "TX_RESULT success=" + ok + " tx=" + freq + " " + message);
    }

    /** Called before RadioService closes the radio. */
    public void shutdown(Runnable afterUnkey) {
        if (!closing.compareAndSet(false, true)) return;
        CountDownLatch current = completion;
        if (current != null) current.countDown();
        try { worker.execute(() -> { if (afterUnkey != null) afterUnkey.run(); }); }
        catch (RejectedExecutionException e) {
            Log.e(TAG, "shutdown ordering failed", e);
            if (afterUnkey != null) afterUnkey.run();
        }
        worker.shutdown();
    }

    private static final class Request {
        String audio, tx, rx, digital, cc, tg, issued, nonce, sha, tone, toneSource;
        double txMHz, rxMHz, toneHz;
        int colorCode, talkgroup;
        File file;
    }
}
