package com.benamorgroup.pricechecker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * BEN AMOR Price Checker — fullscreen kiosk container for the hosted web app.
 *
 * The activity owns two WebViews:
 *  - main: loads the hosted Price Checker
 *  - popup: hosts the quotation window that the site opens with
 *    window.open('', '_blank') + document.write(...) — required for the
 *    "print / save as PDF" quotation feature.
 *
 * Hidden admin access: tap the logo in the web page 7 times, or press
 * Volume-Up 3 times quickly — then enter the admin PIN.
 */
@SuppressLint("SetJavaScriptEnabled")
public class MainActivity extends Activity {

    private static final String TAG = "BenAmorKiosk";
    private static final int REQ_FILE_CHOOSER = 1001;

    // ---- views -------------------------------------------------------------
    private FrameLayout webHolder;          // hosts the main WebView
    private TextView offlineBanner;
    private LinearLayout popupRoot;
    private FrameLayout popupWebHolder;
    private View splash;
    private View errorOverlay;
    private TextView errorDetail;

    // ---- webview state -----------------------------------------------------
    private WebView web;
    private WebView popupWeb;
    private ValueCallback<Uri[]> fileChooserCallback;

    // ---- kiosk / admin state ----------------------------------------------
    private boolean kioskActive = false;
    private boolean adminUiShowing = false;
    private int volumeUpCount = 0;
    private long lastVolumeUp = 0L;

    // ---- load state --------------------------------------------------------
    private boolean firstPageShown = false;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pageTimeout = new Runnable() {
        @Override
        public void run() {
            if (!firstPageShown) {
                showError(getString(R.string.error_timeout));
            }
        }
    };
    private ConnectivityManager.NetworkCallback networkCallback;
    private android.content.SharedPreferences prefs;

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(App.PREFS, MODE_PRIVATE);
        PinStore.ensureInitialized(this);

        // Keep the screen awake while the kiosk is running.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        bindViews();
        setupKeyboardResize();

        attachNewWebView();
        applyOrientationPref();
        registerNetworkCallback();

        // Service workers power the offline (PWA) behaviour of the web app.
        try {
            android.webkit.ServiceWorkerController.getInstance()
                    .getServiceWorkerWebSettings()
                    .setCacheMode(WebSettings.LOAD_DEFAULT);
        } catch (Throwable ignored) {
            // not available on every build; WebView defaults are already correct
        }

