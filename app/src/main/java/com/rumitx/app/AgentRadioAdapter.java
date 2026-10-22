package com.rumitx.app;

import android.content.Context;
import android.os.BatteryManager;

import com.rumitx.app.agent.LlamaRuntime;
import com.rumitx.app.agent.RadioPort;
import com.rumitx.app.repeaters.RepeaterProfiles;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The only class that turns agent commands into RadioController and VoiceTxBridge calls.
 * Programming forces encryption off. Activating a saved row programs that row as stored.
 */
public final class AgentRadioAdapter implements RadioPort {
    private final Context app;
    private final RadioController radio;
    private volatile VoiceTxBridge bridge;
    private volatile boolean scanning;
    private volatile int volume = -1;
    private Thread scanThread;

    public AgentRadioAdapter(Context app, RadioController radio, VoiceTxBridge bridge) {
        this.app = app.getApplicationContext();
        this.radio = radio;
        this.bridge = bridge;
    }

    public void setBridge(VoiceTxBridge bridge) { this.bridge = bridge; }

    @Override public Status status() {
        Status status = new Status();
        Channel live = radio.currentChannel();
        status.connected = radio.isConnected();
        status.keyed = radio.isKeyed() || VoiceTxBridge.isTransmitting();
        status.receiving = radio.isReceiving();
        status.squelchOpen = radio.isReceiving();
        status.scanning = scanning;
        status.modelLoaded = LlamaRuntime.loaded();
        status.batteryPercent = battery();
        status.volume = volume;
        status.rssi = null;
        status.helper = RadioService.status() == null ? "" : RadioService.status();
        status.channel = live == null ? null : toRef(live, indexOf(live));
        return status;
    }

    @Override public List<ChannelRef> channels() {
        ChannelDatabase db = new ChannelDatabase(app);
        try {
            List<Channel> rows = db.getAllChannels();
            List<ChannelRef> out = new ArrayList<ChannelRef>();
            for (int i = 0; i < rows.size(); i++) out.add(toRef(rows.get(i), i + 1));
            return out;
        } finally {
            db.close();
        }
    }

    @Override public ChannelRef current() {
        Channel live = radio.currentChannel();
        return live == null ? null : toRef(live, indexOf(live));
    }

    @Override public ChannelRef findById(long id) {
        for (ChannelRef ref : channels()) if (ref.id == id) return ref;
        return null;
    }

    @Override public ChannelRef findByIndex(int oneBased) {
        List<ChannelRef> rows = channels();
        if (oneBased < 1 || oneBased > rows.size()) return null;
        return rows.get(oneBased - 1);
    }

    @Override public ChannelRef findByName(String name) {
        if (name == null) return null;
        ChannelRef match = null;
        for (ChannelRef ref : channels()) {
            if (ref.name != null && ref.name.equalsIgnoreCase(name)) {
                if (match != null) return null;
                match = ref;
            }
        }
        return match;
    }

    @Override public boolean program(ChannelSpec spec) {
        scanStop();
        Channel ch = new Channel();
        ch.name = spec.name == null || spec.name.trim().isEmpty() ? "Agent" : spec.name.trim();
        ch.txFreqMHz = spec.txMhz;
        ch.rxFreqMHz = spec.rxMhz;
        ch.isDigital = spec.digital;
        ch.colorCode = spec.colorCode;
        ch.talkgroup = spec.talkgroup;
        ch.timeslot = spec.timeslot <= 0 ? 1 : spec.timeslot;
        ch.txCtcssHz = spec.txCtcss;
        ch.rxCtcssHz = spec.rxCtcss;
        ch.txDcsCode = spec.txDcs;
        ch.rxDcsCode = spec.rxDcs;
        ch.squelchLevel = spec.squelch;
        ch.bandwidthKhz = spec.bandwidthKhz <= 0 ? 12 : spec.bandwidthKhz;
        ch.encry = "0";
        ch.secretKey = "0";
        String blocked = RepeaterProfiles.blockedChannel(ch);
        if (blocked != null) throw new IllegalArgumentException(blocked);
        if (spec.save) {
            ChannelDatabase db = new ChannelDatabase(app);
            try { ch.id = db.saveChannel(ch); }
            finally { db.close(); }
        }
        boolean ok = radio.programChannel(ch);
        if (ok) remember(ch.id);
        return ok;
    }

    @Override public boolean activate(ChannelRef channel) {
        if (channel == null) return false;
        scanStop();
        if (VoiceTxBridge.isTransmitting() || radio.isKeyed()) return false;
        ChannelDatabase db = new ChannelDatabase(app);
        try {
            Channel found = null;
            for (Channel row : db.getAllChannels()) {
                if (row.id == channel.id) { found = row; break; }
            }
            if (found == null) return false;
            String blocked = RepeaterProfiles.blockedChannel(found);
            if (blocked != null) throw new IllegalArgumentException(blocked);
            boolean ok = radio.programChannel(found);
            if (ok) remember(found.id);
            return ok;
        } finally {
            db.close();
        }
    }

    @Override public boolean setVolume(int level) {
        boolean ok = radio.setVolume(level);
        if (ok) volume = level;
        return ok;
    }

