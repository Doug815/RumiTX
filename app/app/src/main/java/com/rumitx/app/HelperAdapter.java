package com.rumitx.app;

import android.util.Log;
import com.rumitx.recovery.BootCoordinator;

/**
 * Helper adapter for BootCoordinator.
 * Wraps the existing RadioController's daemon communication.
 */
public class HelperAdapter implements BootCoordinator.Helper {
    private static final String TAG = "RumiTX-Helper";
    private final RadioController radio;

    public HelperAdapter(RadioController radio) {
        this.radio = radio;
    }

    @Override
    public void openTcp() throws Exception {
        if (!radio.isConnected()) {
            throw new Exception("RadioController not connected - TCP handshake failed in onCreate");
        }
        Log.i(TAG, "openTcp: RadioController reports connected");
    }

    @Override
    public String readGreeting() throws Exception {
        if (!radio.isConnected()) throw new Exception("Not connected");
        return "READY";
    }

    @Override
    public boolean appStart() throws Exception {
        if (!radio.isConnected()) throw new Exception("Not connected");
        Log.i(TAG, "appStart: RadioController.connect() already sent start OK");
        return true;
    }

    @Override
    public boolean configureSerial() throws Exception {
        if (!radio.isConnected()) throw new Exception("Not connected");
        Log.i(TAG, "configureSerial: RadioController.connect() already sent serial OK");
        return true;
    }

    @Override
    public void openUart() throws Exception {
        if (!radio.isConnected()) throw new Exception("Not connected");
        Log.i(TAG, "openUart: RadioController.connect() already opened /dev/ttyMT1");
    }

    @Override
    public boolean queryModule() throws Exception {
        Log.i(TAG, "queryModule: skipped (no safe query established yet)");
        return true;
    }
}
