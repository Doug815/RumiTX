package com.rumitx.app;

import android.util.Log;
import com.rumitx.recovery.BootCoordinator;

/**
 * HIDL adapter for BootCoordinator.
 * Implements the ITalkiePort interface via reflection/transact.
 * Follows Astra's rule: helper owns APP_START, HIDL does init + speak_enable only.
 */
public class HidlAdapter implements BootCoordinator.Hidl {
    private static final String TAG = "RumiTX-HIDL";
    private final TalkieHidlClient client = new TalkieHidlClient();

    @Override
    public boolean findService() throws Exception {
        boolean found = client.findService();
        Log.i(TAG, "findService: " + found);
        return found;
    }

    @Override
    public int init() throws Exception {
        Integer result = client.init();
        if (result == null) throw new Exception("init returned null");
        Log.i(TAG, "init returned: " + result);
        return result;
    }

    @Override
    public int appStart() throws Exception {
        // Should NOT be called when helper owns APP_START
        throw new UnsupportedOperationException("Helper owns APP_START");
    }

    @Override
    public void submitSpeakEnable(BootCoordinator.Callback callback) throws Exception {
        client.submitSpeakEnable(value -> {
            Log.i(TAG, "reportResults: " + value);
            callback.reportResults(value);
        });
    }

    @Override
    public int speakDisable() throws Exception {
        Integer result = client.speakDisable();
        return result != null ? result : -1;
    }

    @Override
    public int appExit() throws Exception {
        Integer result = client.appExit();
        return result != null ? result : -1;
    }

    @Override
    public int deinit() throws Exception {
        Integer result = client.deinit();
        return result != null ? result : -1;
    }
}
