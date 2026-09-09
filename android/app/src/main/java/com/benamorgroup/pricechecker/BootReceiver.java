package com.benamorgroup.pricechecker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Starts the Price Checker automatically after the tablet reboots
 * (only when enabled in the hidden admin menu, off by default).
 * <p>
 * Android 10+ restricts starting activities from the background. The launch
 * succeeds when at least one of the following is true:
 *  - the app is Device Owner (recommended kiosk setup — see README),
 *  - the app has the SYSTEM_ALERT_WINDOW app-op allowed (one ADB command),
 *  - the app is set as the HOME (default launcher).
 * If the start is blocked, the receiver logs it and does nothing harmful.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BenAmorKiosk";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(App.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("autoBoot", false)) {
            return; // disabled — nothing to do
        }
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            Log.i(TAG, "Auto-started after boot");
        } catch (Throwable t) {
            Log.w(TAG, "Boot auto-start blocked by Android. "
                    + "Set the app as Device Owner, allow the SYSTEM_ALERT_WINDOW "
                    + "app-op, or set it as the HOME launcher. See README.", t);
        }
    }
}
