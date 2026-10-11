package com.rumitx.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * RadioController for the DOOGEE S80 HCT intercom module.
 *
 * Binary DMR007 protocol (see Dmr007Frame + DMR007-PROTOCOL.txt):
 *   Serial: /dev/ttyMT1, 57600 8N1 raw (stock app baud, verified live).
 *   PTT: ioctl on /dev/hctintercom_node via the root helper
 *        /data/local/tmp/rumitx-ptt (APP_START / KEY_DOWN / KEY_UP).
 *
 * TX sequence (verified live 2026-10-11, SDR-confirmed carriers):
 *   1. programChannel()  -> 68 36 (digital) / 68 35 (analog) frame,
 *      await 68 36/35 00 00 SUCCESS reply.
 *   2. pttDown()         -> 68 06 01 01 start frame, await the
 *      68 06 02 61 "sendstart" notification (2 s), then helper "down 0".
 *   3. pttUp()           -> helper "up 0", then 68 06 01 FF stop frame.
 *
 * Physical PTT button: the stock HcTTalkie app maps it to KEYCODE_F9
 * (Fragment_speak.onKeyDown: keys {24,25,F9} = {VOL_UP, VOL_DOWN, PTT};
 * PTT action fires on FLAG_LONG_PRESS, release on onKeyUp).
 */
public class RadioController {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------
    private static final String SERIAL_PORT = "/dev/ttyMT1";
    private static final int BAUD_RATE = 57600;
    private static final String PTT_HELPER = "/data/local/tmp/rumitx-ptt";
    /** TCP port of the helper daemon (started from adb shell). */
    private static final int PTT_TCP_PORT = 45921;
    private static final int PTT_VAL = 0; // INTERCOM_KEY_DOWN/UP arg

    /** Physical PTT button keycode (stock app: "KEYCODE_F9"). */
    public static final int KEYCODE_F9 = 139;
    /** KeyEvent.FLAG_LONG_PRESS. */
    public static final int FLAG_LONG_PRESS = 0x80;

    /** True if this key event is the physical PTT button going down. */
    public static boolean isPttKeyDown(int keyCode, int flags) {
        return keyCode == KEYCODE_F9 && (flags & FLAG_LONG_PRESS) != 0;
    }

    /** True if this key event is the physical PTT button coming up. */
    public static boolean isPttKeyUp(int keyCode) {
        return keyCode == KEYCODE_F9;
    }

    public interface RadioCallback {
        void onError(String msg);
        void onResponse(String msg);
    }

