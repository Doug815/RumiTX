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
        // RadioController.connect() handles TCP
        // We need to expose the connection state
        Log.i(TAG, "openTcp: delegating to RadioController");
        // The actual connect is done in MainActivity; this is a placeholder
        // for the coordinator's state machine
    }

    @Override
    public String readGreeting() throws Exception {
        // Greeting is read during connect()
        return "READY"; // Placeholder - actual greeting verified in connect()
    }

    @Override
    public boolean appStart() throws Exception {
        // Already done via RadioController.connect()
        Log.i(TAG, "appStart: already handled by RadioController.connect()");
        return true;
    }

    @Override
    public boolean configureSerial() throws Exception {
        // Already done via RadioController.connect()
        Log.i(TAG, "configureSerial: already handled by RadioController.connect()");
        return true;
    }

    @Override
    public void openUart() throws Exception {
        // Already done via RadioController.connect()
        Log.i(TAG, "openUart: already handled by RadioController.connect()");
    }

    @Override
    public boolean queryModule() throws Exception {
        // TODO: Implement a safe read-only query
        Log.i(TAG, "queryModule: not yet implemented, skipping");
        return true; // Skip for now
    }
}
