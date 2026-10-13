package com.rumitx.app;

import android.content.Context;
import android.media.AudioManager;

/**
 * MediaTek vendor settings recovered from stock VoiceRecorder.
 *
 * The optional analog-FM diagnostic temporarily applies ONLY the
 * existing stock close() values and restores the exact known RX values.
 * There is no experimental enum sweep, mixer ioctl, or HIDL transaction.
 */
public final class StockAudioRoute {
    private final AudioManager audio;
    private final AudioManager.OnAudioFocusChangeListener focus =
            change -> RadioLog.i("RumiTX-Audio", "focus=" + change);
    private boolean lastRxCallbackOpen;
    private boolean temporarilySuspended;

    public StockAudioRoute(Context context) {
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Reflection success does NOT prove the vendor HAL honored the value.
     * Diagnostics therefore also require receiver-side RF measurements.
     */
    private boolean parameter(String value) {
        if (audio == null) return false;
        try {
            AudioManager.class.getMethod("setParameters", String.class)
                    .invoke(audio, value);
            RadioLog.i("RumiTX-Audio", "setParameters " + value);
            return true;
        } catch (Exception e) {
            RadioLog.e("RumiTX-Audio",
                    "Vendor audio parameter failed " + value, e);
            return false;
        }
    }

    private boolean setStockRxRoute() {
        boolean ok = parameter("SET_MIC_NUM=2");
        ok = parameter("RESET_MIC2_COUNTER=0") && ok;
        ok = parameter("SET_LOOPBACK_TYPE=3, 3") && ok;
        return ok;
    }

    private boolean setStockStoppedRoute() {
        boolean ok = parameter("SET_MIC_CHOOSE=2");
        ok = parameter("SET_LOOPBACK_TYPE=0") && ok;
        ok = parameter("SET_MIC_NUM=1") && ok;
        return ok;
    }

    public synchronized void prepare() {
        if (audio == null) return;
        int result = audio.requestAudioFocus(
                focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        RadioLog.i("RumiTX-Audio",
                "Stock music audio focus result=" + result);
        parameter("SET_MIC_NUM=1");
    }

    public synchronized void onDriverResult(int value) {
        if (audio == null) return;
        lastRxCallbackOpen = value == 1;
        RadioLog.i("RumiTX-Audio",
                "Driver result=" + value + "; stock receive-open value=1");
        if (temporarilySuspended) {
            RadioLog.w("RumiTX-Audio",
                    "RX route callback deferred during analog-only probe; value=" + value);
            return;
        }
        if (value == 1) setStockRxRoute();
        else setStockStoppedRoute();
    }

    /**
     * Guarded temporary RX-route stop during an already-authorized,
     * one-shot FM PTT-only diagnostic. Must be called off UI thread.
     * Fail closed if the stock RX open callback was not observed.
     */
    public synchronized boolean suspendForAnalogTxProbe() {
        if (audio == null || temporarilySuspended || !lastRxCallbackOpen) {
            RadioLog.w("RumiTX-Audio",
                    "ANALOG_RX_ROUTE_PROBE_REJECTED: no open RX callback / already suspended");
            return false;
        }
        temporarilySuspended = true;
        boolean ok = setStockStoppedRoute();
        if (!ok) {
            temporarilySuspended = false;
            setStockRxRoute(); // best-effort rollback before any PTT
            RadioLog.e("RumiTX-Audio",
                    "ANALOG_RX_ROUTE_PROBE_ABORT: stock stop parameters failed");
            return false;
        }
        RadioLog.w("RumiTX-Audio",
                "ANALOG_RX_ROUTE_PROBE_SUSPENDED: stock stop route applied; " +
                "vendor HAL acknowledgment NOT independently verified");
        return true;
    }

    /** Restore RX route from latest HIDL callback; call AFTER PTT up. */
    public synchronized boolean restoreAfterAnalogTxProbe() {
        if (!temporarilySuspended) return true;
        temporarilySuspended = false;
        boolean ok = lastRxCallbackOpen ? setStockRxRoute()
                                        : setStockStoppedRoute();
        RadioLog.w("RumiTX-Audio",
                "ANALOG_RX_ROUTE_PROBE_RESTORE: lastRxOpen=" +
                lastRxCallbackOpen + " invoked=" + ok +
                " (receiver audio must be checked on device)");
        return ok;
    }

    public synchronized void stop() {
        temporarilySuspended = false;
        lastRxCallbackOpen = false;
        if (audio != null) setStockStoppedRoute();
    }

    public synchronized void close() {
        stop();
        if (audio != null) audio.abandonAudioFocus(focus);
    }
}
