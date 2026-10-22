package com.rumitx.app.agent;

import java.util.List;

/** Hardware boundary. Unit tests supply a fake. The Android adapter is the only real radio caller. */
public interface RadioPort {
    Status status();

    List<ChannelRef> channels();

    ChannelRef current();

    ChannelRef findById(long id);

    ChannelRef findByIndex(int oneBased);

    ChannelRef findByName(String name);

    boolean program(ChannelSpec spec);

    boolean activate(ChannelRef channel);

    boolean setVolume(int level);

    boolean scanStart(double fromMhz, double toMhz, double stepMhz);

    boolean scanStop();

    boolean keyDown();

    boolean keyUp();

    /** Plays WAV through the in-process VoiceTxBridge path and unkeys before returning. */
    String playAudio(byte[] wav, ChannelRef channel);

    String complete(String message);

    List<String> logs();

    final class Status {
        public boolean connected;
        public boolean keyed;
        public boolean receiving;
        public boolean scanning;
        public boolean squelchOpen;
        public boolean modelLoaded;
        public int batteryPercent = -1;
        public int volume = -1;
        public Double rssi;
        public String helper = "";
        public ChannelRef channel;
    }

    final class ChannelRef {
        public long id;
        public int index;
        public String name = "";
        public double txMhz;
        public double rxMhz;
        public boolean digital;
        public boolean encrypted;
        public int squelch;
        public int colorCode;
        public int talkgroup;

        public String json() {
            return new Json.Obj()
                    .put("id", id)
                    .put("index", index)
                    .put("name", name == null ? "" : name)
                    .put("tx_mhz", txMhz)
                    .put("rx_mhz", rxMhz)
                    .put("digital", digital)
                    .put("encrypted", encrypted)
                    .put("squelch", squelch)
                    .put("color_code", colorCode)
                    .put("talkgroup", talkgroup)
                    .toString();
        }
    }

    final class ChannelSpec {
        public String name = "Agent";
        public double txMhz;
        public double rxMhz;
        public boolean digital;
        public int colorCode = 1;
        public int talkgroup = 1;
        public int timeslot = 1;
        public double txCtcss;
        public double rxCtcss;
        public String txDcs;
        public String rxDcs;
        public int squelch = 4;
        public int bandwidthKhz = 12;
        public boolean save;
    }
}
