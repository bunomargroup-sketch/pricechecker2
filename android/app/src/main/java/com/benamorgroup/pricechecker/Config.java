package com.benamorgroup.pricechecker;

/**
 * Central configuration of the kiosk wrapper.
 *
 * Change values here and rebuild the APK if you ever need to point the app at
 * a different URL or change the default admin PIN. Everything that changes
 * often (kiosk on/off, auto-start, screen orientation, PIN) can also be
 * changed at runtime from the hidden admin menu — no rebuild needed.
 */
public final class Config {

    private Config() {
        // no instances
    }

    /**
     * The hosted Price Checker web app.
     * The APK never contains a copy of the website — it always loads this
     * URL, so GitHub Pages updates reach the tablet without rebuilding.
     */
    public static final String APP_URL =
            "https://bunomargroup-sketch.github.io/pricechecker2/";

    /** Host of the main site (used for the HTTPS-only upgrade rule). */
    public static final String APP_HOST = "bunomargroup-sketch.github.io";

    /**
     * Hosts that are allowed to open inside the WebView when the page tries
     * to NAVIGATE to them. Sub-resources (fonts, Supabase API calls, images)
     * are not affected by this list — it only restricts navigations, so
     * staff cannot wander off to arbitrary websites.
     */
    public static final String[] ALLOWED_NAV_HOSTS = {
            APP_HOST,                              // the Price Checker itself
            "kkqbkumobeimwuscxztu.supabase.co",    // Supabase project (cart sync)
            "fonts.googleapis.com",                // Google Fonts (CSS)
            "fonts.gstatic.com"                    // Google Fonts (files)
    };

    /**
     * Packages that are allowed to run on top of this app while it is in
     * TRUE Lock Task mode (only effective when the app is Device Owner).
     * Everything else is blocked by the system while the kiosk is active.
     */
    public static final String[] LOCK_TASK_PACKAGES = {
            "com.benamorgroup.pricechecker", // this app
            "com.android.settings",          // Wi-Fi settings (admin action)
            "com.android.printspooler",      // system print / Save-as-PDF dialog
            "com.android.documentsui",       // system file picker (CSV/photo uploads)
            "com.whatsapp",                  // WhatsApp sharing (wa.me links)
            "com.whatsapp.w4b"               // WhatsApp Business (if installed)
    };

    /**
     * In-app APK self-update (admin menu → "تحديث تطبيق الأندرويد (APK)").
     * build-apk.sh regenerates android/apk/latest.json on every build; the
     * tablet reads it from the FIRST URL that responds.
     *
     * IMPORTANT: these URLs serve the MAIN branch of the repository — merge
     * the working branch into main once, otherwise both return 404 and the
     * in-app update check will report "cannot reach update info".
     */
    public static final String[] UPDATE_JSON_URLS = {
            "https://raw.githubusercontent.com/bunomargroup-sketch/pricechecker2/main/android/apk/latest.json",
            "https://bunomargroup-sketch.github.io/pricechecker2/android/apk/latest.json"
    };

    /**
     * Admin PIN used on the very first run.
     * CHANGE IT IMMEDIATELY from the admin menu after installing
     * (logo x7 -> PIN -> "تغيير رمز الإدارة").
     */
    public static final String DEFAULT_ADMIN_PIN = "1234";

    /**
     * Taps on the logo (inside the web page) needed to open the admin PIN
     * prompt. Fallback for when the page cannot be loaded: press the
     * Volume-Up key this many times quickly.
     */
    public static final int SECRET_TAPS = 7;

    /** Give up on the first page load and show the Arabic retry screen after this. */
    public static final long PAGE_LOAD_TIMEOUT_MS = 45_000L;
}
