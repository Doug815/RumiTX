package com.rumitx.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated, bounded file-audio TX bridge for rooted DOOGEE S80/RumiTX v2.3.1.
 *
 * Only the RadioController singleton touches the helper and UART. No new
 * socket, ioctl, serial writer, shell or long-lived root service is created.
 * This class does not implement digital audio injection: it deliberately
 * plays WAV via the phone speaker for acoustic pickup by the TX microphone.
 */
public final class VoiceTxBridge {
    public static final String ACTION = "com.rumitx.app.TX_AUDIO";
    public static final String RESULT_ACTION = "com.rumitx.app.TX_RESULT";
    private static final String TAG = "RumiTX-TX";
    private static final int MAX_AUDIO_MS = 15000;
    private static final long MAX_CLOCK_SKEW_MS = 90000;
    private static final int MAX_WAV_BYTES = 480044; // 15 s at 16 kHz, mono, 16-bit + 44 B WAV
    private final Context app;
    private final RadioController radio;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RumiTX-VoiceBridge-TX");
        t.setDaemon(false);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private final AtomicBoolean busy = BUSY;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final File baseDir;
    private final Set<String> seenNonces = new HashSet<>();
    private volatile CountDownLatch activeCompletion;
    private volatile MediaPlayer activePlayer;
    private long lastTxEndElapsedMs = -100000;

