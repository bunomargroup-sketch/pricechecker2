package com.benamorgroup.pricechecker;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Lock Task (kiosk) helper.
 * <p>
 * Two levels of kiosk are possible:
 *
 * 1. SCREEN PINNING (no setup): {@link Activity#startLockTask()} shows a
 *    system confirmation, then the device is pinned to the app. Exit by
 *    holding Back + Recents — or through the hidden admin menu.
 *
 * 2. TRUE LOCK TASK (app is Device Owner, set once via ADB — see README):
 *    no confirmation dialog, Home/Recents are blocked by the system, and
 *    only the packages whitelisted in {@link Config#LOCK_TASK_PACKAGES}
 *    can run on top (Wi-Fi settings, print dialog, file picker, WhatsApp).
 */
public final class KioskManager {

    private static final String TAG = "BenAmorKiosk";

    private KioskManager() {
        // no instances
    }

    static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static ComponentName adminComponent(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }

    /** True only if this app was made Device Owner with {@code adb shell dpm set-device-owner}. */
    static boolean isDeviceOwner(Context context) {
        try {
            DevicePolicyManager dpm = dpm(context);
            return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * While the app is Device Owner, allow this package (and the helpers in
     * {@link Config#LOCK_TASK_PACKAGES}) to participate in Lock Task mode.
     */
    private static void applyLockTaskPolicy(Context context) {
        if (!isDeviceOwner(context)) {
            return;
        }
        try {
            dpm(context).setLockTaskPackages(
                    adminComponent(context), Config.LOCK_TASK_PACKAGES);
        } catch (Throwable t) {
            Log.w(TAG, "setLockTaskPackages failed", t);
        }
    }

    /** Enter kiosk mode (screen pinning or true Lock Task). */
    static boolean startKiosk(Activity activity) {
        applyLockTaskPolicy(activity);
        try {
            activity.startLockTask();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startLockTask failed", t);
            return false;
        }
    }

    /** Leave kiosk mode. Safe to call even when not pinned. */
    static void stopKiosk(Activity activity) {
        try {
            activity.stopLockTask();
        } catch (Throwable ignored) {
            // not currently pinned — fine
        }
    }

    /** Safely remove Device Owner status (admin action / recovery). */
    static boolean clearDeviceOwner(Context context) {
        try {
            dpm(context).clearDeviceOwnerApp(context.getPackageName());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "clearDeviceOwnerApp failed", t);
            return false;
        }
    }
}
