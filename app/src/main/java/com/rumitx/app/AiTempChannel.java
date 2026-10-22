package com.rumitx.app;

import java.util.Locale;

/** Temporary analog channel used by on-device AI tune commands. Squelch stays open (level 1). */
public final class AiTempChannel {
    public static final int LIVE_SQUELCH = 1;

    private AiTempChannel() {}

    public static Channel create(double rxMhz, double txMhz, double plHz, String dcs) {
        Channel temp = new Channel();
        temp.id = 0;
        temp.name = String.format(Locale.US, "Temporary %.4f", rxMhz);
        temp.rxFreqMHz = rxMhz;
        temp.txFreqMHz = txMhz;
        temp.isDigital = false;
        temp.rxCtcssHz = plHz;
        temp.txCtcssHz = 0;
        temp.rxDcsCode = dcs;
        temp.txDcsCode = null;
        temp.squelchLevel = LIVE_SQUELCH;
        return temp;
    }
}
