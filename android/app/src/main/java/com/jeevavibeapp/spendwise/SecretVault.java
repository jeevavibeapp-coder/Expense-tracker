package com.jeevavibeapp.spendwise;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed storage for the app's secrets.
 *
 * <p>Before this class, both secrets lived in plaintext:
 * <ul>
 *   <li>the loopback device token in {@code SharedPreferences("spendwise")} —
 *       readable verbatim from a backup, an ADB pull on a rooted/debuggable
 *       device, or any offline image of {@code /data/data};</li>
 *   <li>the Flask session key in the {@code app_state} SQLite table — same
 *       exposure, and it signs the session cookie.</li>
 * </ul>
 *
 * <p>Both now live as AES-256/GCM ciphertext. The wrapping key is generated
 * <em>inside</em> AndroidKeyStore and is marked non-exportable, so on a device
 * with a TEE/StrongBox the raw key material never enters app memory at all and
 * cannot leave the device even with root. An attacker holding a copy of
 * {@code /data/data} therefore holds ciphertext with no key.
 *
 * <p><b>Never fails closed.</b> A handful of OEM keystores are genuinely broken
 * (corrupt keystore blobs after an OTA, vendors whose TEE rejects GCM). If any
 * keystore operation throws, the vault degrades to plaintext under a distinct
 * {@code p0:} marker and records {@link #isDegraded}. Losing SMS capture on
 * those devices would be a far worse outcome than the exposure this class
 * removes, and the degradation is observable rather than silent.
 *
 * <p>Thread-safe: SMS arrives on receiver threads while the UI thread boots.
 */
final class SecretVault {

    private static final String TAG = "SpendWiseVault";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "spendwise_vault_v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    /** Deliberately a different file from "spendwise" so an audit can assert
     *  that the legacy prefs file holds no secret at all. */
    private static final String SECURE_PREFS = "spendwise_secure";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final int GCM_IV_BYTES = 12;   // NIST-recommended for GCM
    private static final int GCM_TAG_BITS = 128;
    private static final String ENC_PREFIX = "v1:";
    private static final String PLAIN_PREFIX = "p0:";

    /** Names used by the app. Public so tests/audits can enumerate them. */
    static final String DEVICE_TOKEN = "device_token";
    static final String SESSION_SECRET = "session_secret";

    /** Legacy plaintext locations that {@link #migrate} drains and erases. */
    private static final String LEGACY_PREFS = "spendwise";
    private static final String LEGACY_TOKEN_KEY = "sms_token";

    private static final Object LOCK = new Object();
    private static volatile boolean degraded = false;

    private SecretVault() {}

    /** True once any keystore operation has failed and the vault fell back to
     *  plaintext. Surfaced for diagnostics — do not treat as fatal. */
    static boolean isDegraded() {
        return degraded;
    }

    /**
     * Return the named secret, generating and storing a fresh 256-bit random
     * value the first time it is asked for. Never returns null or empty.
     */
    static String getOrCreate(Context context, String name) {
        synchronized (LOCK) {
            SharedPreferences prefs = securePrefs(context);
            String stored = prefs.getString(name, null);
            if (stored != null && !stored.isEmpty()) {
                String plain = decode(stored);
                if (plain != null && !plain.isEmpty()) {
                    return plain;
                }
                // Undecodable: the keystore key was lost (app data cleared on
                // some OEMs, or key invalidated). Rotating is the only way
                // forward and is safe — see rotate()'s contract.
                Log.w(TAG, "Secret '" + name + "' could not be decrypted; rotating");
            }
            return store(context, name, randomSecret());
        }
    }

    /**
     * Replace the named secret with a fresh random value and return it.
     *
     * <p>Safe for both current secrets because neither authenticates stored
     * data: the device token is handed to the embedded server at every launch
     * (so both sides always agree), and the session key only signs cookies (so
     * rotating logs out a session that single-user mode re-establishes on the
     * next request). Rotating would NOT be safe for a key that encrypts the
     * ledger — nothing does.
     */
    static String rotate(Context context, String name) {
        synchronized (LOCK) {
            return store(context, name, randomSecret());
        }
    }

    /**
     * One-time upgrade from the plaintext era. Idempotent; cheap after the
     * first call.
     *
     * <p>The legacy device token is <em>rotated, not imported</em>. Importing
     * it would re-seal a value that has already been sitting in a
     * world-readable-by-backup prefs file for the installed lifetime of the
     * app, so its confidentiality is already spent; a fresh value is strictly
     * better and costs nothing because the token is re-handed to the server on
     * every launch. The plaintext entry is then removed, so a post-upgrade
     * image of {@code /data/data} contains no usable secret.
     *
     * @return true if a legacy plaintext secret was found and erased.
     */
    static boolean migrate(Context context) {
        SharedPreferences legacy =
                context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String old = legacy.getString(LEGACY_TOKEN_KEY, null);
        if (old == null) {
            return false;
        }
        synchronized (LOCK) {
            store(context, DEVICE_TOKEN, randomSecret());
            // commit(), not apply(): the erase must be durable before we
            // report success, otherwise a crash here leaves the plaintext
            // behind while the report says it is gone.
            legacy.edit().remove(LEGACY_TOKEN_KEY).commit();
        }
        Log.i(TAG, "Migrated device token out of plaintext prefs (rotated)");
        return true;
    }

    // ── internals ────────────────────────────────────────────────────────

    private static SharedPreferences securePrefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE);
    }

    private static String randomSecret() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String store(Context context, String name, String value) {
        securePrefs(context).edit().putString(name, encode(value)).commit();
        return value;
    }

    private static String encode(String plain) {
        try {
            SecretKey key = masterKey();
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(UTF8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return ENC_PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            degraded = true;
            Log.e(TAG, "Keystore unavailable; storing secret unprotected", t);
            return PLAIN_PREFIX + plain;
        }
    }

    private static String decode(String stored) {
        if (stored.startsWith(PLAIN_PREFIX)) {
            degraded = true;
            return stored.substring(PLAIN_PREFIX.length());
        }
        if (!stored.startsWith(ENC_PREFIX)) {
            return null;   // unknown format — force a rotation
        }
        try {
            byte[] blob = Base64.decode(stored.substring(ENC_PREFIX.length()), Base64.NO_WRAP);
            if (blob.length <= GCM_IV_BYTES) {
                return null;
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(blob, GCM_IV_BYTES, blob.length - GCM_IV_BYTES);
            return new String(plain, UTF8);
        } catch (Throwable t) {
            // AEADBadTagException here means the blob was tampered with — GCM
            // authenticates, so a modified ciphertext is rejected rather than
            // decrypting to attacker-chosen bytes.
            Log.w(TAG, "Secret decrypt failed", t);
            return null;
        }
    }

    /** Fetch (or create) the non-exportable AES key held by AndroidKeyStore. */
    private static SecretKey masterKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Explicitly NOT user-authentication-bound: SMS arrives while
                // the screen is locked, and a receiver that cannot read the
                // token would drop the capture the app exists to perform.
                .setUserAuthenticationRequired(false)
                // Randomised IV per operation, enforced by the keystore — the
                // one mistake that would break GCM outright.
                .setRandomizedEncryptionRequired(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Survive adding/removing a lock screen; otherwise a user setting a
            // PIN for the first time would invalidate the key and silently
            // wipe both secrets.
            spec.setInvalidatedByBiometricEnrollment(false);
        }
        gen.init(spec.build());
        return gen.generateKey();
    }
}