    /** A parsed inbound frame. */
    public static final class Reply {
        public final int cmd;      // command byte (0x36, 0x06, ...)
        public final byte[] raw;
        public final String hex;   // "68 36 00 00 87 C9 00 00 10"
        Reply(int cmd, byte[] raw) {
            this.cmd = cmd;
            this.raw = raw;
            this.hex = Dmr007Frame.toHexString(raw);
        }
        /** Short-reply status bytes: 68 <cmd> 00 00 <CS> 00 00 10. */
        public boolean isSuccess() {
            return raw.length >= 4
                    && (raw[2] & 0xFF) == 0x00 && (raw[3] & 0xFF) == 0x00;
        }
        /** 68 06 02 61 sendstart notification. */
        public boolean isSendstart() {
            return raw.length >= 5
                    && (raw[1] & 0xFF) == 0x06
                    && (raw[2] & 0xFF) == 0x02
                    && (raw[3] & 0xFF) == 0x61;
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private volatile boolean connected = false;
    private FileInputStream serialIn;
    private FileOutputStream serialOut;
    // Note: no pttProc -- the helper is a persistent daemon started from
    // adb shell; the app talks to it via TCP (Android blocks app exec).
    private java.net.Socket pttSocket;
    private OutputStream pttIn;
    private BufferedReader pttOut;
    private Thread readerThread;
    private final BlockingQueue<Reply> replyQueue =
            new LinkedBlockingQueue<Reply>();
    private RadioCallback callback;
    private boolean keyed = false;

    public void setCallback(RadioCallback cb) { this.callback = cb; }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    public synchronized boolean connect() {
        if (connected) return true;
        try {
            android.util.Log.i("RumiTX", "connect: opening TCP to helper");
            // 1. Connect to the PTT helper daemon via TCP. The helper
            // runs as a daemon (started from adb shell, NOT spawned by
            // the app -- Android 8+ blocks apps from exec'ing binaries).
            java.net.Socket sock = new java.net.Socket();
            sock.connect(new java.net.InetSocketAddress("127.0.0.1",
                    PTT_TCP_PORT), 5000);
            pttIn = sock.getOutputStream();
            pttOut = new BufferedReader(
                    new InputStreamReader(sock.getInputStream()));
            pttSocket = sock;
            android.util.Log.i("RumiTX", "connect: TCP open");
            String ready = pttOut.readLine();
            android.util.Log.i("RumiTX", "connect: helper said: " + ready);
            if (ready == null || !ready.startsWith("READY")) {
                reportError("PTT helper did not print READY");
                return false;
            }

            // 2. APP_START ioctl so the module powers its serial side.
            android.util.Log.i("RumiTX", "connect: sending start");
            if (!pttCmd("start")) {
                reportError("helper 'start' (APP_START) failed");
                return false;
            }
            android.util.Log.i("RumiTX", "connect: start OK");

            // 3. Configure the UART at 57600 8N1 raw. The deployed
            // helper's "serial" command hard-codes 9600 (wrong baud --
            // the stock app opens the port at 57600), so configure via
            // stty instead. Do NOT touch the phone's helper binary.
            android.util.Log.i("RumiTX", "connect: configuring serial");
            if (!configureSerial()) {
                reportError("could not set " + SERIAL_PORT
                        + " to 57600 8N1");
                return false;
            }
            android.util.Log.i("RumiTX", "connect: serial OK");

            File f = new File(SERIAL_PORT);
            if (!f.exists()) {
                reportError(SERIAL_PORT + " missing");
                return false;
            }
            serialOut = new FileOutputStream(f);
            serialIn = new FileInputStream(f);

            // 4. Reader thread: 68..10 frame scanner + checksum check.
            connected = true;
            readerThread = new Thread(new Runnable() {
                @Override public void run() { readLoop(); }
            }, "rumitx-serial-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // 5. Drain the boot indicator ("44 c6 b5 b5 c0 31 3b 0d 0a"
            // ~1.5 s after APP_START) so it can't pollute reply matching.
            drain(2500);
            return true;
        } catch (Exception e) {
            android.util.Log.e("RumiTX", "connect FAILED: " + e);
            reportError("connect: " + e);
            connected = false;
            return false;
        }
    }

    public synchronized void disconnect() {
        connected = false;
        try {
            if (keyed) pttUp();
            if (pttIn != null) {
                pttCmd("exit");                            // APP_EXIT ioctl
                try {
                    pttIn.write("quit\n".getBytes("ASCII"));
                    pttIn.flush();
                } catch (IOException ignored) { }
            }
        } catch (Exception ignored) { }
        // Helper is a persistent daemon; just close our socket.
        // (Do NOT kill it -- other app sessions may be using it.)
        try { if (pttSocket != null) pttSocket.close(); }
        catch (IOException ignored) { }
        pttSocket = null;
        try { if (serialOut != null) serialOut.close(); }
        catch (IOException ignored) { }
        try { if (serialIn != null) serialIn.close(); }
        catch (IOException ignored) { }
        serialIn = null;
        serialOut = null;
        pttIn = null;
        pttOut = null;
    }

    public boolean isConnected() { return connected; }
    public boolean isKeyed() { return keyed; }

    /**
     * Configure the UART at 57600 8N1 raw via the helper's "serial"
     * command (the helper binary sets B57600). No root, no stty needed.
     */
    private boolean configureSerial() {
        return pttCmd("serial");
    }

    // ------------------------------------------------------------------
    // Serial I/O
    // ------------------------------------------------------------------

    /** Write one frame to the module. */
    public synchronized boolean sendFrame(String frame) {
        if (serialOut == null) return false;
        try {
            serialOut.write(Dmr007Frame.toBytes(frame));
            serialOut.flush();
            return true;
        } catch (IOException e) {
            reportError("serial write: " + e);
            return false;
        }
    }

    /**
     * Background scanner: find 0x68, read through the next 0x10
     * (max 200 bytes), checksum-validate, queue the reply. Spontaneous
     * 5-byte heartbeats ("XX 00 FF A1 F1") never start with 0x68, so the
     * scanner skips them naturally.
     */
    private void readLoop() {
        List<Byte> buf = new ArrayList<Byte>();
        byte[] chunk = new byte[512];
        try {
            while (connected && serialIn != null) {
                int n = serialIn.read(chunk);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) {
                    int b = chunk[i] & 0xFF;
                    if (buf.isEmpty()) {
                        if (b == 0x68) buf.add(chunk[i]);
                        continue; // skip non-frame bytes (heartbeats)
                    }
                    buf.add(chunk[i]);
                    if (b == 0x10 || buf.size() >= 200) {
                        byte[] raw = new byte[buf.size()];
                        for (int j = 0; j < raw.length; j++)
                            raw[j] = buf.get(j);
                        buf.clear();
                        int cmd = Dmr007Frame.commandOf(
                                Dmr007Frame.toHexString(raw));
                        if (cmd < 0) continue;
                        Reply r = new Reply(cmd, raw);
                        replyQueue.offer(r);
                        if (callback != null)
                            callback.onResponse(r.hex);
                    }
                }
            }
        } catch (IOException e) {
            if (connected) reportError("serial read: " + e);
        }
    }

    /** Discard anything already in flight (boot indicator, stale bytes). */
    private void drain(long ms) {
        long end = System.currentTimeMillis() + ms;
        try {
            while (System.currentTimeMillis() < end) {
                Reply r = replyQueue.poll(
                        end - System.currentTimeMillis(),
                        TimeUnit.MILLISECONDS);
                if (r == null) break;
            }
        } catch (InterruptedException ignored) { }
    }

    /** Wait for a reply matching the predicate. */
    private Reply awaitReply(Matcher m, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < end) {
                Reply r = replyQueue.poll(
                        end - System.currentTimeMillis(),
                        TimeUnit.MILLISECONDS);
                if (r == null) break;
                if (m.matches(r)) return r;
            }
        } catch (InterruptedException ignored) { }
        return null;
    }

