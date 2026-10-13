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
    private final StockAudioRoute audio;
    public HidlAdapter(android.content.Context context){audio=new StockAudioRoute(context);}
    public void closeAudio(){audio.close();}
    private volatile java.util.function.IntConsumer rxListener;
    public void setRxListener(java.util.function.IntConsumer listener){rxListener=listener;}

    @Override
    public boolean findService() throws Exception {
        boolean found = client.findService();
        RadioLog.i(TAG, "findService: " + found);
        return found;
    }

    @Override
    public int init() throws Exception {
        Integer result = client.init();
        if (result == null) throw new Exception("init returned null");
        RadioLog.i(TAG, "init returned: " + result);
        return result;
    }

    @Override
    public int appStart() throws Exception {
        // Should NOT be called when helper owns APP_START
        throw new UnsupportedOperationException("Helper owns APP_START");
    }

    @Override
    public void submitSpeakEnable(BootCoordinator.Callback callback) throws Exception {
        audio.prepare();
        client.submitSpeakEnable(value -> {
            RadioLog.i(TAG, "reportResults: " + value);
            callback.reportResults(value);
            audio.onDriverResult(value);
            java.util.function.IntConsumer listener=rxListener;if(listener!=null)listener.accept(value);
        });
    }

    public int callbackTransactions(){return client.callbackTransactions();}

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
