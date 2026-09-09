package com.benamorgroup.pricechecker;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * In-app APK self-update (admin menu → "تحديث تطبيق الأندرويد (APK)").
 *
 * Flow:
 *  1. Read android/apk/latest.json from the project's public URLs
 *     (see {@link Config#UPDATE_JSON_URLS}) and compare versionCode.
 *  2. If newer: download the release APK listed there into the app's
 *     private cache and install it through the system PackageInstaller.
 *
 * Behaviour by device state:
 *  - Device Owner (true kiosk): installation is SILENT (managed install),
 *    then the app relaunches itself automatically.
 *  - Normal mode: Android shows the standard "Install?" confirmation —
 *    the admin taps Install once. This requires the "install unknown
 *    apps" permission for this app (the app opens the right Settings
 *    screen automatically when it is missing).
 *
 * Safety: the update is served over HTTPS only, and PackageInstaller
 * refuses to replace the app unless the new APK is signed with the SAME
 * key — a wrong or tampered file simply fails to install.
 */
public final class SelfUpdater {

    private static final String TAG = "BenAmorKiosk";
    public static final String ACTION_INSTALL_STATUS =
            "com.benamorgroup.pricechecker.INSTALL_STATUS";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SelfUpdater() {
        // no instances
    }

    /** Called with latest == null when latest.json could not be reached. */
    public interface CheckCallback {
        void onResult(JSONObject latest, String baseUrl, int currentVersionCode);
    }

    static int currentVersionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    static String currentVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Fetch latest.json (trying each configured URL in order) off the UI thread. */
    static void checkLatest(final Context c, final CheckCallback cb) {
        new Thread(() -> {
            JSONObject found = null;
            String base = null;
            for (String url : Config.UPDATE_JSON_URLS) {
                try {
                    JSONObject o = new JSONObject(httpGet(url));
                    if (o.optInt("versionCode", -1) > 0 && o.has("apk")) {
                        found = o;
                        base = url.substring(0, url.lastIndexOf('/') + 1);
                        break;
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "update check failed for " + url + ": " + t);
                }
            }
            final JSONObject latest = found;
            final String baseUrl = base;
            final int current = currentVersionCode(c);
            MAIN.post(() -> cb.onResult(latest, baseUrl, current));
        }, "apk-update-check").start();
    }

    /** Download the APK in the background, then hand it to PackageInstaller. */
    static void downloadAndInstall(final Activity a, String baseUrl, JSONObject latest) {
        final String url = baseUrl + latest.optString("apk");
        Toast.makeText(a, R.string.update_downloading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File apk = download(a, url);
                // sanity check: the file must really be this app's package
                PackageInfo info = a.getPackageManager()
                        .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (info == null || !a.getPackageName().equals(info.packageName)) {
                    throw new IOException("downloaded file is not the Price Checker APK");
                }
                MAIN.post(() -> install(a, apk));
            } catch (Throwable t) {
                Log.w(TAG, "update download failed", t);
                MAIN.post(() -> Toast.makeText(a,
                        R.string.update_download_failed, Toast.LENGTH_LONG).show());
            }
        }, "apk-update-download").start();
    }

    static void install(Activity a, File apk) {
        PackageInstaller.Session session = null;
        try {
            PackageInstaller installer = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (OutputStream out = session.openWrite("benamor-update", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            session.commit(statusSender(a));

            Toast.makeText(a, R.string.update_installing, Toast.LENGTH_SHORT).show();
            // Device owners install silently — relaunch shortly after commit.
            if (KioskManager.isDeviceOwner(a)) {
                scheduleRelaunch(a, 4_000L);
            }
        } catch (Throwable t) {
            Log.w(TAG, "update install failed", t);
            Toast.makeText(a, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Handle the PackageInstaller result broadcast (see MainActivity's receiver). */
    static void handleStatus(Activity a, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Normal (non-owner) installs: show the system confirmation.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    a.startActivity(confirm);
                } catch (Throwable ignored) {
                }
            }
            // Safety net: if the update installs (app is killed and cannot
            // react), relaunch automatically a bit later.
            scheduleRelaunch(a, 45_000L);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(a, R.string.update_success, Toast.LENGTH_SHORT).show();
            scheduleRelaunch(a, 2_000L);
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.w(TAG, "APK install failed: " + msg);
            if (msg != null && msg.contains("REQUEST_INSTALL_PACKAGES")) {
                Toast.makeText(a, R.string.update_needs_unknown_apps, Toast.LENGTH_LONG).show();
                try {
                    a.startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + a.getPackageName())));
                } catch (Throwable ignored) {
                }
            } else {
                Toast.makeText(a, R.string.update_install_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Relaunch the app after a delay (used after an update replaces the process). */
    static void scheduleRelaunch(Context c, long delayMs) {
        try {
            Intent intent = new Intent(c, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 1, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMs, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    private static IntentSender statusSender(Context c) {
        Intent intent = new Intent(ACTION_INSTALL_STATUS).setPackage(c.getPackageName());
        // FLAG_MUTABLE is required so the system can attach the install
        // result extras to the broadcast.
        PendingIntent pi = PendingIntent.getBroadcast(c, 10, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return pi.getIntentSender();
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static File download(Context c, String url) throws IOException {
        File out = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream();
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }
}