    public VoiceTxBridge(Context ctx, RadioController controller) {
        app = ctx.getApplicationContext();
        radio = controller;
        baseDir = new File(app.getFilesDir(), "voice_bridge");
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            Log.e(TAG, "Cannot create bridge configuration directory");
        }
    }

    public boolean isBusy() { return busy.get(); }
    /** Visible to manual controls so they never compete for the radio during a bridge TX. */
    public static boolean isTransmitting() { return BUSY.get(); }

    public BroadcastReceiver receiver() {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !ACTION.equals(intent.getAction())) return;
                if (closing.get() || !busy.compareAndSet(false, true)) {
                    result(false, "BUSY: one radio transmission at a time", "", "");
                    return;
                }
                // The receiver is on the main thread: move authentication,
                // replay-ledger fsync, WAV validation and all hardware I/O off-main.
                try {
                    executor.execute(() -> {
                        try {
                            Request req = parseAndAuthorize(intent);
                            transmit(req);
                        } catch (Exception ex) {
                            Log.e(TAG, "TX_AUDIO rejected", ex);
                            try { result(false, "REJECTED: " + ex.getMessage(), "", ""); }
                            finally { busy.set(false); }
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException ex) {
                    busy.set(false);
                    result(false, "Bridge stopped", "", "");
                }
            }
        };
    }

    private Request parseAndAuthorize(Intent intent) throws Exception {
        final Request r = new Request();
        r.audio = required(intent, "audio");
        r.tx = required(intent, "tx");
        r.rx = fallback(intent, "rx", r.tx);
        r.digital = fallback(intent, "digital", "1");
        r.cc = fallback(intent, "cc", "1");
        r.tg = fallback(intent, "tg", "99");
        r.issued = required(intent, "issued");
        r.nonce = required(intent, "nonce");
        r.sha = required(intent, "sha256").toLowerCase(Locale.ROOT);
        String macHex = required(intent, "mac");
        if (!r.issued.matches("[0-9]{13}") || !r.nonce.matches("[a-fA-F0-9]{32}")
                || !r.sha.matches("[a-f0-9]{64}") || !macHex.matches("[a-fA-F0-9]{64}")) {
            throw new SecurityException("malformed authentication fields");
        }
        long issuedMs = Long.parseLong(r.issued);
        if (Math.abs(System.currentTimeMillis() - issuedMs) > MAX_CLOCK_SKEW_MS) {
            throw new SecurityException("timestamp expired or clocks out of sync");
        }
        r.txMHz = parseFrequency(r.tx);
        r.rxMHz = parseFrequency(r.rx);
        if (!"1".equals(r.digital)) {
            throw new SecurityException("DMR only in v1");
        }
        r.colorCode = Integer.parseInt(r.cc);
        r.talkgroup = Integer.parseInt(r.tg);
        if (r.colorCode < 0 || r.colorCode > 15 || r.talkgroup < 1 || r.talkgroup > 16777215) {
            throw new SecurityException("invalid DMR color code / talkgroup");
        }
        String root = new File(baseDir, "audio").getCanonicalPath() + File.separator;
        File f = new File(r.audio).getCanonicalFile();
        if (!f.getPath().startsWith(root) || !f.isFile() || !f.canRead()) {
            throw new SecurityException("audio must be staged in app-private voice_bridge/audio");
        }
        r.file = f;
        // Signed fields include the declared audio SHA-256 to prevent payload substitution.
        String canonical = String.join("\n", "TX_AUDIO_V1", r.audio, r.tx, r.rx,
                r.digital, r.cc, r.tg, r.issued, r.nonce.toLowerCase(Locale.ROOT), r.sha);
        File keyFile = new File(baseDir, "tx_audio.key");
        if (!keyFile.isFile()) throw new SecurityException("bridge key not provisioned");
        String keyHex;
        try (BufferedReader reader = new BufferedReader(new FileReader(keyFile))) {
            keyHex = reader.readLine();
        }
        if (keyHex == null || !keyHex.matches("[a-fA-F0-9]{64}")) {
            throw new SecurityException("invalid provisioned bridge key");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hexDecode(keyHex), "HmacSHA256"));
        byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, hexDecode(macHex))) {
            throw new SecurityException("bad request signature");
        }
        String allow = String.format(Locale.US, "%.5f,%s,%d,%d", r.txMHz, r.digital, r.colorCode, r.talkgroup);
        if (!approvedChannel(allow)) {
            throw new SecurityException("TX channel absent from operator allowlist");
        }
        // Replay ledger stored before any TX occurs, survives process restarts.
        String replayKey = r.nonce.toLowerCase(Locale.ROOT);
        synchronized (seenNonces) {
            if (seenNonces.contains(replayKey) || replayFileContains(replayKey)) {
                throw new SecurityException("replayed request");
            }
            File replay = new File(baseDir, "nonces.txt");
            try (FileOutputStream output = new FileOutputStream(replay, true)) {
                output.write((replayKey + "\n").getBytes(StandardCharsets.US_ASCII));
                output.getFD().sync();
            }
            seenNonces.add(replayKey);
        }
        return r;
    }

    private boolean approvedChannel(String candidate) throws IOException {
        File file = new File(baseDir, "allowed_channels.csv");
        if (!file.isFile()) return false; // never allow a default TX channel
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String item = line.trim();
                if (!item.startsWith("#") && candidate.equals(item)) return true;
            }
        }
        return false;
    }

    private boolean replayFileContains(String nonce) throws IOException {
        File file = new File(baseDir, "nonces.txt");
        if (!file.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (nonce.equals(line.trim())) return true;
            }
        }
        return false;
    }

    private static String required(Intent i, String k) {
        String value = i.getStringExtra(k);
        if (value == null || value.isEmpty() || value.length() > 1024) {
            throw new IllegalArgumentException("missing/invalid " + k);
        }
        return value;
    }
    private static String fallback(Intent i, String k, String defaultVal) {
        String value = i.getStringExtra(k);
        return value == null ? defaultVal : value;
    }
    private static double parseFrequency(String val) {
        // No exponent, NaN, infinities or locale-dependent parsing.
        if (!val.matches("[0-9]{3}\\.[0-9]{1,5}")) throw new IllegalArgumentException("invalid MHz");
        double mhz = Double.parseDouble(val);
        if (mhz < 400.0 || mhz > 470.0) throw new IllegalArgumentException("MHz outside S80 range");
        return mhz;
    }

    private void transmit(Request r) {
        // Serialize the entire TX session against synchronized RadioController operations,
        // including channel changes, PTT, and message/radio commands from other activities.
        // The primary TX implementation always attempts to release PTT in finally.
        try {
            synchronized (radio) { transmitExclusive(r); }
        } catch (Throwable unexpected) {
            Log.e(TAG, "CRITICAL: TX worker failed outside normal cleanup", unexpected);
            try { synchronized (radio) { radio.pttUp(); } }
            catch (Throwable releaseFailure) {
                Log.e(TAG, "CRITICAL: emergency unkey failed", releaseFailure);
            } finally {
                busy.set(false);
                result(false, "Unexpected TX worker failure; verify physical PTT is released", r.tx, r.sha);
            }
        }
    }

    private void transmitExclusive(Request r) {
        boolean keyed = false;
        boolean pttAttempted = false;
        final AtomicBoolean playbackError = new AtomicBoolean(false);
        boolean success = false;
        String message = "UNKNOWN";
        long started = SystemClock.elapsedRealtime();
        AudioManager audioMgr = null;
        int originalVolume = -1;
        MediaPlayer player = null;
        CountDownLatch completed = new CountDownLatch(1);
        activeCompletion = completed;
        try {
            audioMgr = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            if (closing.get()) throw new IllegalStateException("bridge closing");
            if (SystemClock.elapsedRealtime() - lastTxEndElapsedMs < 2000) {
                throw new IllegalStateException("PTT cooldown active");
            }
            if (radio == null || !radio.isConnected() || radio.isKeyed()) {
                throw new IllegalStateException("radio disconnected or already transmitting");
            }
            int durationMs = verifyWavAndHash(r.file, r.sha);
            Channel ch = new Channel();
            ch.name = "Voice agent temporary TX";
            ch.txFreqMHz = r.txMHz;
            ch.rxFreqMHz = r.rxMHz;
            ch.isDigital = true;
            ch.colorCode = r.colorCode;
            ch.talkgroup = r.talkgroup;
            if (!radio.programChannel(ch)) throw new IOException("channel programming failed");
            if (closing.get()) throw new InterruptedException("cancelled before PTT");
            // MediaPlayer callbacks deliver on main Looper if instantiated on this worker.
            player = new MediaPlayer();
            activePlayer = player;
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(r.file.getAbsolutePath());
            player.setOnCompletionListener(mp -> completed.countDown());
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error " + what + "/" + extra);
                playbackError.set(true);
                completed.countDown();
                return true;
            });
            player.prepare();
            if (audioMgr != null) {
                originalVolume = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC,
                        audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            }
            pttAttempted = true;
            keyed = radio.pttDown();
            if (!keyed) throw new IOException("PTT key failed");
            // Module's sendstart event historically takes roughly 0.5 sec.
            Thread.sleep(650);
            if (closing.get()) throw new InterruptedException("cancelled after PTT");
            player.start();
            boolean completedOk = completed.await(Math.min(MAX_AUDIO_MS + 3000, durationMs + 3000),
                    TimeUnit.MILLISECONDS);
            if (!completedOk) throw new IOException("audio watchdog timeout");
            if (playbackError.get()) throw new IOException("audio playback error");
            if (closing.get()) throw new InterruptedException("cancelled");
            // Playback completion is LOCAL ONLY, RF confirmation requires SDR test.
            message = "local playback complete; RF delivery unverified";
            success = true;
        } catch (Exception error) {
            message = error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(TAG, "TX_FAILED: " + message, error);
        } finally {
            try { if (player != null) player.stop(); } catch (Exception ignored) { }
            try { if (player != null) player.release(); } catch (Exception ignored) { }
            activePlayer = null;
            // NEVER leave PTT asserted on error, timeout, completion, or Activity shutdown.
            boolean unkeyOk = true;
            if (pttAttempted) {
                try { unkeyOk = radio.pttUp(); }
                catch (Throwable releaseFailure) {
                    unkeyOk = false;
                    Log.e(TAG, "CRITICAL: PTT release threw", releaseFailure);
                }
                if (!unkeyOk) {
                    Log.e(TAG, "CRITICAL: PTT release failed; external helper watchdog required");
                    success = false;
                    message += "; CRITICAL PTT_UP FAILED";
                }
            }
            lastTxEndElapsedMs = SystemClock.elapsedRealtime();
            if (audioMgr != null && originalVolume >= 0) {
                try { audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0); }
                catch (Exception e) { Log.w(TAG, "volume restore failed", e); }
            }
            long elapsed = SystemClock.elapsedRealtime() - started;
            try {
                audit(r, elapsed, success, message);
                result(success, message, r.tx, r.sha);
            } finally {
                activeCompletion = null;
                busy.set(false);
            }
        }
    }

    /** Exactly 16kHz/mono/16-bit PCM little-endian canonical 44-byte WAV. */
    private static int verifyWavAndHash(File file, String wantSha) throws Exception {
        if (file.length() < 45 || file.length() > MAX_WAV_BYTES) {
            throw new IOException("WAV size outside allowed limits");
        }
        byte[] head = new byte[44];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(head) != head.length) throw new IOException("truncated WAV");
        }
        if (!ascii(head,0,4).equals("RIFF") || !ascii(head,8,4).equals("WAVE")
                || !ascii(head,12,4).equals("fmt ") || !ascii(head,36,4).equals("data")
                || le32(head,16) != 16 || le16(head,20) != 1
                || le16(head,22) != 1 || le32(head,24) != 16000
                || le32(head,28) != 32000 || le16(head,32) != 2
                || le16(head,34) != 16) {
            throw new IOException("WAV must be canonical 16kHz mono 16-bit PCM");
        }
        int dataBytes = le32(head,40);
        if (dataBytes <= 0 || dataBytes + 44 != file.length() || dataBytes % 2 != 0
                || le32(head,4) != file.length() - 8) {
            throw new IOException("invalid WAV data chunk length");
        }
        int durationMs = (int) (dataBytes * 1000L / 32000L);
        if (durationMs > MAX_AUDIO_MS) throw new IOException("audio exceeds 15 seconds");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int count;
            while ((count = input.read(buf)) != -1) digest.update(buf, 0, count);
        }
        if (!MessageDigest.isEqual(digest.digest(), hexDecode(wantSha))) {
            throw new SecurityException("WAV hash mismatch");
        }
        return durationMs;
    }
    private static String ascii(byte[] a, int off, int len) {
        return new String(a, off, len, StandardCharsets.US_ASCII);
    }
    private static int le16(byte[] b, int i) { return (b[i] & 255) | ((b[i+1] & 255) << 8); }
    private static int le32(byte[] b, int i) {
        return (b[i] & 255) | ((b[i+1] & 255) << 8) | ((b[i+2] & 255) << 16)
                | ((b[i+3] & 255) << 24);
    }
    private static byte[] hexDecode(String v) {
        if ((v.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[v.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(v.substring(i*2, i*2+2), 16);
        }
        return out;
    }

    private void audit(Request r, long duration, boolean success, String msg) {
        try {
            File log = new File(baseDir, "tx_events.jsonl");
            String json = "{\"timestamp_ms\":" + System.currentTimeMillis()
                    + ",\"tx_mhz\":\"" + r.tx + "\",\"rx_mhz\":\"" + r.rx
                    + "\",\"duration_ms\":" + duration
                    + ",\"sha256\":\"" + r.sha + "\",\"success\":" + success
                    + ",\"note\":\"" + msg.replace("\\", "\\\\").replace("\"", "'")
                      .replace("\n", " ").replace("\r", " ") + "\"}\n";
            try (FileOutputStream output = new FileOutputStream(log,true)) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        } catch (IOException e) { Log.e(TAG, "Audit log failed", e); }
    }

    private void result(boolean success, String message, String tx, String sha) {
        Log.i(TAG, "TX_RESULT success=" + success + " tx=" + tx
                + " sha256=" + sha + " message=" + message);
        Intent result = new Intent(RESULT_ACTION);
        result.setPackage(app.getPackageName()); // do not export results to other apps
        result.putExtra("success", success);
        result.putExtra("message", message);
        result.putExtra("tx", tx);
        result.putExtra("sha256", sha);
        result.putExtra("rf_verified", false);
        app.sendBroadcast(result);
    }

    /** Cancel active playback; enqueue cleanup after the TX worker's finally/unkey. */
    public void shutdown(Runnable afterRelease) {
        if (!closing.compareAndSet(false,true)) return;
        CountDownLatch latch = activeCompletion;
        if (latch != null) latch.countDown();
        executor.execute(() -> {
            if (afterRelease != null) afterRelease.run();
        });
        executor.shutdown();
    }

    private static final class Request {
        String audio, tx, rx, digital, cc, tg, issued, nonce, sha;
        double txMHz, rxMHz;
        int colorCode, talkgroup;
        File file;
    }
}
