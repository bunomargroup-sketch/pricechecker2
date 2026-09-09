# BEN AMOR Price Checker — Android Kiosk App

A very lightweight native Android APK (≈ 70 KB) that acts **only as a secure
fullscreen kiosk container** for the existing hosted Price Checker web app:

```
https://bunomargroup-sketch.github.io/pricechecker2/
```

**The APK never contains a copy of the website.** It always loads the hosted
URL, so the update flow stays exactly as it is today:

```
Update website → GitHub Pages publishes new version
              → Tablet (kiosk) opens the same URL
              → New version loads — no APK rebuild, no reinstall
```

Built for the showroom tablet: **Lenovo Yoga Tab 3 Plus (YT-X703F), LineageOS
18.1 / Android 11, ARM64, no Google Play Services.** It uses no Google
libraries, no analytics, no ads — only the standard Android SDK.

---

## Contents

1. [What's included](#whats-included)
2. [Ready-made APKs](#ready-made-apks)
3. [Install on the tablet (Windows + ADB)](#install-on-the-tablet-windows--adb)
4. [First-run setup](#first-run-setup)
5. [The hidden admin menu](#the-hidden-admin-menu)
6. [Kiosk mode](#kiosk-mode)
7. [Device Owner (true Lock Task)](#device-owner-true-lock-task)
8. [Removing Device Owner status safely](#removing-device-owner-status-safely)
9. [Recovering the device if kiosk mode causes problems](#recovering-the-device-if-kiosk-mode-causes-problems)
10. [Auto-start after reboot](#auto-start-after-reboot)
11. [Website updates & the refresh action](#website-updates--the-refresh-action)
12. [Building the APK yourself](#building-the-apk-yourself)
13. [Configuration reference](#configuration-reference)
14. [Replacing the launcher icon / splash logo](#replacing-the-launcher-icon--splash-logo)
15. [What the WebView enables (feature map)](#what-the-webview-enables-feature-map)
16. [Security notes](#security-notes)
17. [Test checklist for the showroom tablet](#test-checklist-for-the-showroom-tablet)
18. [Troubleshooting](#troubleshooting)
19. [How the shipped APKs were built](#how-the-shipped-apks-were-built)

---

## What's included

```
android/
├── apk/                       ← ready-to-install APKs (see below)
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/benamorgroup/pricechecker/
│   │   ├── MainActivity.java      WebView, popup window, print, admin UI
│   │   ├── App.java               process-crash auto-restart
│   │   ├── BootReceiver.java      optional auto-start after reboot
│   │   ├── KioskManager.java      Lock Task / Device Owner helpers
│   │   ├── KioskDeviceAdminReceiver.java
│   │   ├── PinStore.java          salted-hash admin PIN storage
│   │   └── Config.java            ← URLs, hosts, PIN, kiosk packages
│   └── res/                       layout, Arabic strings, icons, theme
├── keystore/
│   ├── benamor-release.jks    release signing key (CHANGE before distribution)
│   └── debug.keystore         standard debug key
├── scripts/make_icons.py      regenerates launcher icons from icons/icon-512.png
├── build-apk.sh               reproducible build pipeline (see last section)
├── build.gradle / settings.gradle / gradle wrapper  ← Android Studio project
└── README.md                  this file
```

---

## Ready-made APKs

| File | Size | Signed with | Use for |
|---|---|---|---|
| `apk/BenAmorPriceChecker-1.0.0-release.apk` | ≈ 70 KB | `keystore/benamor-release.jks` | **production — install this one** |
| `apk/BenAmorPriceChecker-1.0.0-debug.apk` | ≈ 70 KB | debug key | quick testing |

Both are built from the source in this folder, targetSdk 34 / minSdk 24
(Android 7.0+), verified with APK Signature Scheme **v2 + v3** — exactly what
Android 11 requires.

> ⚠️ Both APKs are signed with *different* keys. Android refuses to install an
> APK signed with a different key over an existing installation — pick ONE
> variant and stay with it (recommended: **release**).

---

## Install on the tablet (Windows + ADB)

1. On the tablet: **Settings → About tablet → tap "Build number" 7 times** to
   enable Developer options. Then **Settings → System → Developer options** →
   enable **USB debugging** (and stay on that screen).
2. Connect the tablet to the PC with USB. If a prompt
   *"Allow USB debugging?"* appears on the tablet — check "Always allow" and
   tap **Allow**.
3. On the PC, open **Command Prompt** (cmd) and run:

```bat
adb devices
```

You should see the tablet listed (otherwise install the Lenovo/Google USB
driver or try another USB port/cable).

4. Install the app — **exact command** (adjust the path if you saved the APK
   elsewhere; from the repo folder it is):

```bat
adb install -r "android\apk\BenAmorPriceChecker-1.0.0-release.apk"
```

(`-r` = reinstall/replace, keeps all app data.)
If you downloaded the APK to your **Downloads** folder, use:

```bat
adb install -r "%USERPROFILE%\Downloads\BenAmorPriceChecker-1.0.0-release.apk"
```

5. Launch it from the launcher: **BEN AMOR Price Checker**.

> If `adb` is not recognized: install [Google's platform-tools]
> (https://developer.android.com/tools/releases/platform-tools), unzip it,
> and run the command from inside that folder, e.g.
> `C:\platform-tools\adb install -r ...`

### Updating / reinstalling the APK later

- **New APK version (same signing key):**
  ```bat
  adb install -r "android\apk\BenAmorPriceChecker-1.0.1-release.apk"
  ```
  Logins, saved carts and settings are preserved.
- **If Android refuses** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` — different
  signature was installed before):
  ```bat
  adb uninstall com.benamorgroup.pricechecker
  adb install "android\apk\BenAmorPriceChecker-1.0.0-release.apk"
  ```
  Uninstalling wipes the app's local storage (logins/carts on that device) —
  they re-sync from Supabase after signing in again.
- **Remember:** day-to-day changes belong to the *website*, not the APK.
  Updating the website never touches the APK.

---

## First-run setup

1. Launch the app → it opens the Price Checker fullscreen.
2. Tap the **BEN AMOR logo** in the app header **7 times** → the admin PIN
   prompt appears.
3. Default PIN: **1234** (`Config.DEFAULT_ADMIN_PIN`).
4. **Immediately change it:** admin menu → **تغيير رمز الإدارة** (change PIN).
5. Recommended: set **اتجاه الشاشة** (orientation), enable **دخول الكشك
   تلقائياً** and **تشغيل تلقائي بعد إعادة تشغيل الجهاز** if this tablet is
   a dedicated showroom device.

A second hidden trigger that also works when the page cannot load: press the
**Volume-Up key 3 times quickly**. Staff never see these controls — the menu
is always PIN-protected (5 wrong attempts = 60-second lockout).

---

## The hidden admin menu

Open: **7 logo taps → PIN** (or Volume-Up ×3). Actions:

| Menu item (Arabic) | What it does |
|---|---|
| تحديث / فحص أحدث إصدار | **Refresh / check for latest version** — reloads the web app bypassing the WebView/GitHub-Pages cache for that one load. Logins & carts are **not** touched. |
| إعادة تحميل التطبيق | Normal reload (uses cache) |
| مسح الذاكرة المؤقتة فقط | Clears cached files only — **login & saved carts preserved** |
| مسح جميع بيانات الموقع (خروج كامل) | Wipes localStorage/cookies/service workers (confirm dialog) — full logout |
| بدء/إيقاف وضع الكشك | Enter/leave kiosk mode |
| دخول الكشك تلقائياً عند التشغيل | Auto-enter kiosk whenever the app starts |
| تشغيل تلقائي بعد إعادة تشغيل الجهاز | Start app after device reboot |
| اتجاه الشاشة: عمودي/أفقي/تلقائي | Cycle portrait → landscape → auto (no rebuild needed) |
| فتح إعدادات Wi-Fi | Opens Android Wi-Fi settings |
| تغيير رمز الإدارة | Change the admin PIN |
| معلومات التطبيق | Version, package, WebView version, URL, owner/kiosk state |
| إلغاء تسجيل المالك (Device Owner) | Only shown when the app is Device Owner |
| إنهاء التطبيق | Exit (stops kiosk first) |

---

## Kiosk mode

Two levels:

**1. Normal screen pinning (no setup needed)**
Admin menu → **بدء وضع الكشك**. Android shows a *"Pin this app?"* dialog —
tap **تثبيت**. The app stays fullscreen; the Back button no longer exits;
staff can only leave by holding **Back + Recents** together (or via the admin
menu). Good for testing and light-duty use.

**2. True Lock Task mode (recommended for the showroom)**
When the app is **Device Owner** (next section), entering kiosk mode gives
you the full COSU behavior with no confirmation dialog:

- Home, Recents and the notification-shade paths are blocked by the system.
- The Back button never exits (in-page Back still works).
- Only the packages whitelisted in `Config.LOCK_TASK_PACKAGES` can appear on
  top: **Wi-Fi settings, the system print dialog (Save as PDF), the file
  picker, and WhatsApp / WhatsApp Business** (for cart sharing). Everything
  else is locked out.

Switch back to **Normal Admin Mode** at any time: admin menu → **إيقاف وضع
الكشك** (this also turns off auto-kiosk so the tablet does not immediately
re-pin).

---

## Device Owner (true Lock Task)

Device Owner can be set **once**, via ADB, and only if the device has **no
accounts added yet** (best done right after installing LineageOS, before
adding any Google/other account in Settings → Accounts).

With the tablet connected:

```bat
adb shell dpm set-device-owner com.benamorgroup.pricechecker/.KioskDeviceAdminReceiver
```

If that returns an error, try the explicit user form (Android 11):

```bat
adb shell dpm set-device-owner --user 0 com.benamorgroup.pricechecker/.KioskDeviceAdminReceiver
```

Common errors and fixes:

| Error | Meaning / fix |
|---|---|
| `Not allowed to set the device owner because there are already some accounts on the device` | Remove all accounts (Settings → Accounts), or factory-reset, then retry. |
| `Trying to set the device owner, but device owner is already set` | An owner already exists (`adb shell dpm list-owners` to see it). |
| `Unknown admin` | The app isn't installed yet — install the APK first. |

Verify it worked:

```bat
adb shell dpm list-owners
adb shell dumpsys device_policy
```

You should see `Device owner: com.benamorgroup.pricechecker`. The admin menu's
"معلومات التطبيق" will also show **مالك الجهاز (Device Owner): نعم**.

Once Device Owner is set, enable **دخول الكشك تلقائياً عند التشغيل** in the
admin menu — the tablet boots straight into a fully locked kiosk.

---

## Removing Device Owner status safely

Preferred (from the tablet): admin menu → **إلغاء تسجيل المالك (Device
Owner)** → confirm. This uses the official
`DevicePolicyManager.clearDeviceOwnerApp()` call and also unpins kiosk mode.

Or from the PC:

```bat
adb shell dpm remove-active-admin com.benamorgroup.pricechecker/.KioskDeviceAdminReceiver
```

Then restart the app. `adb shell dpm list-owners` should return nothing.

Nuclear options (only if ADB is unavailable): uninstall the app, or factory
reset — both always clear Device Owner.

---

## Recovering the device if kiosk mode causes problems

If the tablet is stuck in kiosk mode and you cannot reach the admin menu:

1. **USB debugging still works.** From the PC:
   ```bat
   adb shell am force-stop com.benamorgroup.pricechecker
   ```
   In true Lock Task this takes you to the lock screen/launcher. If the app
   is set to auto-restart, also remove owner status (previous section).
2. **Safe mode** (LineageOS 18.1): press and hold Power → long-press
   **Reboot/Power off** in the menu until the "Reboot to safe mode" prompt
   appears → OK. Third-party apps are disabled in safe mode; from there you
   can uninstall the app or clear owner via ADB. Exit safe mode with a normal
   reboot.
3. **Last resort:** reboot into recovery (Power + Volume-down while booting)
   → factory reset.

The kiosk app is designed never to trap you: the admin menu's exit is always
PIN-reachable, and Device Owner removal is a single ADB command.

---

## Auto-start after reboot

Enable **تشغيل تلقائي بعد إعادة تشغيل الجهاز** in the admin menu (off by
default). The app then listens for `BOOT_COMPLETED` and relaunches itself.

**Android 10+ limitation (applies to Android 11):** apps may not start
activities from the background unless one of these is true:

1. **The app is Device Owner** ← recommended; works silently and reliably.
2. The app has the *display over other apps* app-op allowed — one-time ADB:
   ```bat
   adb shell appops set com.benamorgroup.pricechecker SYSTEM_ALERT_WINDOW allow
   ```
3. The app is set as the **HOME launcher** (Settings → Apps → Default Apps →
   Home app → BEN AMOR Price Checker). Then it *is* what boots.

If none apply, the receiver still runs but Android blocks the launch and the
app logs it (visible via `adb logcat -s BenAmorKiosk`). The power button →
app icon is then needed — that is a platform restriction, not a bug.

---

## Website updates & the refresh action

The kiosk always loads the live URL — GitHub Pages updates reach the tablet
automatically on the next app start/reload. Two cache layers can delay what
you see by a few minutes, both by design (they also make the tablet work
offline):

- **GitHub Pages edge cache** (~10 minutes max-age).
- **The site's own service worker** (`sw.js`) — serves the app offline,
  revalidates on navigation.

So in practice: **update the website → tablet shows the new version on its
next launch, or within ~10 minutes of use.** To force it *right now*, use the
admin action **تحديث / فحص أحدث إصدار** — it reloads with
`Cache-Control: no-cache` plus a cache-busting query, and asks the service
worker to update. Login and cart storage are never touched by it.

> Tip for the website repo: when shipping a site update, bump the cache
> version string in `sw.js` (`benamor-pricechecker-v1` → `-v2`) so cached
> assets refresh promptly for every device.

---

## Building the APK yourself

### A) Android Studio (recommended on Windows)

1. Install **Android Studio Hedgehog (2023.1.1) or newer** with its JDK 17.
2. **File → Open** → select the `android` folder.
3. Let it sync (it downloads Gradle 8.5 + AGP 8.2.2 + SDK Platform 34
   automatically).
4. **Build APKs:**
   - Debug: `Build → Build Bundle(s)/APK(s) → Build APK(s)` →
     `app/build/outputs/apk/debug/app-debug.apk`
   - Release: `Build → Generate Signed App Bundle / APK → APK` using
     `keystore/benamor-release.jks` (or run `assembleRelease` — the release
     signing config is already wired to that keystore via
     `gradle.properties`).
5. From the command line inside the `android` folder (optional):
   ```bat
   gradlew.bat assembleDebug
   gradlew.bat assembleRelease
   ```

### B) Linux / CI without Android Studio

`./build-apk.sh` builds both APKs into `apk/` using only open toolchain
components (see [How the shipped APKs were built](#how-the-shipped-apks-were-built)).
`BOOTSTRAP=1 ./build-apk.sh` also downloads the toolchain pieces first.

> The project deliberately uses **plain Java + framework APIs, zero external
> dependencies** (no AndroidX, no Kotlin runtime, no Play Services). That is
> why the APK is ~70 KB, builds anywhere, and has the smallest possible
> attack surface. Kotlin was avoided only so the shipped APK can be built and
> audited with the smallest toolchain; the app's logic is dependency-free and
> ports to Kotlin trivially inside Android Studio if you prefer.

### Version bumps

Edit `versionCode` / `versionName` in `app/build.gradle` **and** the two
variables at the top of `build-apk.sh`, then rebuild. (Higher `versionCode`
is required for `adb install -r` updates over an existing install.)

---

## Configuration reference

Everything lives in
`app/src/main/java/com/benamorgroup/pricechecker/Config.java`:

| Constant | Default | Notes |
|---|---|---|
| `APP_URL` | `https://bunomargroup-sketch.github.io/pricechecker2/` | the hosted web app |
| `APP_HOST` | `bunomargroup-sketch.github.io` | HTTPS is forced for this host |
| `ALLOWED_NAV_HOSTS` | site, Supabase project, Google Fonts | the only hosts that may open in the WebView |
| `LOCK_TASK_PACKAGES` | app + settings + print spooler + file picker + WhatsApp | packages allowed on top in true Lock Task |
| `DEFAULT_ADMIN_PIN` | `1234` | first-run PIN — change it in the admin menu |
| `SECRET_TAPS` | `7` | logo taps needed to open the admin PIN prompt |
| `PAGE_LOAD_TIMEOUT_MS` | `45000` | initial-load watchdog before showing the retry screen |

Orientation is `portrait` by default — change it at runtime from the admin
menu, or statically via `android:screenOrientation` in the manifest.

---

## Replacing the launcher icon / splash logo

The launcher icon and the splash logo both come from **one file** in the
website repo: `icons/icon-512.png` (the same icon the web manifest uses).

1. Replace `icons/icon-512.png` with the final BEN AMOR GROUP logo
   (square, 512×512).
2. Regenerate the Android icons:
   ```
   pip install pillow
   python android/scripts/make_icons.py
   ```
3. Rebuild the APK.

That produces all densities (`mipmap-*dpi/ic_launcher.png`,
`ic_launcher_round.png`, adaptive-icon foregrounds). The splash screen shows
the icon plus "BEN AMOR GROUP / Price Checker" text (in
`res/layout/activity_main.xml`).

---

## What the WebView enables (feature map)

Mapped to what the Price Checker actually uses (verified against
`index.html` / `admin.html` / `admin-shop.html`):

| Web app feature | Kiosk support |
|---|---|
| Login session (localStorage `bo_*`) | DOM storage on, persisted across restarts, never auto-cleared |
| Saved carts / history (localStorage + Supabase sync) | same + HTTPS to `*.supabase.co` allowed |
| Service worker / offline PWA (`sw.js`) | fully enabled, normal HTTP cache, never cleared on launch |
| Quotation "print / save as PDF" (`window.open('')` + `document.write` + `print()`) | popup WebView + `window.print` bridge to the Android print dialog (Save as PDF), plus a native طباعة/إغلاق bar on the popup |
| WhatsApp share (`https://wa.me/?text=…`) | handed to WhatsApp via Android intent; graceful Arabic toast if WhatsApp is missing |
| Google Fonts (CSS + files) | loaded as sub-resources, allowed |
| CSV import / photo upload (`<input type=file>` on admin pages) | system file picker (no permissions needed) |
| Clipboard copy (نسخ) | standard WebView behavior |
| Camera / barcode | the web app does not use it today; WebView plumbing is in place — see Security notes |
| Downloads (future) | routed to the system DownloadManager → `Downloads/BenAmor/` |

---

## Security notes

- **HTTPS only** for the main site — `http://` links are upgraded; mixed
  content is blocked; **SSL errors are never ignored**.
- **No file-system access** for the WebView (`allowFileAccess=false`,
  `allowContentAccess=false`); `file:` / `content:` navigations blocked.
- **Navigation allowlist**: only the Price Checker host, the Supabase project
  and Google Fonts can navigate inside the app; everything else is blocked
  (staff cannot browse arbitrary sites).
- **Minimal JS bridge**: one interface (`BenAmorKiosk`) exposing exactly two
  no-argument methods (`print`, `onSecretTap`).
- **Admin PIN** stored as a salted SHA-256 hash (never plaintext), 60-second
  lockout after 5 wrong attempts, initial value configurable in `Config.java`.
- **No analytics, no ads, no Play Services, no extra permissions** — just
  INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED.
- `usesCleartextTraffic=false`, `allowBackup=false`.
- Supabase credentials are only the ones already public in the website's
  JavaScript — nothing additional is embedded in the APK.
- **If a future web version adds barcode/camera scanning:** add
  `<uses-permission android:name="android.permission.CAMERA"/>` to the
  manifest, rebuild, and grant the request in
  `MainChromeClient.onPermissionRequest` (the deny stub is marked in
  `MainActivity.java`).

---

## Test checklist for the showroom tablet

Run through these after installing (they mirror the intended flows):

| # | Scenario | Expectation |
|---|---|---|
| 1 | First launch online | splash → app loads fullscreen, no browser UI |
| 2 | Login (identifier + code) | works as in the browser |
| 3 | Kill app, relaunch while logged in | still logged in (localStorage persists) |
| 4 | Search products | results as usual |
| 5 | Add to cart, kill app, relaunch | cart restored |
| 6 | Disconnect Wi-Fi after first load | Arabic offline banner; app keeps working from cache |
| 7 | Reconnect | banner disappears; sync resumes |
| 8 | Update the website on GitHub | tablet gets new version on next launch / within ~10 min |
| 9 | Admin → تحديث / فحص أحدث إصدار | new version appears immediately |
| 10 | Reboot the tablet | app auto-starts (if enabled + owner/appops/launcher set) |
| 11 | Enter kiosk (admin menu) | pinned; with Device Owner: no dialog, Home/Recents blocked |
| 12 | Exit kiosk with admin PIN | logo ×7 → PIN → إيقاف وضع الكشك |
| 13 | Back / Home / Recents in kiosk | Back stays in app; Home/Recents blocked (owner mode) |
| 14 | Quotation print | print preview opens; "Save as PDF" produces the PDF |
| 15 | WhatsApp share | WhatsApp opens with the cart text; graceful message if absent |
| 16 | Screen stays on | display never sleeps while the app is visible |

Static verification already performed on the shipped APK: signed v2+v3,
`resources.arsc` stored & 4-byte aligned (Android 11 install requirement),
zipalign-verified, manifest/dex/resources parsed with two independent tools
(aapt2 + androguard).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "App not installed" | Existing install has a different signature → `adb uninstall com.benamorgroup.pricechecker` first, or install the matching variant (debug vs release). |
| Old website version shown | Wait ≤10 min (GitHub Pages/SW cache) or use admin → تحديث / فحص أحدث إصدار. |
| Blank screen after a renderer crash | app auto-recovers; if not, admin → إعادة تحميل التطبيق. |
| Print dialog does not appear | Check that a print service exists (Settings → Printing); "Save as PDF" is built into AOSP/LineageOS. |
| WhatsApp share says blocked | You are in pinned (non-owner) kiosk — use Device Owner mode (WhatsApp is whitelisted), or exit kiosk to share. |
| App does not start after reboot | See [Auto-start after reboot](#auto-start-after-reboot) — set Device Owner or run the appops command. |
| Forgot the admin PIN | `adb shell pm clear com.benamorgroup.pricechecker` resets ALL app data including the PIN (also logs out the site). |

---

## How the shipped APKs were built

The APKs in `apk/` were produced by `android/build-apk.sh` from this exact
source tree, without Android Studio, using only open/reproducible components
(all fetched from public mirrors — see the script):

- **aapt2 2.20** (resource compile/link) — from the `aaptjs3` npm package,
  which bundles Google's official aapt2 binaries
- **javac 17** compiling to Java 8 bytecode — OpenJDK 17 from the AOSP
  prebuilts mirror (`platform.prebuilts.jdk.jdk17`, linux-x86)
- **android.jar (API 34)** — from the `Sable/android-platforms` mirror
- **D8** — `r8-master.jar` from the AOSP/LineageOS prebuilts mirror
  (desugars lambdas to minSdk 24)
- **apksigner** — compiled from AOSP `tools/apksig` source (LineageOS mirror)
- **zipalign** — AOSP prebuilts build-tools (LineageOS mirror)

The equivalent Gradle project in this folder builds the same sources with
standard AGP in Android Studio — keep the two version variables in sync when
bumping versions.
