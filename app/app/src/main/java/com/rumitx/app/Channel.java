package com.rumitx.app;

/**
 * RumiTX Channel model.
 * Supports split TX/RX frequencies for repeater-style operation.
 * All tone/code values use DIRECT ENTRY (actual values, not chart indices).
 */
public class Channel {
    public long id;
    public String name;

    // Split frequencies — the headline RumiTX feature
    public double txFreqMHz;   // e.g., 446.00625
    public double rxFreqMHz;   // e.g., 446.09375 (can differ from TX)

    // Analog FM
    public double txCtcssHz;   // 0 = none, else 67.0-254.1 (direct entry)
    public double rxCtcssHz;   // 0 = none
    public String txDcsCode;   // null = none, else "023" (direct entry)
    public String rxDcsCode;   // null = none

    // DMR Digital
    public boolean isDigital;
    public int colorCode;      // 0-15 (direct entry)
    public int talkgroup;      // direct entry
    public int timeslot;       // 1 or 2

    // DMR007 module fields (68 36 frame; defaults = verified CH36 values)
    public String phoneId = "1";    // radio's own DMR ID
    public int callType = 0;        // 0 = group call, 4 = private call
    public String flag = "1";       // 68 36 flag byte (semantics TBD)
    public String encry = "0";      // "0" = encryption off
    public String secretKey = "0";  // 8-byte key, zeros when encry off
    public String rxGroups = "01";  // ";"-separated RX group list
                                    // (pad 1-digit groups: "1" -> "01")
    public String msgType = "2";    // 68 07 SMS msgType (stock DB value)

    public int bandwidthKhz;   // 12.5 or 25
    public int squelchLevel;   // 0-9
    public int volume;         // 0-10

    public Channel() {
        txFreqMHz = 446.00625;
        rxFreqMHz = 446.00625;
        bandwidthKhz = 12;
        squelchLevel = 4;
        volume = 5;
        isDigital = false;
        colorCode = 1;
        talkgroup = 1;
        timeslot = 1;
    }

    /**
     * Build the DMR007 binary channel-programming frame for this channel.
     * Digital -> 68 36, analog -> 68 35. Replaces the old AT+DMOSETGROUP
     * text command (the module only speaks the binary protocol).
     */
    public String buildProgramFrame() {
        if (isDigital) {
            return Dmr007Frame.buildSetGroupDigital(
                    txFreqMHz, rxFreqMHz, flag, phoneId,
                    String.valueOf(colorCode), String.valueOf(callType),
                    String.valueOf(talkgroup), encry, secretKey, rxGroups);
        }
        // Analog 68 35: stock wrote one freq to both fields (simplex);
        // the frame carries both, so split is expressible (untested).
        int gwb = (bandwidthKhz == 25) ? 1 : 0;
        return Dmr007Frame.buildSetGroupAnalog(
                txFreqMHz, rxFreqMHz, String.valueOf(gwb), "0",
                String.valueOf(squelchLevel), "0", "0", "0", "0");
    }

    public boolean isSplitFrequency() {
        return Math.abs(txFreqMHz - rxFreqMHz) > 0.00001;
    }
}
