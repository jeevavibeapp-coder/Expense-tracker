package com.jeevavibeapp.spendwise;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // BridgeActivity.onCreate loads the bundled `dist` web assets, which act
        // as a brief splash screen until the embedded server is ready.
        super.onCreate(savedInstanceState);
        registerPlugin(SpendWisePlugin.class);

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
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]),
                    SMS_PERMISSION_REQUEST);
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
}
