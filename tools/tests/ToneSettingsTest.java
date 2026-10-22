package com.rumitx.app;

/** Wire regressions: RX None must not acquire a TX tone, or vice versa. */
public final class ToneSettingsTest {
    static void check(Channel c, int rxType, int rx, int txType, int tx) {
        String[] hex = c.buildProgramFrame().split(" ");
        int[] want = {rxType, rx, txType, tx};
        for (int i = 0; i < 4; i++) {
            if (Integer.parseInt(hex[19 + i], 16) != want[i]) throw new AssertionError(c.buildProgramFrame());
        }
    }

    public static void main(String[] args) {
        Channel c = new Channel();
        c.txFreqMHz = c.rxFreqMHz = 462.6500;
        c.squelchLevel = 1;
        String none = c.buildProgramFrame();
        check(c, 1, 0, 1, 0);
        c.txCtcssHz = 100;
        check(c, 1, 0, 2, 13);
        c.rxCtcssHz = 103.5;
        check(c, 2, 14, 2, 13);
        c.txCtcssHz = 0;
        check(c, 2, 14, 1, 0);
        c.rxCtcssHz = 0;
        c.txDcsCode = "023N";
        check(c, 1, 0, 3, 1);
        c.txDcsCode = "";
        c.rxDcsCode = "023I";
        check(c, 4, 1, 1, 0);
        c.txDcsCode = "023N";
        try {
            c.buildProgramFrame();
            throw new AssertionError("conflicting polarity accepted");
        } catch (IllegalArgumentException expected) {
            // TX and RX DCS polarity must match.
        }
        c.txDcsCode = "";
        c.rxDcsCode = "";
        if (!none.equals(c.buildProgramFrame())) throw new AssertionError("no-tone frame changed");
        c.isDigital = true;
        String digital = c.buildProgramFrame();
        c.txCtcssHz = 100;
        if (!digital.equals(c.buildProgramFrame())) throw new AssertionError("DMR frame changed");
        Channel temp = AiTempChannel.create(446.1000, 446.1000, 100.0, null);
        if (temp.squelchLevel != AiTempChannel.LIVE_SQUELCH) throw new AssertionError("AI temp squelch");
        if (temp.txCtcssHz != 0 || temp.rxCtcssHz != 100.0) throw new AssertionError("AI temp tones");
        ToneIndex.Settings tones = ToneIndex.resolve(temp.txCtcssHz, temp.rxCtcssHz, temp.txDcsCode, temp.rxDcsCode);
        if (tones.txType != 1 || tones.tx != 0 || tones.rxType != 2) {
            throw new AssertionError("AI temp channel must keep an independent TX None");
        }
        System.out.println("PASS: independent tone fields, explicit None, polarity rejection, carrier mode1, CTCSS mode2, DCS modes3/4, unchanged DMR frame, live AI squelch");
    }
}
