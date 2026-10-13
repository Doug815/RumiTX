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
    private long previousEnd = -100000;

    public VoiceTxBridge(Context context, RadioController controller) {
        app = context.getApplicationContext();
        radio = controller;
        root = new File(app.getFilesDir(), "voice_bridge");
        if (!root.exists() && !root.mkdirs())
            Log.e(TAG, "Cannot create voice bridge directory");
    }

    public static boolean isTransmitting() { return BUSY.get(); }

    public BroadcastReceiver receiver() {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!ACTION.equals(intent.getAction())) return;
                if (closing.get() || !BUSY.compareAndSet(false, true)) {
                    reply(false, "BUSY or stopping", "", "");
                    return;
                }
                final Request request;
                try {
                    request = authorize(intent);
                } catch (Exception error) {
                    BUSY.set(false);
                    reply(false, "REJECTED: " + error.getMessage(), "", "");
                    return;
                }
                try {
                    worker.execute(() -> transmit(request));
                } catch (RejectedExecutionException rejected) {
                    BUSY.set(false);
                    reply(false, "Service stopping", request.tx, request.sha);
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
        r.cc = optional(i, "cc", "1");
        r.tg = optional(i, "tg", "99");
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
        if (!r.digital.equals("1")) throw new SecurityException("DMR only");
        r.colorCode = Integer.parseInt(r.cc);
        r.talkgroup = Integer.parseInt(r.tg);
        if (r.colorCode < 0 || r.colorCode > 15 ||
                r.talkgroup < 1 || r.talkgroup > 16777215)
            throw new SecurityException("invalid CC / talkgroup");

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
        // The underlying DMR007 helper socket is single-client.
        // Lock the singleton for this entire operation; no second connection.
        synchronized (radio) {
            transmitExclusive(request);
        }
    }

    private void transmitExclusive(Request r) {
        boolean pttAttempted = false;
        boolean success = false;
        String note = "not started";
        MediaPlayer player = null;
        AudioManager manager = (AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        int oldVolume = -1;
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
            Channel ch = new Channel();
            ch.name = "Voice TX temporary";
            ch.txFreqMHz = r.txMHz;
            ch.rxFreqMHz = r.rxMHz;
            ch.isDigital = true;
            ch.colorCode = r.colorCode;
            ch.talkgroup = r.talkgroup;
            if (!radio.programChannel(ch)) throw new IOException("channel programming failed");
            if (closing.get()) throw new IOException("cancelled before TX");

            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(r.file.getAbsolutePath());
            player.setOnCompletionListener(mp -> done.countDown());
            player.setOnErrorListener((mp, what, extra) -> {
                audioError.set(true);
                done.countDown();
                return true;
            });
            player.prepare();
            if (manager != null) {
                oldVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
                manager.setStreamVolume(AudioManager.STREAM_MUSIC,
                        manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            }
            pttAttempted = true;
            if (!radio.pttDown()) throw new IOException("PTT DOWN not acknowledged");
            Thread.sleep(650);
            if (closing.get()) throw new IOException("cancelled after PTT");
            player.start();
            if (!done.await(Math.min(MAX_TX_MS + 3000, millis + 3000), TimeUnit.MILLISECONDS))
                throw new IOException("playback timeout");
            if (audioError.get()) throw new IOException("audio playback failure");
            if (closing.get()) throw new IOException("cancelled during TX");
            note = "local playback complete; RF audio NOT verified";
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
            previousEnd = SystemClock.elapsedRealtime();
            if (manager != null && oldVolume >= 0) {
                try { manager.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0); }
                catch (Exception error) { Log.w(TAG, "volume restoration failed", error); }
            }
            audit(r, SystemClock.elapsedRealtime()-began, success, note);
            reply(success, note, r.tx, r.sha);
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
                    + "\",\"duration_ms\":" + elapsed + ",\"sha256\":\"" + r.sha
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
        String audio, tx, rx, digital, cc, tg, issued, nonce, sha;
        double txMHz, rxMHz;
        int colorCode, talkgroup;
        File file;
    }
}
