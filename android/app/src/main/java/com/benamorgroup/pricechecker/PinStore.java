package com.benamorgroup.pricechecker;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Stores the admin PIN as a salted SHA-256 hash in the app's private
 * preferences (never in plain text). The initial value comes from
 * {@link Config#DEFAULT_ADMIN_PIN} and can be changed from the admin menu.
 * <p>
 * Note: this protects the PIN at rest on a non-rooted device; it is the
 * standard pragmatic approach for an internal kiosk tool. Keystore-backed
 * encryption would require external libraries, which this APK deliberately
 * avoids.
 */
final class PinStore {

    private PinStore() {
        // no instances
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(App.PREFS, Context.MODE_PRIVATE);
    }

    /** Make sure a PIN hash exists; on first run seed it with the default PIN. */
    static synchronized void ensureInitialized(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("pinHash")) {
            set(c, Config.DEFAULT_ADMIN_PIN);
        }
    }

    static synchronized void set(Context c, String pin) {
        SharedPreferences p = prefs(c);
        String salt = p.getString("pinSalt", null);
        if (salt == null) {
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            salt = toHex(raw);
        }
        String hash = toHex(sha256(salt + pin));
        p.edit().putString("pinSalt", salt).putString("pinHash", hash).commit();
    }

    static synchronized boolean verify(Context c, String pin) {
        SharedPreferences p = prefs(c);
        String salt = p.getString("pinSalt", "");
        String stored = p.getString("pinHash", null);
        if (stored == null) {
            return false;
        }
        byte[] a = sha256(salt + pin);
        byte[] b = fromHex(stored);
        return MessageDigest.isEqual(a, b); // constant-time comparison
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e); // never on Android
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
