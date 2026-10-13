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

    /** Singleton: the radio hardware has one TCP connection; all
     * activities must share it. Use getInstance(), not new. */
    private static RadioController instance;
    public static synchronized RadioController getInstance() {
        if (instance == null) instance = new RadioController();
        return instance;
    }

    /** True while the module reports receiving (recstart 0x60 seen,
     * recstop 0x6f not yet seen). Updated by the serial reader thread. */
    private volatile boolean receiving = false;
    public boolean isReceiving() { return receiving; }

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
    private volatile FileInputStream serialIn;
    private final Object serialReaderLock=new Object();
    private volatile boolean initialized;
    private FileOutputStream serialOut;
    // Note: no pttProc -- the helper is a persistent daemon started from
    // adb shell; the app talks to it via TCP (Android blocks app exec).
    private java.net.Socket pttSocket;
    private OutputStream pttIn;
    private BufferedReader pttOut;
    private Thread readerThread;
    private final BlockingQueue<Reply> replyQueue =
            new LinkedBlockingQueue<Reply>(256);
    private volatile RadioCallback callback;
    private volatile java.util.function.Consumer<String[]> smsListener;
    public void setSmsListener(java.util.function.Consumer<String[]> listener){smsListener=listener;}
    private final java.util.concurrent.ExecutorService inboxWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"RumiTX-inbox");t.setDaemon(true);return t;});
    private final java.util.concurrent.atomic.AtomicBoolean inboxPending=new java.util.concurrent.atomic.AtomicBoolean();
    public void requestInbox(){if(!inboxPending.compareAndSet(false,true))return;inboxWorker.execute(()->{try{while(connected&&keyed)Thread.sleep(100);synchronized(this){if(connected&&!keyed)sendFrame(Dmr007Frame.buildQuery(0x11));}}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{inboxPending.set(false);}});}
    private volatile boolean keyed = false;
    private volatile boolean reconnectEnabled;
    private Thread monitorThread;
    private volatile String lastChannelFrame;
    private volatile Channel lastChannel;
    public Channel currentChannel(){Channel c=lastChannel;return c==null?null:snapshot(c);}
    private volatile int lastChannelCommand;
    private volatile String identityId;
    public interface ConnectionListener {void onStatus(String status);}
    private volatile ConnectionListener connectionListener;
    public void setConnectionListener(ConnectionListener listener){connectionListener=listener;}
    public void setIdentity(String id){identityId=id;}
    public synchronized boolean applyIdentityToCurrentChannel(){if(lastChannel==null||!lastChannel.isDigital)return false;return programChannel(lastChannel);}
    private static Channel snapshot(Channel ch){Channel c=new Channel();c.id=ch.id;c.name=ch.name;c.txFreqMHz=ch.txFreqMHz;c.rxFreqMHz=ch.rxFreqMHz;c.txCtcssHz=ch.txCtcssHz;c.rxCtcssHz=ch.rxCtcssHz;c.txDcsCode=ch.txDcsCode;c.rxDcsCode=ch.rxDcsCode;c.isDigital=ch.isDigital;c.colorCode=ch.colorCode;c.talkgroup=ch.talkgroup;c.timeslot=ch.timeslot;c.phoneId=ch.phoneId;c.callType=ch.callType;c.flag=ch.flag;c.encry=ch.encry;c.secretKey=ch.secretKey;c.rxGroups=ch.rxGroups;c.bandwidthKhz=ch.bandwidthKhz;c.squelchLevel=ch.squelchLevel;return c;}
    private void connectionStatus(String status){RadioLog.i("RumiTX",status);ConnectionListener l=connectionListener;if(l!=null)l.onStatus(status);}
    private synchronized void startMonitor(){reconnectEnabled=true;if(monitorThread!=null&&monitorThread.isAlive())return;monitorThread=new Thread(()->{
        ReconnectBackoff backoff=new ReconnectBackoff();int attempt=0;
        while(reconnectEnabled&&Thread.currentThread()==monitorThread){
            if(connected){backoff.reset();attempt=0;try{Thread.sleep(2000);}catch(InterruptedException e){break;}synchronized(this){if(reconnectEnabled&&Thread.currentThread()==monitorThread&&connected&&!keyed&&!probeHelper())loseConnection("Helper disconnected");}continue;}
            long delay=backoff.nextMillis();connectionStatus("Reconnecting to radio module... retry in "+delay/1000+"s");
            try{Thread.sleep(delay);}catch(InterruptedException e){break;}
            synchronized(this){
                if(!reconnectEnabled||Thread.currentThread()!=monitorThread)break;
                if(connected)continue;
                RadioLog.i("RumiTX","Reconnect attempt "+(++attempt));
                if(connectInternal()){connectionStatus("Radio module reconnected · channel restored");backoff.reset();}
            }
        }
    },"RumiTX-helper-reconnect");monitorThread.setDaemon(true);monitorThread.start();}
    // Native helper safely returns ERR ping unknown: no ioctl, UART write, or RF.
    // A timeout/EOF is a transport failure; an ERR response proves the connection alive.
    private synchronized boolean probeHelper(){try{pttIn.write("ping\n".getBytes("ASCII"));pttIn.flush();String line;while((line=pttOut.readLine())!=null){if(line.startsWith("WATCHDOG")){keyed=false;continue;}return line.equals("ERR ping unknown")||line.startsWith("OK ping");}}catch(IOException e){RadioLog.i("RumiTX","Helper heartbeat: "+e);}return false;}
    private synchronized void closeTransport(){initialized=false;connected=false;receiving=false;keyed=false;try{if(pttSocket!=null)pttSocket.close();}catch(IOException ignored){}try{if(serialOut!=null)serialOut.close();}catch(IOException ignored){}synchronized(serialReaderLock){try{if(serialIn!=null)serialIn.close();}catch(IOException ignored){}serialIn=null;}pttSocket=null;pttIn=null;pttOut=null;serialIn=null;serialOut=null;replyQueue.clear();}
    private synchronized void loseConnection(String reason){closeTransport();if(reconnectEnabled)connectionStatus("Reconnecting to radio module... "+reason);}


    public void setCallback(RadioCallback cb) { this.callback = cb; }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    public synchronized boolean connect() {startMonitor();return connectInternal();}
    private synchronized boolean connectInternal() {
        if (connected) return true;
        if(!reconnectEnabled)return false;
        closeTransport();
        try {
            RadioLog.i("RumiTX", "connect: opening TCP to helper");
            // 1. Connect to the PTT helper daemon via TCP. The helper
            // runs as a daemon (started from adb shell, NOT spawned by
            // the app -- Android 8+ blocks apps from exec'ing binaries).
            java.net.Socket sock = new java.net.Socket();
            sock.connect(new java.net.InetSocketAddress("127.0.0.1",
                    PTT_TCP_PORT), 5000);
            sock.setSoTimeout(5000);
            pttIn = sock.getOutputStream();
            pttOut = new BufferedReader(
                    new InputStreamReader(sock.getInputStream()));
            pttSocket = sock;
            RadioLog.i("RumiTX", "connect: TCP open");
            String ready = pttOut.readLine();
            RadioLog.i("RumiTX", "connect: helper said: " + ready);
            if (ready == null || !ready.startsWith("READY")) {
                reportError("PTT helper did not print READY");
                return false;
            }

            // 2. APP_START ioctl so the module powers its serial side.
            RadioLog.i("RumiTX", "connect: sending start");
            if (!pttCmd("start")) {
                reportError("helper 'start' (APP_START) failed");
                return false;
            }
            RadioLog.i("RumiTX", "connect: start OK");

            // 3. Existing helper serial command sets 57600 8N1 raw.
            RadioLog.i("RumiTX", "connect: configuring serial");
            if (!configureSerial()) {
                reportError("could not set " + SERIAL_PORT
                        + " to 57600 8N1");
                return false;
            }
            RadioLog.i("RumiTX", "connect: serial OK");

            File f = new File(SERIAL_PORT);
            if (!f.exists()) {
                reportError(SERIAL_PORT + " missing");
                return false;
            }
            serialOut = new FileOutputStream(f);
            serialIn = new FileInputStream(f);

            // 4. Reader thread: 68..10 frame scanner + checksum check.
            connected = true;
            final FileInputStream input=serialIn;
            readerThread = new Thread(new Runnable() {
                @Override public void run() { readLoop(input); }
            }, "rumitx-serial-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // 5. Drain the boot indicator ("44 c6 b5 b5 c0 31 3b 0d 0a"
            // ~1.5 s after APP_START) so it can't pollute reply matching.
            drain(2500);
            if(lastChannelFrame!=null){
                if(lastChannel!=null&&lastChannel.isDigital&&identityId!=null){lastChannel.phoneId=identityId.isEmpty()?"1":identityId;lastChannelFrame=lastChannel.buildProgramFrame();}
                final int command=lastChannelCommand;
                if(!sendFrame(lastChannelFrame)){return false;}
                Reply restored=awaitReply(reply->reply.cmd==command,5000);
                if(restored==null||!restored.isSuccess()||!Dmr007Frame.validateReply(restored.hex)){reportError("Reconnect: channel restore not acknowledged");connected=false;return false;}
                RadioLog.i("RumiTX","Reconnect: channel configuration restored (cmd "+command+")");
            }
            initialized=true;
            return true;
        } catch (Exception e) {
            RadioLog.e("RumiTX", "connect FAILED: " + e);
            reportError("connect: " + e);
            connected = false;
            return false;
        } finally {if(!connected)closeTransport();}
    }

    public synchronized void disconnect() {
        initialized=false;reconnectEnabled=false;Thread oldMonitor=monitorThread;monitorThread=null;if(oldMonitor!=null)oldMonitor.interrupt();connectionListener=null;
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

    public boolean isConnected() { return connected && initialized; }
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
            if(Dmr007Frame.commandOf(frame)==7)RadioLog.i("RumiTX-SMS-PROBE","UART TX "+frame);
            serialOut.write(Dmr007Frame.toBytes(frame));
            serialOut.flush();
            return true;
        } catch (IOException e) {
            reportError("serial write: " + e);
            loseConnection("UART write failed");
            return false;
        }
    }

    /**
     * Background scanner: find 0x68, read through the next 0x10
     * (max 200 bytes), checksum-validate, queue the reply. Spontaneous
     * 5-byte heartbeats ("XX 00 FF A1 F1") never start with 0x68, so the
     * scanner skips them naturally.
     */
    private void readLoop(FileInputStream input) {
        List<Byte> buf = new ArrayList<Byte>();
        byte[] chunk = new byte[512];
        try {
            while (connected && input == serialIn) {
                int n=0;
                // Poll the UART instead of leaving an old reader blocked across reconnect.
                // Closing a TTY descriptor need not wake a blocking read on Linux/Android.
                synchronized(serialReaderLock){
                    if(input!=serialIn)break;
                    if(input.available()>0)n=input.read(chunk);
                }
                if(n<0){if(input==serialIn)loseConnection("UART closed");break;}
                if(n==0){try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}continue;}
                if(input!=serialIn)break;
                for (int i = 0; i < n; i++) {
                    int b = chunk[i] & 0xFF;
                    if (buf.isEmpty()) {
                        if (b == 0x68) buf.add(chunk[i]);
                        continue; // skip non-frame bytes (heartbeats)
                    }
                    buf.add(chunk[i]);
                    if(buf.size()<8)continue;
                    int payload=((buf.get(6)&255)<<8)|(buf.get(7)&255);
                    if(payload>4096){buf.clear();continue;}
                    if (buf.size() == 9+payload) {
                        byte[] raw = new byte[buf.size()];
                        for (int j = 0; j < raw.length; j++)
                            raw[j] = buf.get(j);
                        buf.clear();
                        int cmd = Dmr007Frame.commandOf(
                                Dmr007Frame.toHexString(raw));
                        if (cmd < 0) continue;
                        Reply r = new Reply(cmd, raw);
                        if(!Dmr007Frame.validateReply(r.hex))continue;
                        if(cmd==7||cmd==0x11)RadioLog.i("RumiTX-SMS-PROBE","UART RX "+r.hex);
                        if(!replyQueue.offer(r)){replyQueue.poll();replyQueue.offer(r);}
                        if(cmd==0x11){String[] message=Dmr007Frame.parseSmsNotification(raw);java.util.function.Consumer<String[]> listener=smsListener;if(message!=null&&listener!=null){Channel channel=lastChannel;try{listener.accept(new String[]{channel==null?"Unknown channel":"Channel "+channel.name,message[1]});}catch(RuntimeException deliveryError){RadioLog.e("RumiTX","Incoming message processing failed",deliveryError);}}}
                        if(cmd!=0x11 && raw.length>3 && ((raw[3]&255)==0x70||(raw[3]&255)==0x71))requestInbox();
                        // Track receive state: sr=0x60 recstart, sr=0x6f recstop
                        if (cmd==0x06 && raw.length > 3) {
                            int sr = raw[3] & 0xFF;
                            if (sr == 0x60) receiving = true;
                            else if (sr == 0x6f) receiving = false;
                        }
                        if (callback != null)
                            callback.onResponse(r.hex);
                    }
                }
            }
        } catch (IOException e) {
            if (connected && input==serialIn) {reportError("serial read: " + e);loseConnection("UART read failed");}
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
    public synchronized boolean programChannel(Channel ch) {
        if (!connected) return false;
        final int want = ch.isDigital ? 0x36 : 0x35;
        // Own ID is documented in the existing 68 36 frame. Callsign has no UART command.
        if(ch.isDigital&&identityId!=null)ch.phoneId=identityId.isEmpty()?"1":identityId;
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
        if(ok){lastChannelFrame=frame;lastChannelCommand=want;lastChannel=snapshot(ch);}
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
        final String startFrame=Dmr007Frame.buildSend("01", "0", "0");
        // Preserve the verified ordering: actual UART write, then HCT KEY_DOWN.
        if (!sendFrame(startFrame)) {RadioLog.e("RumiTX-TX","UART start write failed");return false;}
        RadioLog.i("RumiTX-TX","UART /dev/ttyMT1 start written and flushed: "+startFrame);
        if (!pttCmd("down " + PTT_VAL)) {
            sendFrame(Dmr007Frame.buildSend("FF", "0", "0"));
            RadioLog.e("RumiTX-TX","Helper down not acknowledged");
            return false;
        }
        RadioLog.i("RumiTX-TX","Helper down ACK; waiting for module sendstart (not RF proof)");
        boolean confirmed=false;
        long deadline=System.currentTimeMillis()+2000;
        int retries=0;
        while(System.currentTimeMillis()<deadline) {
            Reply reply=awaitReply(r -> r.cmd==0x06,Math.max(1,deadline-System.currentTimeMillis()));
            if(reply==null)break;
            if(!Dmr007Frame.validateReply(reply.hex))continue;
            RadioLog.i("RumiTX-TX","Module reply: "+reply.hex);
            if(reply.isSendstart()){confirmed=true;break;}
            int status=reply.raw.length>3?(reply.raw[3]&0xff):-1;
            if(status==0x01&&retries<2) {
                // Stock ReceiveDataPort retries BUSY; allow key-down to settle first.
                try{Thread.sleep(120);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                if(!sendFrame(startFrame))break;
                RadioLog.i("RumiTX-TX","BUSY: UART TX-start retry "+(++retries));
            }else if(status==0x02||status==0x07||status==0x09||status==0x62){break;}
        }
        if(!confirmed){
            RadioLog.e("RumiTX-TX","Module did not confirm TX start; rolling back KEY_DOWN");
            pttCmd("up " + PTT_VAL);
            sendFrame(Dmr007Frame.buildSend("FF", "0", "0"));
            keyed=false;
            reportError("PTT: module TX start not confirmed (see RumiTX-TX log)");
            return false;
        }
        keyed=true;
        RadioLog.i("RumiTX-TX","TX start confirmed by module; RF still requires SDR verification");
        if(callback!=null)callback.onResponse("PTT DOWN: module sendstart confirmed");
        return true;
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
    public synchronized boolean sendSms(String callnumber, String text) {
        if(callnumber==null||!callnumber.matches("[0-9]{1,8}")||Long.parseLong(callnumber)<1||Long.parseLong(callnumber)>16777215L)throw new IllegalArgumentException("DMR recipient ID must be 1..16777215");
        if(text==null||text.trim().isEmpty()||text.length()>120)throw new IllegalArgumentException("Enter 1..120 characters");
        if(lastChannel==null||!lastChannel.isDigital||keyed)throw new IllegalStateException("Select a digital channel and release PTT first");
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
        } catch (IOException e) {RadioLog.i("RumiTX","Helper command transport failed: "+e);}
        if(connected)loseConnection("Helper command connection lost");
        return false;
    }

    private void reportError(String msg) {
        if (callback != null) callback.onError(msg);
    }
}