        loadApp(true);
    }

    private void bindViews() {
        webHolder = findViewById(R.id.webHolder);
        offlineBanner = findViewById(R.id.offlineBanner);
        popupRoot = findViewById(R.id.popupRoot);
        popupWebHolder = findViewById(R.id.popupWebHolder);
        splash = findViewById(R.id.splash);
        errorOverlay = findViewById(R.id.errorOverlay);
        errorDetail = findViewById(R.id.errorDetail);

        Button retry = findViewById(R.id.btnRetry);
        retry.setOnClickListener(v -> loadApp(false));

        Button popupPrint = findViewById(R.id.btnPopupPrint);
        popupPrint.setOnClickListener(v -> {
            if (popupWeb != null) doPrint(popupWeb);
        });

        Button popupClose = findViewById(R.id.btnPopupClose);
        popupClose.setOnClickListener(v -> closePopup());
    }

    /**
     * Keyboard behaviour: the app content must always stay visible above the
     * on-screen keyboard (search box, cart, retry screen...).
     *
     * Android 11 (API 30) delivers IME insets reliably, so on API 30+ the
     * window uses ADJUST_NOTHING and the root view is padded up by the exact
     * keyboard height — this works even in immersive fullscreen, where plain
     * adjustResize is unreliable. Older Android versions fall back to the
     * manifest's android:windowSoftInputMode="adjustResize".
     */
    private void setupKeyboardResize() {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(0, 0, 0, ime.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.noteHealthyUptime(this);
        applyImmersive();
        // Re-enter kiosk automatically when configured (best with Device Owner).
        if (prefs.getBoolean("autoKiosk", false) && !kioskActive) {
            main.postDelayed(this::enterKiosk, 400);
        }
    }

    @Override
    protected void onDestroy() {
        if (networkCallback != null) {
            try {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {
            }
            networkCallback = null;
        }
        closePopup();
        destroyMainWebView();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    public void onBackPressed() {
        if (popupWeb != null) {
            closePopup();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        if (kioskActive) {
            // In kiosk mode the Back button never exits the app.
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Hidden admin trigger that also works while the error screen is
        // shown: press Volume-Up 3 times quickly.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastVolumeUp > 1500L) volumeUpCount = 0;
            lastVolumeUp = now;
            volumeUpCount++;
            if (volumeUpCount >= 3) {
                volumeUpCount = 0;
                maybeOpenAdmin();
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ========================================================================
    // WebView setup
    // ========================================================================

    /** Creates the main WebView (also used to recover after a renderer crash). */
    private void attachNewWebView() {
        WebView newWeb = new WebView(this);
        configureWebView(newWeb, true);
        webHolder.removeAllViews();
        webHolder.addView(newWeb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web = newWeb;
    }

    private void destroyMainWebView() {
        if (web != null) {
            try {
                webHolder.removeAllViews();
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
    }

    @SuppressLint({"JavascriptInterface", "AddJavascriptInterface"})
    private void configureWebView(WebView view, boolean isMain) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage: login + carts persist
        s.setSupportMultipleWindows(true);     // quotation: window.open('', '_blank')
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);           // security: no local file access
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT); // normal HTTP cache + PWA cache
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); // HTTPS only
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(true);
        }
        view.setBackgroundColor(0xFF0B1020);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try {
            cm.setAcceptThirdPartyCookies(view, true);
        } catch (Throwable ignored) {
        }

        view.setDownloadListener(downloadListener);

        if (isMain) {
            view.setWebViewClient(new MainWebViewClient());
            view.setWebChromeClient(new MainChromeClient());
            view.addJavascriptInterface(new KioskBridge(this, view), "BenAmorKiosk");
        } else {
            view.setWebViewClient(new PopupWebViewClient());
            view.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onCloseWindow(WebView window) {
                    closePopup();
                }

                @Override
                public boolean onJsAlert(WebView v, String url, String message, JsResult result) {
                    result.confirm();
                    return true;
                }
            });
            view.addJavascriptInterface(new KioskBridge(this, view), "BenAmorKiosk");
        }
    }

    private final DownloadListener downloadListener = new DownloadListener() {
        @Override
        public void onDownloadStart(String url, String userAgent,
                                    String contentDisposition, String mimetype, long length) {
            // The Price Checker itself uses printing for quotations; this
            // listener future-proofs CSV/PDF/image downloads from admin pages.
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                toast(R.string.download_failed);
                return;
            }
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, "BenAmor/" + name);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setTitle(name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    toast(R.string.download_started);
                    return;
                }
            } catch (Throwable t) {
                // fall through
            }
            toast(R.string.download_failed);
        }
    };

    // ========================================================================
    // Main WebViewClient / WebChromeClient
    // ========================================================================

    private class MainWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!firstPageShown) {
                splash.setVisibility(View.VISIBLE);
            }
            schedulePageTimeout();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            firstPageShown = true;
            cancelPageTimeout();
            splash.setVisibility(View.GONE);
            updateOfflineBanner();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            firstPageShown = true;
            cancelPageTimeout();
            splash.setVisibility(View.GONE);
            injectKioskJs(view);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (!request.isForMainFrame()) {
                return; // sub-resource failures (images, fonts) are not fatal
            }
            String detail = "";
            try {
                detail = " (" + error.getDescription() + ")";
            } catch (Throwable ignored) {
            }
            showError(getString(R.string.error_network) + detail);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (!request.isForMainFrame()) {
                return;
            }
            try {
                showError(getString(R.string.error_http, errorResponse.getStatusCode()));
            } catch (Throwable t) {
                showError(getString(R.string.error_network));
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // Security: never proceed on certificate errors.
            handler.cancel();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Renderer crash recovery: rebuild the WebView and reload.
            if (view == web) {
                destroyMainWebView();
                attachNewWebView();
                loadApp(true);
                toast(R.string.crash_recovered);
            } else {
                closePopup();
            }
            return true; // handled — keep the app alive
        }
    }

    private class MainChromeClient extends WebChromeClient {

        /** window.open('', '_blank') — the quotation preview window. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture, Message resultMsg) {
            return openPopupWebView(resultMsg);
        }

        @Override
        public void onCloseWindow(WebView window) {
            closePopup();
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = callback;
            try {
                Intent intent = params.createIntent();
                startActivityForResult(Intent.createChooser(
                        intent, getString(R.string.file_chooser_title)), REQ_FILE_CHOOSER);
                return true;
            } catch (Throwable t) {
                fileChooserCallback = null;
                toast(R.string.file_chooser_failed);
                return false;
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            // The Price Checker needs no camera/microphone. If a future web
            // version adds barcode scanning, add the CAMERA permission to the
            // manifest and grant protected media requests here (see README).
            runOnUiThread(() -> {
                try {
                    request.deny();
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER && fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileChooserCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ========================================================================
    // Navigation policy
    // ========================================================================

    /** @return true if the app consumed the URL (WebView must not load it). */
    private boolean handleNavigation(String url) {
        if (url == null) {
            return true;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        switch (scheme) {
            case "http":
            case "https": {
                if (isAllowedHost(host)) {
                    if ("http".equals(scheme)) {
                        // Security: never allow plain HTTP — always upgrade.
                        web.loadUrl("https://" + url.substring(7));
                        return true;
                    }
                    return false; // load inside the WebView
                }
                if ("wa.me".equals(host) || "api.whatsapp.com".equals(host)
                        || "chat.whatsapp.com".equals(host)) {
                    openExternal(url);
                    return true;
                }
                toast(R.string.blocked_link);
                return true; // unknown site — blocked (kiosk)
            }
            case "whatsapp": {
                openExternal(url);
                return true;
            }
            case "intent": {
                try {
                    Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    intent.addCategory(Intent.CATEGORY_BROWSABLE);
                    intent.setComponent(null);
                    intent.setSelector(null);
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) {
                        return handleNavigation(fallback);
                    }
                    openExternal(intent);
                } catch (Throwable t) {
                    toast(R.string.blocked_link);
                }
                return true;
            }
            case "mailto":
            case "tel":
            case "sms": {
                openExternal(url);
                return true;
            }
            case "about":
                return false; // about:blank hosts the quotation window
            case "blob":
            case "data":
                return false;
            default:
                // file:, content:, unknown schemes — blocked.
                toast(R.string.blocked_link);
                return true;
        }
    }

    private boolean isAllowedHost(String host) {
        for (String allowed : Config.ALLOWED_NAV_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            toast(R.string.whatsapp_missing);
        } catch (Throwable t) {
            toast(R.string.external_blocked_kiosk);
        }
    }

    private void openExternal(Intent intent) {
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            toast(R.string.whatsapp_missing);
        } catch (Throwable t) {
            toast(R.string.external_blocked_kiosk);
        }
    }

    // ========================================================================
    // Quotation popup (window.open + document.write + print)
    // ========================================================================

    private boolean openPopupWebView(Message resultMsg) {
        try {
            closePopup();

            WebView popup = new WebView(this);
            configureWebView(popup, false);

            popupWebHolder.removeAllViews();
            popupWebHolder.addView(popup, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            popupWeb = popup;
            popupRoot.setVisibility(View.VISIBLE);

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();

            // Route window.print() in the quotation to the Android print
            // dialog. Injected repeatedly (races are harmless, it is
            // idempotent) so the auto-print at ~300ms is always caught.
            injectPrintOverride(popup);
            popup.postDelayed(() -> injectPrintOverride(popup), 150L);
            popup.postDelayed(() -> injectPrintOverride(popup), 700L);
            return true;
        } catch (Throwable t) {
            closePopup();
            return false;
        }
    }

    private void injectPrintOverride(WebView view) {
        try {
            view.evaluateJavascript(
                    "window.print=function(){if(window.BenAmorKiosk){BenAmorKiosk.print();}}",
                    null);
        } catch (Throwable ignored) {
        }
    }

    private class PopupWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            Uri uri = request.getUrl();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean whatsapp = "wa.me".equals(host) || "api.whatsapp.com".equals(host)
                    || "chat.whatsapp.com".equals(host)
                    || "whatsapp".equals(uri.getScheme());
            if (whatsapp) {
                openExternal(url);
            } else if (isAllowedHost(host)) {
                // HTTPS only: upgrade and open in the main WebView
                web.loadUrl("http".equals(uri.getScheme())
                        ? "https://" + url.substring(7) : url);
            } else {
                toast(R.string.blocked_link);
            }
            closePopup();
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectPrintOverride(view);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            closePopup();
            return true;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }
    }

    private void closePopup() {
        WebView popup = popupWeb;
        popupWeb = null;
        if (popup != null) {
            try {
                popupWebHolder.removeAllViews();
                popup.destroy();
            } catch (Throwable ignored) {
            }
        }
        popupRoot.setVisibility(View.GONE);
    }

    // ========================================================================
    // Printing (quotation → PDF via Android print dialog)
    // ========================================================================

    private void doPrint(WebView target) {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String jobName = getString(R.string.print_job_name);
            // Opens the system print screen: choose "Save as PDF" to export
            // the quotation, or a printer to print it.
            pm.print(jobName, target.createPrintDocumentAdapter(jobName),
                    new PrintAttributes.Builder().build());
        } catch (Throwable t) {
            toast(R.string.print_unavailable);
        }
    }

    // ========================================================================
    // JavaScript bridge + injection (kept deliberately tiny)
    // ========================================================================

    /**
     * Exposed to JS as window.BenAmorKiosk with exactly two methods:
     *  - print(): routes window.print() to the Android print dialog
     *  - onSecretTap(): the hidden admin trigger (7 logo taps)
     * No other surface is exposed to the page.
     */
    private static class KioskBridge {
        private final WeakReference<MainActivity> activity;
        private final WeakReference<WebView> target;

        KioskBridge(MainActivity activity, WebView target) {
            this.activity = new WeakReference<>(activity);
            this.target = new WeakReference<>(target);
        }

        @JavascriptInterface
        public void print() {
            MainActivity a = activity.get();
            final WebView w = target.get();
            if (a != null && w != null) {
                a.runOnUiThread(() -> a.doPrint(w));
            }
        }

        @JavascriptInterface
        public void onSecretTap() {
            MainActivity a = activity.get();
            if (a != null) {
                a.runOnUiThread(a::maybeOpenAdmin);
            }
        }
    }

    private void injectKioskJs(WebView view) {
        view.evaluateJavascript(
                "(function(){"
                + "if(window.__benamorKiosk){return;}window.__benamorKiosk=1;"
                // window.print -> Android print dialog
                + "try{window.print=function(){if(window.BenAmorKiosk){BenAmorKiosk.print();}};}catch(e){}"
                // hidden admin trigger: 7 taps on the BEN AMOR logo
                + "try{"
                + "var taps=0,last=0;"
                + "document.addEventListener('click',function(ev){"
                + "try{"
                + "var el=ev.target&&ev.target.closest?ev.target.closest('.logo,.brand,.brandTitle,.brandLogo'):null;"
                + "if(!el){return;}"
                + "var now=Date.now();if(now-last>6000){taps=0;}last=now;taps++;"
                + "if(taps>=" + Config.SECRET_TAPS + "){taps=0;if(window.BenAmorKiosk){BenAmorKiosk.onSecretTap();}}"
                + "}catch(_){}"
                + "},true);"
                + "}catch(e){}"
                + "})();", null);
    }

    // ========================================================================
    // Load / error / offline handling
    // ========================================================================

    private void loadApp(boolean firstLoad) {
        if (firstLoad) {
            firstPageShown = false;
            splash.setVisibility(View.VISIBLE);
        }
        errorOverlay.setVisibility(View.GONE);
        if (web != null) {
            web.loadUrl(Config.APP_URL);
        }
        schedulePageTimeout();
    }

    private void schedulePageTimeout() {
        cancelPageTimeout();
        main.postDelayed(pageTimeout, Config.PAGE_LOAD_TIMEOUT_MS);
    }

    private void cancelPageTimeout() {
        main.removeCallbacks(pageTimeout);
    }

    private void showError(String detail) {
        cancelPageTimeout();
        splash.setVisibility(View.GONE);
        errorDetail.setText(detail);
        errorOverlay.setVisibility(View.VISIBLE);
        offlineBanner.setVisibility(View.GONE);
    }

    private void updateOfflineBanner() {
        // Show the Arabic offline banner only when the page is up but the
        // network is gone (the web app itself has no offline indication).
        boolean online = isOnline();
        offlineBanner.setVisibility(
                firstPageShown && !online && errorOverlay.getVisibility() != View.VISIBLE
                        ? View.VISIBLE : View.GONE);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.getActiveNetwork() != null
                    && cm.getNetworkCapabilities(cm.getActiveNetwork()) != null;
        } catch (Throwable t) {
            return true; // assume online — never block usage on a check failure
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(() -> {
                        offlineBanner.setVisibility(View.GONE);
                        if (errorOverlay.getVisibility() == View.VISIBLE) {
                            // connection came back — retry automatically
                            main.postDelayed(() -> {
                                if (errorOverlay.getVisibility() == View.VISIBLE) {
                                    loadApp(false);
                                }
                            }, 800L);
                        }
                    });
                }

                @Override
                public void onLost(Network network) {
                    runOnUiThread(MainActivity.this::updateOfflineBanner);
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable ignored) {
        }
    }

    // ========================================================================
    // Immersive fullscreen
    // ========================================================================

    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ========================================================================
    // Admin area (hidden): PIN + menu
    // ========================================================================

    private void maybeOpenAdmin() {
        if (adminUiShowing) {
            return;
        }
        PinStore.ensureInitialized(this);
        promptPin();
    }

    private void promptPin() {
        adminUiShowing = true;

        long lockUntil = prefs.getLong("pinLockUntil", 0L);
        long now = System.currentTimeMillis();
        if (now < lockUntil) {
            toast(getString(R.string.pin_locked, (lockUntil - now) / 1000L + 1));
            adminUiShowing = false;
            return;
        }

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.pin_hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.admin_pin_title)
                .setMessage(R.string.admin_pin_msg)
                .setView(input)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    checkPin(input.getText().toString());
                    adminUiShowing = false;
                })
                .setNegativeButton(R.string.cancel, (d, w) -> adminUiShowing = false)
                .setOnCancelListener(d -> adminUiShowing = false)
                .show();
    }

    private void checkPin(String entered) {
        if (PinStore.verify(this, entered)) {
            prefs.edit().putInt("pinFails", 0).apply();
            showAdminMenu();
            return;
        }
        int fails = prefs.getInt("pinFails", 0) + 1;
        if (fails >= 5) {
            prefs.edit()
                    .putInt("pinFails", 0)
                    .putLong("pinLockUntil", System.currentTimeMillis() + 60_000L)
                    .apply();
            toast(R.string.pin_lockout);
        } else {
            prefs.edit().putInt("pinFails", fails).apply();
            toast(R.string.pin_wrong);
        }
    }

    private void showAdminMenu() {
        final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        final java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();

        labels.add(getString(R.string.menu_refresh_latest)); // "Refresh / check latest version"
        actions.add(this::refreshLatestVersion);

        labels.add(getString(R.string.menu_reload));
        actions.add(() -> {
            closePopup();
            toast(R.string.reload_started);
            web.reload();
        });

        labels.add(getString(R.string.menu_clear_cache));
        actions.add(() -> confirm(getString(R.string.menu_clear_cache),
                getString(R.string.cache_confirm_msg), () -> {
                    web.clearCache(true); // HTTP/PWA file cache only —
                                          // login & saved carts are untouched
                    toast(R.string.cache_cleared);
                }));

        labels.add(getString(R.string.menu_clear_data));
        actions.add(() -> confirm(getString(R.string.menu_clear_data),
                getString(R.string.site_data_confirm_msg), this::clearAllSiteData));

        labels.add(kioskActive ? getString(R.string.menu_kiosk_stop) : getString(R.string.menu_kiosk_start));
        actions.add(() -> {
            if (kioskActive) {
                exitKiosk();
            } else {
                enterKiosk();
            }
        });

        labels.add((prefs.getBoolean("autoKiosk", false) ? "✓ " : "○ ")
                + getString(R.string.menu_autokiosk));
        actions.add(() -> {
            boolean newValue = !prefs.getBoolean("autoKiosk", false);
            prefs.edit().putBoolean("autoKiosk", newValue).apply();
            toast(newValue ? R.string.autokiosk_on : R.string.autokiosk_off);
        });

        labels.add((prefs.getBoolean("autoBoot", false) ? "✓ " : "○ ")
                + getString(R.string.menu_autoboot));
        actions.add(() -> {
            boolean newValue = !prefs.getBoolean("autoBoot", false);
            prefs.edit().putBoolean("autoBoot", newValue).apply();
            toast(newValue ? R.string.autoboot_on : R.string.autoboot_off);
        });

        String orient = prefs.getString("orientation", "portrait");
        String orientLabel = "landscape".equals(orient) ? getString(R.string.orientation_landscape)
                : "auto".equals(orient) ? getString(R.string.orientation_auto)
                : getString(R.string.orientation_portrait);
        labels.add(getString(R.string.menu_orientation, orientLabel));
        actions.add(this::cycleOrientation);

        labels.add(getString(R.string.menu_wifi));
        actions.add(this::openWifiSettings);

        labels.add(getString(R.string.menu_change_pin));
        actions.add(this::promptChangePin);

        labels.add(getString(R.string.menu_info));
        actions.add(this::showAppInfo);

        if (KioskManager.isDeviceOwner(this)) {
            labels.add(getString(R.string.menu_remove_owner));
            actions.add(() -> confirm(getString(R.string.menu_remove_owner),
                    getString(R.string.owner_remove_confirm), () -> {
                        if (KioskManager.clearDeviceOwner(this)) {
                            toast(R.string.owner_removed);
                        } else {
                            toast(R.string.owner_remove_failed);
                        }
                    }));
        }

        labels.add(getString(R.string.menu_exit));
        actions.add(() -> {
            exitKiosk();
            finishAndRemoveTask();
        });

        final Runnable[] run = actions.toArray(new Runnable[0]);
        adminUiShowing = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.admin_menu_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    adminUiShowing = false;
                    if (which >= 0 && which < run.length) {
                        run[which].run();
                    }
                })
                .setNegativeButton(R.string.close, (d, w) -> adminUiShowing = false)
                .setOnCancelListener(d -> adminUiShowing = false)
                .show();
    }

    // ---- admin actions -----------------------------------------------------

    /**
     * "Refresh / check for latest version":
     * reloads the web app while bypassing the normal WebView/GitHub-Pages
     * cache for this one load. Persistent login/cart storage is NOT touched.
     */
    private void refreshLatestVersion() {
        closePopup();
        toast(R.string.refresh_started);
        errorOverlay.setVisibility(View.GONE);
        splash.setVisibility(View.VISIBLE);
        firstPageShown = false;

        // Ask the service worker to check for an update, then load the page
        // with cache-bypass headers + a unique query (the web app ignores
        // unknown query parameters).
        try {
            web.evaluateJavascript(
                    "(function(){try{if(navigator.serviceWorker){"
                            + "navigator.serviceWorker.getRegistrations().then(function(rs){"
                            + "rs.forEach(function(r){r.update().catch(function(){});});"
                            + "}).catch(function(){});}}catch(e){}})()",
                    null);
        } catch (Throwable ignored) {
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        String sep = Config.APP_URL.contains("?") ? "&" : "?";
        String url = Config.APP_URL + sep + "_k=" + System.currentTimeMillis();
        web.loadUrl(url, headers);
        schedulePageTimeout();
    }

    private void clearAllSiteData() {
        closePopup();
        try {
            web.clearCache(true);
            web.clearHistory();
            web.clearFormData();
        } catch (Throwable ignored) {
        }
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        try {
            WebStorage.getInstance().deleteAllData(); // localStorage: logins, carts
        } catch (Throwable ignored) {
        }
        try {
            web.evaluateJavascript(
                    "(function(){try{if(navigator.serviceWorker){"
                            + "navigator.serviceWorker.getRegistrations().then(function(rs){"
                            + "rs.forEach(function(r){r.unregister().catch(function(){});});"
                            + "}).catch(function(){});}}catch(e){}})()",
                    null);
        } catch (Throwable ignored) {
        }
        toast(R.string.site_data_cleared);
        main.postDelayed(() -> loadApp(true), 900L);
    }

    private void enterKiosk() {
        boolean ok = KioskManager.startKiosk(this);
        kioskActive = ok;
        if (ok) {
            if (!KioskManager.isDeviceOwner(this)) {
                // screen pinning: confirm the system dialog to pin the app
                toast(R.string.kiosk_pinned_notice);
            }
        } else {
            toast(R.string.kiosk_failed);
        }
    }

    private void exitKiosk() {
        KioskManager.stopKiosk(this);
        kioskActive = false;
        if (prefs.getBoolean("autoKiosk", false)) {
            // Do not immediately re-pin after an explicit admin exit.
            prefs.edit().putBoolean("autoKiosk", false).apply();
            toast(R.string.autokiosk_disabled_with_exit);
        }
    }

    private void openWifiSettings() {
        // Under Lock Task the Settings app can only open when this app is
        // Device Owner (it is whitelisted). Otherwise stop pinning first.
        if (kioskActive && !KioskManager.isDeviceOwner(this)) {
            KioskManager.stopKiosk(this);
            kioskActive = false;
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
        } catch (Throwable t) {
            toast(R.string.wifi_failed);
        }
    }

    private void cycleOrientation() {
        String current = prefs.getString("orientation", "portrait");
        String next = "portrait".equals(current) ? "landscape"
                : "landscape".equals(current) ? "auto" : "portrait";
        prefs.edit().putString("orientation", next).apply();
        applyOrientationPref();
        String label = "landscape".equals(next) ? getString(R.string.orientation_landscape)
                : "auto".equals(next) ? getString(R.string.orientation_auto)
                : getString(R.string.orientation_portrait);
        toast(getString(R.string.orientation_now, label));
    }

    private void applyOrientationPref() {
        String v = prefs.getString("orientation", "portrait");
        int orientation = "landscape".equals(v)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : "auto".equals(v)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        setRequestedOrientation(orientation);
    }

    private void promptChangePin() {
        adminUiShowing = true;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText newPin = new EditText(this);
        newPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        newPin.setHint(R.string.pin_new);
        final EditText confirmPin = new EditText(this);
        confirmPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        confirmPin.setHint(R.string.pin_confirm);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(newPin);
        box.addView(confirmPin);

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_change_pin)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    adminUiShowing = false;
                    String a = newPin.getText().toString();
                    String b = confirmPin.getText().toString();
                    if (a.length() < 4) {
                        toast(R.string.pin_too_short);
                    } else if (!a.equals(b)) {
                        toast(R.string.pin_mismatch);
                    } else {
                        PinStore.set(MainActivity.this, a);
                        toast(R.string.pin_changed);
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> adminUiShowing = false)
                .setOnCancelListener(d -> adminUiShowing = false)
                .show();
    }

    private void showAppInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            sb.append(getString(R.string.info_version, pi.versionName)).append('\n');
            sb.append(getString(R.string.info_package, getPackageName())).append('\n');
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        sb.append(getString(R.string.info_url, Config.APP_URL)).append('\n');
        try {
            sb.append(getString(R.string.info_webview,
                    android.webkit.WebView.getCurrentWebViewPackage().versionName)).append('\n');
        } catch (Throwable t) {
            sb.append(getString(R.string.info_webview, "?")).append('\n');
        }
        sb.append(getString(R.string.info_owner,
                KioskManager.isDeviceOwner(this)
                        ? getString(R.string.yes) : getString(R.string.no))).append('\n');
        sb.append(getString(R.string.info_kiosk,
                kioskActive ? getString(R.string.yes) : getString(R.string.no))).append('\n');
        sb.append(getString(R.string.info_autoboot,
                prefs.getBoolean("autoBoot", false)
                        ? getString(R.string.yes) : getString(R.string.no))).append('\n');

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_info)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // ---- helpers -----------------------------------------------------------

    private void confirm(String title, String message, final Runnable action) {
        adminUiShowing = true;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    adminUiShowing = false;
                    action.run();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> adminUiShowing = false)
                .setOnCancelListener(d -> adminUiShowing = false)
                .show();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // Unused but kept: prevents lint "missing inflate" confusion for layouts.
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
    }
}
