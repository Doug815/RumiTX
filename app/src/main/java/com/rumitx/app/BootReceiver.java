package com.rumitx.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the foreground radio and agent API after boot. The app must have been opened once. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || context == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            RadioService.start(context);
        }
    }
}
