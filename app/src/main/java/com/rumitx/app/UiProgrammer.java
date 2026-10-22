package com.rumitx.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/**
 * PROGRAM_CHANNEL broadcast used by adb. Kept out of the activity so the UI,
 * the local API, and the model share one programming idea: program, do not key.
 */
public final class UiProgrammer {
    public interface Host {
        ChannelDatabase database();
        void refresh();
        void activate(Channel channel);
        void toast(String message);
    }

    private UiProgrammer() {}

    public static BroadcastReceiver receiver(final Host host) {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (VoiceTxBridge.isTransmitting()) {
                    RadioLog.i("RumiTX-TX", "PROGRAM_CHANNEL blocked during voice TX");
                    return;
                }
                try {
                    Channel ch = new Channel();
                    ch.name = intent.getStringExtra("name");
                    if (ch.name == null) ch.name = "Remote";
                    ch.txFreqMHz = Double.parseDouble(intent.getStringExtra("tx"));
                    String rx = intent.getStringExtra("rx");
                    ch.rxFreqMHz = (rx != null) ? Double.parseDouble(rx) : ch.txFreqMHz;
                    ch.isDigital = !"0".equals(intent.getStringExtra("digital"));
                    String cc = intent.getStringExtra("cc");
                    if (cc != null) ch.colorCode = Integer.parseInt(cc);
                    String tg = intent.getStringExtra("tg");
                    if (tg != null) ch.talkgroup = Integer.parseInt(tg);
                    ch.encry = "0";
                    ch.secretKey = "0";
                    host.database().saveChannel(ch);
                    host.refresh();
                    if (!"0".equals(intent.getStringExtra("activate"))) host.activate(ch);
                    host.toast("Programmed: " + ch.name);
                } catch (Exception e) {
                    host.toast("Remote program failed: " + e.getMessage());
                }
            }
        };
    }
}
