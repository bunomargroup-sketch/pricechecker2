package com.benamorgroup.pricechecker;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * Application class.
 * <p>
 * Its only job is crash recovery: if the app process dies from an unhandled
 * exception, schedule an automatic restart a second and a half later, so the
 * tablet never stays on a blank/home screen. A rolling crash counter stops
 * the app from restart-looping forever if something is fundamentally broken.
 */
public class App extends Application {

    private static final String TAG = "BenAmorKiosk";
    static final String PREFS = "kiosk";

    @Override
    public void onCreate() {
        super.onCreate();

        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    scheduleRestartIfNeeded();
                } catch (Throwable ignored) {
                    // never throw from the crash handler itself
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    /**
     * Called by MainActivity when the app has been alive and healthy for a
     * while — clears the rolling crash counter.
     */
    static void noteHealthyUptime(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long windowStart = p.getLong("crashWindowStart", 0L);
        long now = SystemClock.elapsedRealtime();
        if (now - windowStart > 60_000L) {
            p.edit().putInt("crashCount", 0).apply();
        }
    }

    private void scheduleRestartIfNeeded() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = SystemClock.elapsedRealtime();

        long windowStart = p.getLong("crashWindowStart", 0L);
        int crashes = p.getInt("crashCount", 0);
        if (now - windowStart > 5 * 60_000L) {
            // start a new rolling 5-minute window
            windowStart = now;
            crashes = 0;
        }
        crashes++;
        p.edit()
                .putLong("crashWindowStart", windowStart)
                .putInt("crashCount", crashes)
                .commit(); // synchronous: the process is about to die

        if (crashes > 4) {
            Log.e(TAG, "Too many crashes within 5 minutes (" + crashes
                    + ") — not restarting, to avoid a boot loop.");
            return;
        }

        Log.e(TAG, "Crash #" + crashes + " — restarting the app in 1.5s", null);

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent restart = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) {
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1_500L, restart);
        }
    }
}