    private interface Matcher {
        boolean matches(Reply r);
    }

    // ------------------------------------------------------------------
    // High-level operations
    // ------------------------------------------------------------------

    /**
     * Program the module's channel. Digital -> 68 36, analog -> 68 35.
     * Awaits the module's 68 36/35 00 00 SUCCESS reply (5 s).
     */
    public boolean programChannel(Channel ch) {
        if (!connected) return false;
        final int want = ch.isDigital ? 0x36 : 0x35;
        String frame = ch.buildProgramFrame();
        drain(300);
        if (!sendFrame(frame)) return false;
        Reply r = awaitReply(new Matcher() {
            @Override public boolean matches(Reply r) {
                return r.cmd == want;
            }
        }, 5000);
        boolean ok = r != null && r.isSuccess()
                && Dmr007Frame.validateReply(r.hex);
        if (!ok) reportError("programChannel: no SUCCESS reply"
                + (r == null ? "" : " (got " + r.hex + ")"));
        return ok;
    }

    /**
     * Key the transmitter. Sends the 68 06 start frame, then fires the
     * KEY_DOWN ioctl IMMEDIATELY -- the module's 68 06 02 61 "sendstart"
     * notification only arrives ~0.5 s AFTER keying (it is the module's
     * response to the key, not a precondition), so waiting for it first
     * would add a 2 s PTT latency. The notification still surfaces via
     * the reader-thread callback when it arrives. (Decompilation note:
     * the module's immediate reply to the start frame is
     * 68 06 00 01 ...; the stock app's onReceive classifies sr="01" as
     * "busy" and retries -- we simply ignore the ACK and key.)
     */
    public synchronized boolean pttDown() {
        if (!connected || keyed) return keyed;
        drain(300);
        if (!sendFrame(Dmr007Frame.buildSend("01", "0", "0"))) return false;
        boolean ok = pttCmd("down " + PTT_VAL);
        keyed = ok;
        if (callback != null)
            callback.onResponse("PTT DOWN (keyed; sendstart notification"
                    + " follows ~0.5 s later)");
        return ok;
    }

    /** Unkey: KEY_UP ioctl, then the 68 06 stop frame. */
    public synchronized boolean pttUp() {
        if (pttIn == null) return false;
        boolean ok = pttCmd("up " + PTT_VAL);
        sendFrame(Dmr007Frame.buildSend("FF", "0", "0"));
        if (ok) keyed = false;
        return ok;
    }

    /**
     * Send a DMR text message. The module transmits the SMS itself;
     * no PTT cycle needed. Awaits the 68 07 00 00 SUCCESS reply.
     * (Verified live 2026-10-11 run 7/16, SDR-confirmed bursts.)
     */
    public boolean sendSms(String callnumber, String text) {
        if (!connected) return false;
        drain(300);
        if (!sendFrame(Dmr007Frame.buildSms("2", callnumber, text)))
            return false;
        Reply r = awaitReply(new Matcher() {
            @Override public boolean matches(Reply r) {
                return r.cmd == 0x07;
            }
        }, 5000);
        boolean ok = r != null && r.isSuccess()
                && Dmr007Frame.validateReply(r.hex);
        if (!ok) reportError("sendSms: no SUCCESS reply");
        return ok;
    }

    /** 68 02 volume frame (1-byte volume, 0-10). No reply awaited. */
    public boolean setVolume(int level) {
        if (!connected) return false;
        return sendFrame(Dmr007Frame.buildVolume(level));
    }

    /** 68 25 version query. No parsed reply yet (module behavior TBD). */
    public boolean queryVersion() {
        if (!connected) return false;
        drain(300);
        return sendFrame(Dmr007Frame.buildQuery(0x25));
    }

    /**
     * 68 38 check-digital-channel: the module answers with the 156-byte
     * channel dump. Returns the raw payload hex, or null on timeout.
     */
    public String checkDigitalChannel() {
        if (!connected) return null;
        drain(300);
        if (!sendFrame(Dmr007Frame.buildQuery(0x38))) return null;
        Reply r = awaitReply(new Matcher() {
            @Override public boolean matches(Reply r) {
                return r.cmd == 0x38 && r.raw.length > 20;
            }
        }, 5000);
        return r == null ? null : r.hex;
    }

    // ------------------------------------------------------------------
    // Helper protocol
    // ------------------------------------------------------------------

    private synchronized boolean pttCmd(String cmd) {
        try {
            pttIn.write((cmd + "\n").getBytes("ASCII"));
            pttIn.flush();
            String line;
            while ((line = pttOut.readLine()) != null) {
                if (line.startsWith("WATCHDOG")) {
                    keyed = false;
                    continue;
                }
                return line.startsWith("OK");
            }
        } catch (IOException ignored) { }
        return false;
    }

    private void reportError(String msg) {
        if (callback != null) callback.onError(msg);
    }
}