    @Override public boolean scanStart(final double fromMhz, final double toMhz, final double stepMhz) {
        if (radio.isKeyed() || VoiceTxBridge.isTransmitting()) return false;
        scanStop();
        scanning = true;
        scanThread = new Thread(new Runnable() {
            @Override public void run() {
                double freq = fromMhz;
                int steps = 0;
                while (scanning && freq <= toMhz + 0.000001 && steps < 500) {
                    if (radio.isKeyed() || VoiceTxBridge.isTransmitting()) break;
                    Channel probe = new Channel();
                    probe.name = "Scan";
                    probe.txFreqMHz = freq;
                    probe.rxFreqMHz = freq;
                    probe.isDigital = false;
                    probe.squelchLevel = AiTempChannel.LIVE_SQUELCH;
                    probe.encry = "0";
                    probe.secretKey = "0";
                    try {
                        if (!radio.programChannel(probe)) break;
                    } catch (RuntimeException e) {
                        RadioLog.e("RumiTX-Scan", "scan step failed", e);
                        break;
                    }
                    try { Thread.sleep(300); }
                    catch (InterruptedException e) { break; }
                    if (radio.isReceiving()) break;
                    freq += stepMhz;
                    steps++;
                }
                scanning = false;
            }
        }, "RumiTX-agent-scan");
        scanThread.setDaemon(true);
        scanThread.start();
        return true;
    }

    @Override public boolean scanStop() {
        scanning = false;
        Thread thread = scanThread;
        if (thread != null) thread.interrupt();
        return true;
    }

    @Override public boolean keyDown() {
        if (scanning || VoiceTxBridge.isTransmitting()) return false;
        boolean ok = radio.pttDown();
        if (ok) RadioService.noteTx(true);
        return ok;
    }

    @Override public boolean keyUp() {
        boolean ok;
        try { ok = radio.pttUp(); }
        catch (RuntimeException e) { ok = false; }
        RadioService.noteTx(false);
        return ok;
    }

    @Override public String playAudio(byte[] wav, ChannelRef channel) {
        VoiceTxBridge active = bridge;
        if (active == null) throw new IllegalStateException("voice bridge is not running");
        if (wav == null || channel == null) throw new IllegalArgumentException("audio and channel are required");
        Channel use = channelForAudio(channel);
        File dir = new File(app.getFilesDir(), "agent-audio");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot store agent audio");
        File wavFile = new File(dir, "agent-tx.wav");
        try {
            FileOutputStream out = new FileOutputStream(wavFile);
            try { out.write(wav); out.getFD().sync(); }
            finally { out.close(); }
        } catch (Exception e) {
            throw new IllegalStateException("cannot write agent audio");
        }
        try {
            return active.submitLocal(wavFile, use);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage() == null ? "audio failed" : e.getMessage());
        }
    }

    @Override public String complete(String message) { return LlamaRuntime.complete(message); }

    @Override public List<String> logs() {
        String snap = RadioLog.snapshot();
        String[] parts = snap.split("\n");
        List<String> lines = new ArrayList<String>();
        int start = Math.max(0, parts.length - 200);
        for (int i = start; i < parts.length; i++) {
            if (parts[i] != null && parts[i].length() > 0) lines.add(parts[i]);
        }
        return lines;
    }

    private Channel channelForAudio(ChannelRef channel) {
        Channel live = radio.currentChannel();
        if (live != null
                && Math.abs(live.txFreqMHz - channel.txMhz) <= 0.00002
                && Math.abs(live.rxFreqMHz - channel.rxMhz) <= 0.00002) {
            return live;
        }
        Channel built = new Channel();
        built.id = channel.id;
        built.name = channel.name;
        built.txFreqMHz = channel.txMhz;
        built.rxFreqMHz = channel.rxMhz;
        built.isDigital = channel.digital;
        built.colorCode = channel.colorCode;
        built.talkgroup = channel.talkgroup;
        built.squelchLevel = channel.squelch;
        built.encry = "0";
        built.secretKey = "0";
        return built;
    }

    private void remember(long id) {
        app.getSharedPreferences("radio_session", 0).edit()
                .putLong("active_channel", id > 0 ? id : -1).apply();
    }

    private int indexOf(Channel live) {
        List<ChannelRef> rows = channels();
        for (int i = 0; i < rows.size(); i++) {
            if (live.id > 0 && rows.get(i).id == live.id) return i + 1;
        }
        return 0;
    }

    private int battery() {
        try {
            BatteryManager manager = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
            if (manager == null) return -1;
            return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static ChannelRef toRef(Channel channel, int index) {
        ChannelRef ref = new ChannelRef();
        ref.id = channel.id;
        ref.index = index;
        ref.name = channel.name == null ? "" : channel.name;
        ref.txMhz = channel.txFreqMHz;
        ref.rxMhz = channel.rxFreqMHz;
        ref.digital = channel.isDigital;
        ref.encrypted = encrypted(channel.encry);
        ref.squelch = channel.squelchLevel;
        ref.colorCode = channel.colorCode;
        ref.talkgroup = channel.talkgroup;
        return ref;
    }

    private static boolean encrypted(String encry) {
        if (encry == null) return false;
        String value = encry.trim();
        return value.length() > 0 && !value.equals("0") && !value.equals("00");
    }
}
