package com.jeevavibeapp.spendwise;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "SpendWise";
    private static final String SERVER_URL = "http://127.0.0.1:8765";
    // How long to wait for the embedded Flask server to come up before giving
    // up and leaving the bundled `dist` splash visible.
    private static final long SERVER_TIMEOUT_MS = 15000L;
    private static final long POLL_INTERVAL_MS = 250L;
    private static final int SMS_PERMISSION_REQUEST = 4011;
    private static final String PREFS = "spendwise";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // BridgeActivity.onCreate loads the bundled `dist` web assets, which act
        // as a brief splash screen until the embedded server is ready.
        super.onCreate(savedInstanceState);
        registerPlugin(SpendWisePlugin.class);

        // Expose a tiny bridge so the in-app "Allow SMS access" banner (served
        // by the embedded web app) can re-trigger the native permission flow.
        try {
            getBridge().getWebView().addJavascriptInterface(new SmsBridge(), "AndroidSms");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to attach SMS bridge", t);
        }

        // Ask for SMS access up front — without RECEIVE_SMS the broadcast that
        // powers automatic transaction capture is never delivered.
        requestSmsPermissions();

        // Start the Chaquopy Python interpreter (must happen before any Python
        // is invoked). Idempotent.
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        // Launch the Flask server and the readiness poll off the UI thread.
        final String filesDir = getFilesDir().getAbsolutePath();
        new Thread(new Runnable() {
            @Override
            public void run() {
                startServerAndLoad(filesDir);
            }
        }, "spendwise-bootstrap").start();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The user may have just returned from the system settings screen —
        // refresh the permission state so the banner appears/disappears.
        reportPermissionState(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == SMS_PERMISSION_REQUEST) {
            reportPermissionState(true);
        }
    }

    private boolean hasSmsPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Request RECEIVE_SMS / READ_SMS at runtime (Android 6+) if not granted. */
    private void requestSmsPermissions() {
        String[] wanted = { Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS };
        List<String> needed = new ArrayList<>();
        for (String perm : wanted) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }
        if (!needed.isEmpty()) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("sms_asked", true).apply();
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]),
                    SMS_PERMISSION_REQUEST);
        }
    }

    /** Called from the web banner: re-prompt, or deep-link to settings if the
     *  permission was permanently denied ("Don't ask again"). */
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
            // Permanently denied — the system dialog won't show again.
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

    /** Tell the embedded app whether SMS capture is currently allowed, then
     *  optionally reload so the banner reflects the new state. */
    private void reportPermissionState(final boolean reloadOnChange) {
        final boolean granted = hasSmsPermission();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean delivered = postState(granted);
                if (delivered && granted && reloadOnChange) {
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

    private boolean postState(boolean granted) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(SERVER_URL + "/device/state");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String form = "sms_permission=" + (granted ? "granted" : "denied");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;  // server not up yet; reported again once it is
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void startServerAndLoad(String filesDir) {
        try {
            // spendwise.android_entry.start_server(files_dir) starts Flask on a
            // daemon thread (idempotent) and returns the URL string.
            Python.getInstance()
                    .getModule("spendwise.android_entry")
                    .callAttr("start_server", filesDir);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start embedded Python server", t);
            return;
        }

        if (waitForServer()) {
            // Server is up — make sure it knows the current permission state.
            reportPermissionState(false);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        getBridge().getWebView().loadUrl(SERVER_URL);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to load server URL in WebView", t);
                    }
                }
            });
        } else {
            Log.e(TAG, "Embedded server did not become ready within timeout");
        }
    }

    /** Poll the local server until it responds or the timeout elapses. */
    private boolean waitForServer() {
        long deadline = System.currentTimeMillis() + SERVER_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(SERVER_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                // Any HTTP response means the server socket is up and serving.
                if (code > 0) {
                    return true;
                }
            } catch (Exception e) {
                // Server not up yet; keep polling.
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
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

    /** JavaScript-callable bridge for the in-app permission banner. */
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
    }
}
