package com.jeevavibeapp.spendwise;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.BridgeActivity;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "SpendWise";
    private static final String SERVER_URL = "http://127.0.0.1:8765";
    private static final long SERVER_TIMEOUT_MS = 20000L;  // extra headroom on low-end devices
    private static final long POLL_INTERVAL_MS = 250L;
    private static final int SMS_PERMISSION_REQUEST = 4011;
    private static final String PREFS = "spendwise";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerPlugin(SpendWisePlugin.class);

        // Edge-to-edge: let our content draw behind system bars on all API levels.
        // On API 35+ Android enforces this anyway; on older versions we opt in.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Apply insets as padding on the root view so the WebView content is
        // never obscured by nav bars / notches / the keyboard on any device.
        View rootView = getWindow().getDecorView().getRootView();
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(0, top, 0, Math.max(nav, ime));
            return WindowInsetsCompat.CONSUMED;
        });

        applyWebViewCompatibility();

        try {
            getBridge().getWebView().addJavascriptInterface(new SmsBridge(), "AndroidSms");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach SMS bridge", t);
        }

        requestSmsPermissions();
        bootstrap();
        // Fallback sweep for devices where the broadcast receiver never
        // fires (MIUI/ColorOS Autostart) or the process was dead.
        SmsCatchUpWorker.schedule(getApplicationContext());
    }

    /** Harden WebView settings for compatibility across Android 7–15+. */
    private void applyWebViewCompatibility() {
        try {
            WebView wv = getBridge().getWebView();
            WebSettings ws = wv.getSettings();

            // Allow mixed content from the loopback server (http://127.0.0.1).
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

            // Smooth scrolling and touch responsiveness improvements.
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);

            // Viewport meta tags are honoured on all devices.
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);

            // Disable safe-browsing to avoid network calls from the WebView while
            // the embedded server is still booting; the embedded app is trusted.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(ws, false);
            }

            // Force dark mode in the WebView to respect the user's system setting
            // on devices that support it (API 29+ / WebView 76+).
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "WebView compatibility setup partial failure", t);
        }
    }

    private volatile boolean bootstrapping = false;

    /** Start the Python interpreter + embedded server fully off the UI thread
     *  (Python.start unpacks the runtime on first launch — too slow for main).
     *  Guarded so Retry taps can't stack concurrent bootstraps. */
    private void bootstrap() {
        if (bootstrapping) {
            return;
        }
        bootstrapping = true;
        final android.content.Context appContext = getApplicationContext();
        final String filesDir = getFilesDir().getAbsolutePath();
        // Drain any pre-Keystore plaintext token before anything reads one.
        SecretVault.migrate(appContext);
        final String token = getDeviceToken();
        final String secret = getSessionSecret();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!Python.isStarted()) {
                        Python.start(new AndroidPlatform(appContext));
                    }
                    startServerAndLoad(filesDir, token, secret);
                } finally {
                    bootstrapping = false;
                }
            }
        }, "spendwise-bootstrap").start();
    }

    /** Stable per-install secret — only this app can POST to loopback ingest
     *  endpoints. Held as AES-GCM ciphertext under an AndroidKeyStore key; see
     *  {@link SecretVault}. */
    private String getDeviceToken() {
        return SecretVault.getOrCreate(getApplicationContext(), SecretVault.DEVICE_TOKEN);
    }

    /** Signs the Flask session cookie. Previously generated in Python and
     *  persisted as plaintext in the app_state table; now supplied by the
     *  keystore-backed vault so it never touches the database at all. */
    private String getSessionSecret() {
        return SecretVault.getOrCreate(getApplicationContext(), SecretVault.SESSION_SECRET);
    }

    @Override
    public void onResume() {
        super.onResume();
        reportPermissionState(true);
    }

    /** Hardware/gesture back walks the in-app WebView history (it's a
     *  multi-page server-rendered app); only exit at the first page. */
    @Override
    public void onBackPressed() {
        try {
            android.webkit.WebView wv = getBridge().getWebView();
            if (wv != null && wv.canGoBack()) {
                // Only walk back into the app's own (loopback) pages. Anything
                // else in history is the bundled loading shell — backing into
                // it would strand the user outside the real app.
                android.webkit.WebBackForwardList list = wv.copyBackForwardList();
                int prev = list.getCurrentIndex() - 1;
                if (prev >= 0) {
                    String url = list.getItemAtIndex(prev).getUrl();
                    if (url != null && url.startsWith(SERVER_URL)) {
                        wv.goBack();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_REQUEST) {
            reportPermissionState(true);
            // Permission may have just been granted — pull in existing bank
            // messages right away instead of making the user relaunch.
            if (hasReadSmsPermission()) {
                final String filesDir = getFilesDir().getAbsolutePath();
                final String token = getDeviceToken();
                final String secret = getSessionSecret();
                new Thread(new Runnable() {
                    @Override
                    public void run() { catchUpFromInbox(filesDir, token, secret); }
                }, "spendwise-sms-catchup").start();
            }
        }
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** READ_SMS powers the launch-time catch-up scan; it can be granted or
     *  denied independently of RECEIVE_SMS. */
    private boolean hasReadSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestSmsPermissions() {
        if (!hasSmsPermission() || !hasReadSmsPermission()) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("sms_asked", true).apply();
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS }, SMS_PERMISSION_REQUEST);
        }
    }

    private void handleGrantRequest() {
        if (hasSmsPermission()) {
            reportPermissionState(true);
            return;
        }
        boolean asked = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("sms_asked", false);
        boolean canPrompt = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.RECEIVE_SMS);
        if (asked && !canPrompt) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to open app settings", t);
            }
        } else {
            requestSmsPermissions();
        }
    }

    private void reportPermissionState(final boolean reloadOnChange) {
        final boolean granted = hasSmsPermission();
        final String token = getDeviceToken();
        // Only reload the WebView when the state actually flips to granted —
        // reloading on every onResume would wipe scroll/form state each time
        // the user returns to the app.
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final boolean changed = p.getInt("last_perm", -1) != (granted ? 1 : 0);
        p.edit().putInt("last_perm", granted ? 1 : 0).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean delivered = postState(granted, token);
                if (delivered && granted && changed && reloadOnChange) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                getBridge().getWebView().reload();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
            }
        }, "spendwise-perm-report").start();
    }

    private boolean postState(boolean granted, String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(SERVER_URL + "/device/state");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("X-SpendWise-Token", token);
            }
            String form = "sms_permission=" + (granted ? "granted" : "denied");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void startServerAndLoad(String filesDir, String token, String secret) {
        try {
            Python.getInstance()
                    .getModule("spendwise.android_entry")
                    .callAttr("start_server", filesDir, token, secret);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start embedded Python server", t);
            showStartupError();
            return;
        }

        if (waitForServer()) {
            reportPermissionState(false);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final android.webkit.WebView wv = getBridge().getWebView();
                        wv.loadUrl(SERVER_URL);
                        // Drop the bundled loading shell from history once the
                        // real app is up, so Back can never return to it.
                        // Only clears while the shell is still the OLDEST entry,
                        // so it can never discard real in-app history if the
                        // user navigates before this runs.
                        wv.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String cur = wv.getUrl();
                                    if (cur == null || !cur.startsWith(SERVER_URL)) {
                                        return;
                                    }
                                    android.webkit.WebBackForwardList list =
                                            wv.copyBackForwardList();
                                    if (list.getSize() > 0) {
                                        String first = list.getItemAtIndex(0).getUrl();
                                        boolean shellStillFirst =
                                                first == null || !first.startsWith(SERVER_URL);
                                        if (shellStillFirst) {
                                            wv.clearHistory();
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }, 1200);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to load server URL in WebView", t);
                    }
                }
            });
            catchUpFromInbox(filesDir, token, secret);
        } else {
            Log.e(TAG, "Embedded server did not become ready within timeout");
            showStartupError();
        }
    }

    /** Queue any finance SMS the live receiver missed, then drain the queue.
     *  This is what makes auto-capture actually work on devices where the
     *  broadcast receiver is throttled (MIUI "Autostart") or the app process
     *  was dead when the message arrived. Dedup makes re-scanning harmless. */
    private void catchUpFromInbox(final String filesDir, final String token,
                                  final String secret) {
        if (!hasReadSmsPermission()) {
            return;
        }
        try {
            int queued = SmsInboxScanner.scan(getApplicationContext());
            if (queued > 0) {
                // start_server is idempotent and kicks a fresh drain each call.
                Python.getInstance().getModule("spendwise.android_entry")
                        .callAttr("start_server", filesDir, token, secret);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Inbox catch-up failed", t);
        }
    }

    private void showStartupError() {
        final String html =
            "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;font-family:-apple-system,Roboto,sans-serif;"
            + "background:linear-gradient(135deg,#8b5cff,#5b8cff);color:#fff;display:flex;align-items:center;"
            + "justify-content:center;text-align:center}.b{padding:28px}h1{font-size:20px;margin:0 0 8px}"
            + "p{opacity:.9;font-size:14px;margin:0 0 22px}button{padding:13px 28px;border:none;border-radius:999px;"
            + "font-size:15px;font-weight:700;color:#5b3cff;background:#fff}</style></head><body><div class='b'>"
            + "<h1>Couldn't start SpendWise</h1><p>The app engine didn't respond. Please try again.</p>"
            + "<button onclick=\"if(window.AndroidSms&&AndroidSms.retry){AndroidSms.retry()}\">Retry</button>"
            + "</div></body></html>";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    getBridge().getWebView().loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private boolean waitForServer() {
        long deadline = System.currentTimeMillis() + SERVER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(SERVER_URL + "/healthz");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) return true;
            } catch (Exception e) {
                // Server not up yet; keep polling.
            } finally {
                if (conn != null) conn.disconnect();
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public class SmsBridge {
        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    handleGrantRequest();
                }
            });
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    bootstrap();
                }
            });
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            // Expose device info to the web app for diagnostics / analytics.
            return Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
        }
    }
}
