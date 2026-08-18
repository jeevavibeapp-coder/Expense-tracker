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
    // A COLD first launch has to do a lot before /healthz can answer: Chaquopy
    // unpacks Python and the stdlib, Flask and waitress import, then ten schema
    // migrations run against a database that does not exist yet. Twenty seconds
    // was enough on a fast phone and not on a slow one, where it produced
    // "the app engine didn't respond" for a server that was simply still
    // starting. Polling is cheap, so a higher ceiling costs nothing when
    // startup is quick — it only changes how long a slow device may take.
    private static final long SERVER_TIMEOUT_MS = 90000L;
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
        // bootstrap() also registers the WorkManager fallback sweep, on its
        // worker thread — see the comment there.
        bootstrap();
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
    /** Populated by bootstrap() off the UI thread. onResume reads this rather
     *  than calling into AndroidKeyStore, which was a binder round trip on the
     *  main thread on EVERY return to the app. */
    private volatile String cachedDeviceToken = null;

    /** Start the Python interpreter + embedded server fully off the UI thread
     *  (Python.start unpacks the runtime on first launch — too slow for main).
     *  Guarded so Retry taps can't stack concurrent bootstraps. */
    private void bootstrap() {
        if (bootstrapping) {
            return;
        }
        bootstrapping = true;
        final android.content.Context appContext = getApplicationContext();
        // NOTHING expensive may run before this thread starts. Everything
        // below used to execute on the UI thread inside onCreate: two
        // AndroidKeyStore round trips (with first-run key generation, which
        // costs hundreds of milliseconds on StrongBox), three synchronous
        // SharedPreferences commit() disk writes, getFilesDir(), and
        // WorkManager's Room database init. That is a cold-start ANR waiting
        // to happen on a low-end device.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String filesDir = getFilesDir().getAbsolutePath();
                    // Drain any pre-Keystore plaintext token before anything
                    // reads one.
                    SecretVault.migrate(appContext);
                    String token = getDeviceToken();
                    String secret = getSessionSecret();
                    // Cached so onResume never has to touch the keystore.
                    cachedDeviceToken = token;
                    if (!Python.isStarted()) {
                        Python.start(new AndroidPlatform(appContext));
                    }
                    startServerAndLoad(filesDir, token, secret);
                    // WorkManager.getInstance() opens a Room database, so it
                    // belongs here rather than in onCreate.
                    SmsCatchUpWorker.schedule(appContext);
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
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // Keystore reads and getFilesDir() belong here, not on
                        // the UI thread this callback arrives on.
                        catchUpFromInbox(getFilesDir().getAbsolutePath(),
                                         getDeviceToken(), getSessionSecret());
                    }
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

    /** Is this permission actually in OUR merged manifest?
     *
     * The nosms flavour strips READ_SMS and RECEIVE_SMS, because Play Protect
     * refuses to sideload any app that asks for them. Requesting a permission
     * the manifest does not declare is not a crash — the system returns
     * PERMISSION_DENIED immediately — but the app would then show "SMS access
     * is off, tap Allow" forever, on a build where there is nothing to allow.
     * So the request, and the reported state, are both gated on this. */
    private boolean isPermissionDeclared(String permission) {
        try {
            String[] declared = getPackageManager()
                    .getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;
            if (declared == null) {
                return false;
            }
            for (String p : declared) {
                if (permission.equals(p)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w("SpendWise", "Could not read declared permissions", t);
        }
        return false;
    }

    /** True on the full build, false on the no-SMS build. */
    private boolean smsCaptureIsPossible() {
        return isPermissionDeclared(Manifest.permission.RECEIVE_SMS)
                && isPermissionDeclared(Manifest.permission.READ_SMS);
    }

    private void requestSmsPermissions() {
        if (!smsCaptureIsPossible()) {
            return;              // nothing to ask for on this build
        }
        if (!hasSmsPermission() || !hasReadSmsPermission()) {
            // getSharedPreferences blocks on the first call while the file is
            // read, and this runs from onCreate — so the write goes to a
            // worker. requestPermissions itself must stay on the UI thread.
            final android.content.Context appContext = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean("sms_asked", true).apply();
                }
            }, "spendwise-prefs").start();
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
        // checkSelfPermission is a cheap in-process lookup; everything that
        // touches disk or the keystore happens on the worker below. This runs
        // from onResume, i.e. on EVERY return to the app, so it is the hottest
        // main-thread path in the activity.
        final boolean granted = hasSmsPermission();
        final boolean capturePossible = smsCaptureIsPossible();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String token = cachedDeviceToken;
                if (token == null) {
                    token = getDeviceToken();      // off the UI thread
                    cachedDeviceToken = token;
                }
                // Only reload the WebView when the state actually flips to
                // granted — reloading on every onResume would wipe scroll and
                // form state each time the user returns to the app.
                SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                final boolean changed = p.getInt("last_perm", -1) != (granted ? 1 : 0);
                p.edit().putInt("last_perm", granted ? 1 : 0).apply();
                boolean delivered = postState(granted, capturePossible, token);
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

    private boolean postState(boolean granted, boolean capturePossible,
                              String token) {
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
            // Three states, not two. "unavailable" lets the UI say that this
            // build cannot capture SMS at all, instead of blaming the user for
            // a permission they were never offered.
            String state = !capturePossible ? "unavailable"
                                            : (granted ? "granted" : "denied");
            String form = "sms_permission=" + state;
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

    private void startServerAndLoad(String filesDir, final String token, String secret) {
        try {
            Python.getInstance()
                    .getModule("spendwise.android_entry")
                    .callAttr("start_server", filesDir, token, secret);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start embedded Python server", t);
            showStartupError(t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }

        if (waitForServer()) {
            reportPermissionState(false);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final android.webkit.WebView wv = getBridge().getWebView();
                        // Authenticate this WebView to the embedded server. The
                        // server mints a signed session cookie from this
                        // one-time grant and immediately redirects, so the token
                        // does not persist in the page URL. Without it every
                        // page returns 403 — which is the point: a co-installed
                        // app reaching 127.0.0.1 cannot supply this token.
                        wv.loadUrl(SERVER_URL + "/?k=" + Uri.encode(token));
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
            // The server thread may have died with an exception that never
            // reached this thread — start_server() returns as soon as the
            // thread is spawned. Ask for it before blaming the clock.
            String reason = pythonStartupError();
            Log.e(TAG, "Embedded server not ready within " + SERVER_TIMEOUT_MS
                    + "ms" + (reason.isEmpty() ? "" : "; python said: " + reason));
            showStartupError(reason);
        }
    }

    /** Whatever killed the Python server thread, or "" if it is merely slow. */
    private String pythonStartupError() {
        try {
            return Python.getInstance()
                    .getModule("spendwise.android_entry")
                    .callAttr("startup_error").toString();
        } catch (Throwable t) {
            return "";
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

    private void showStartupError(final String reason) {
        // A retry button and nothing else asks the user to guess. When Python
        // told us why, show it — that is the difference between "try again"
        // and a line someone can search for or send on.
        final String detail = (reason == null || reason.isEmpty())
                ? ""
                : "<pre>" + android.text.TextUtils.htmlEncode(reason) + "</pre>";
        final String html =
            "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;font-family:-apple-system,Roboto,sans-serif;"
            + "background:linear-gradient(135deg,#8b5cff,#5b8cff);color:#fff;display:flex;align-items:center;"
            + "justify-content:center;text-align:center}.b{padding:28px}h1{font-size:20px;margin:0 0 8px}"
            + "p{opacity:.9;font-size:14px;margin:0 0 22px}button{padding:13px 28px;border:none;border-radius:999px;"
            + "font-size:15px;font-weight:700;color:#5b3cff;background:#fff}"
            + "pre{white-space:pre-wrap;word-break:break-word;font-size:11px;opacity:.9;"
            + "background:rgba(0,0,0,.25);padding:10px;border-radius:10px;margin:0 0 18px;text-align:left}"
            + "</style></head><body><div class='b'>"
            + "<h1>Couldn't start SpendWise</h1><p>The app engine didn't respond. Please try again.</p>"
            + detail
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
