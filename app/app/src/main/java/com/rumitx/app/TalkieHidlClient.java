package com.rumitx.app;

import android.util.Log;

/**
 * HIDL client for vendor.hct.hardware.talkie@1.0::ITalkiePort.
 * Uses reflection to avoid compile-time dependency on HIDL classes.
 * Transaction IDs recovered from stock HcTTalkie.vdex.
 */
public class TalkieHidlClient {
    private static final String TAG = "RumiTX-HIDL";
    private static final String IFACE = "vendor.hct.hardware.talkie@1.0::ITalkiePort";
    private static final String SERVICE_NAME = "default";

    // Transaction IDs from stock vdex
    private static final int TRANSACTION_INIT = 1;
    private static final int TRANSACTION_APP_START = 3;
    private static final int TRANSACTION_SPEAK_ENABLE = 9;

    private Object service; // IHwBinder proxy

    /** Find the HIDL service. Returns true if found. */
    public boolean findService() {
        try {
            Class<?> hwBinderClass = Class.forName("android.os.HwBinder");
            java.lang.reflect.Method getService = hwBinderClass.getMethod(
                "getService", String.class, String.class);
            service = getService.invoke(null, IFACE, SERVICE_NAME);
            Log.i(TAG, "findService: " + (service != null ? "FOUND " + service.getClass().getName() : "null"));
            return service != null;
        } catch (Exception e) {
            Log.e(TAG, "findService failed", e);
            return false;
        }
    }

    /** Call talkie_init() via transact. Returns the int32 result, or null on failure. */
    public Integer init() {
        try {
            Object reply = transact(TRANSACTION_INIT, 0);
            if (reply == null) return null;
            // Read int32 from reply parcel
            Class<?> hwParcelClass = Class.forName("android.os.HwParcel");
            java.lang.reflect.Method readInt32 = hwParcelClass.getMethod("readInt32");
            Integer result = (Integer) readInt32.invoke(reply);
            Log.i(TAG, "talkie_init returned: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "talkie_init failed", e);
            return null;
        }
    }

    /** Call talkie_app_start() via transact. Returns the int32 result, or null on failure. */
    public Integer appStart() {
        try {
            Object reply = transact(TRANSACTION_APP_START, 0);
            if (reply == null) return null;
            Class<?> hwParcelClass = Class.forName("android.os.HwParcel");
            java.lang.reflect.Method readInt32 = hwParcelClass.getMethod("readInt32");
            Integer result = (Integer) readInt32.invoke(reply);
            Log.i(TAG, "talkie_app_start returned: " + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "talkie_app_start failed", e);
            return null;
        }
    }

    /** Submit talkie_speak_enable (oneway, transaction 9) with a callback. */
    public void submitSpeakEnable(SpeakEnableCallback callback) throws Exception {
        // For oneway, we need to pass the callback binder in the request parcel
        // This is complex - for now, submit without callback and log
        // TODO: Implement proper callback binder
        Log.i(TAG, "submitSpeakEnable: oneway transaction 9 (callback not yet implemented)");
        Object reply = transact(TRANSACTION_SPEAK_ENABLE, 1); // 1 = oneway flag
        Log.i(TAG, "speak_enable submitted (oneway)");
        // Note: callback will not fire without proper binder implementation
    }

    /** Call talkie_speak_disable() via transact. */
    public Integer speakDisable() {
        try {
            Object reply = transact(7, 0); // transaction 7 = speak_disable
            if (reply == null) return null;
            Class<?> hwParcelClass = Class.forName("android.os.HwParcel");
            java.lang.reflect.Method readInt32 = hwParcelClass.getMethod("readInt32");
            return (Integer) readInt32.invoke(reply);
        } catch (Exception e) {
            Log.e(TAG, "speak_disable failed", e);
            return null;
        }
    }

    /** Call talkie_app_exit() via transact. */
    public Integer appExit() {
        try {
            Object reply = transact(4, 0); // transaction 4 = app_exit
            if (reply == null) return null;
            Class<?> hwParcelClass = Class.forName("android.os.HwParcel");
            java.lang.reflect.Method readInt32 = hwParcelClass.getMethod("readInt32");
            return (Integer) readInt32.invoke(reply);
        } catch (Exception e) {
            Log.e(TAG, "app_exit failed", e);
            return null;
        }
    }

    /** Call talkie_deinit() via transact. */
    public Integer deinit() {
        try {
            Object reply = transact(2, 0); // transaction 2 = deinit
            if (reply == null) return null;
            Class<?> hwParcelClass = Class.forName("android.os.HwParcel");
            java.lang.reflect.Method readInt32 = hwParcelClass.getMethod("readInt32");
            return (Integer) readInt32.invoke(reply);
        } catch (Exception e) {
            Log.e(TAG, "deinit failed", e);
            return null;
        }
    }

    public interface SpeakEnableCallback {
        void onResult(int value);
    }

    /**
     * Perform a HIDL transact call.
     * @param code Transaction ID
     * @param flags 0 for normal, 1 for oneway
     * @return Reply parcel, or null on failure
     */
    private Object transact(int code, int flags) throws Exception {
        if (service == null) throw new IllegalStateException("Service not found");

        Class<?> ihwBinder = Class.forName("android.os.IHwBinder");
        Class<?> hwParcelClass = Class.forName("android.os.HwParcel");

        java.lang.reflect.Method transactMethod = ihwBinder.getMethod(
            "transact", int.class, hwParcelClass, hwParcelClass, int.class);
        java.lang.reflect.Method writeToken =
            hwParcelClass.getMethod("writeInterfaceToken", String.class);

        Object request = hwParcelClass.newInstance();
        writeToken.invoke(request, IFACE);

        Object reply = hwParcelClass.newInstance();
        transactMethod.invoke(service, code, request, reply, flags);

        return reply;
    }
}
