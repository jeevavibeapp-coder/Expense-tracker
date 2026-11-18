package com.jeevavibeapp.spendwise;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

/**
 * Catch-up scan of the device SMS inbox.
 *
 * The broadcast receiver alone is not reliable in the real world:
 *   · On MIUI/HyperOS, ColorOS, EMUI and similar, a manifest-declared receiver
 *     does not fire unless the user enables "Autostart" for the app.
 *   · When the app process is dead the embedded server is down, so a live POST
 *     cannot succeed.
 *   · The user may install the app long after the messages arrived.
 *
 * So on every launch we read recent inbox messages, keep the finance-looking
 * ones, and append them to the SAME queue file the receiver uses. Python's
 * existing drain replays them, and content-hash/reference dedup guarantees a
 * message already captured is never counted twice — which makes re-scanning
 * completely safe.
 */
final class SmsInboxScanner {

    private static final String TAG = "SpendWiseScan";
    private static final String PREFS = "spendwise";
    private static final String LAST_SCAN = "last_sms_scan";
    private static final String INBOX_FILE = "sms_inbox.jsonl";
    // First run looks back this far; later runs re-scan a small overlap so a
    // message that arrived mid-scan is never skipped (dedup makes it free).
    private static final long FIRST_RUN_LOOKBACK_MS = 90L * 24 * 60 * 60 * 1000;
    private static final long OVERLAP_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_MESSAGES = 500;

    private SmsInboxScanner() {}

    /** Returns how many finance SMS were queued for ingest. */
    static int scan(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(LAST_SCAN, 0L);
        long now = System.currentTimeMillis();
        long since = (last > 0) ? Math.max(0, last - OVERLAP_MS) : (now - FIRST_RUN_LOOKBACK_MS);

        Cursor cur = null;
        int queued = 0;
        StringBuilder batch = new StringBuilder();
        try {
            cur = context.getContentResolver().query(
                    Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"},
                    "date > ?", new String[]{String.valueOf(since)},
                    "date DESC");
            if (cur == null) {
                return 0;
            }
            int iAddr = cur.getColumnIndex("address");
            int iBody = cur.getColumnIndex("body");
            int seen = 0;
            while (cur.moveToNext() && seen < MAX_MESSAGES) {
                seen++;
                String body = iBody >= 0 ? cur.getString(iBody) : null;
                if (body == null || body.isEmpty() || !SmsReceiver.looksFinancial(body)) {
                    continue;
                }
                String addr = iAddr >= 0 ? cur.getString(iAddr) : null;
                JSONObject o = new JSONObject();
                o.put("sender", addr == null ? "" : addr);
                o.put("body", body);
                batch.append(o.toString()).append("\n");
                queued++;
            }
        } catch (Throwable t) {
            // Missing READ_SMS, an OEM-restricted provider, or a malformed row:
            // never let a scan failure break app startup.
            Log.w(TAG, "Inbox scan skipped", t);
            return 0;
        } finally {
            if (cur != null) {
                try { cur.close(); } catch (Throwable ignored) {}
            }
        }

        if (queued > 0) {
            File file = new File(context.getFilesDir(), INBOX_FILE);
            synchronized (SmsReceiver.QUEUE_LOCK) {
                try (BufferedWriter w = new BufferedWriter(new FileWriter(file, true))) {
                    w.write(batch.toString());
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to queue scanned SMS", t);
                    return 0;
                }
            }
        }
        // Only advance the watermark once the batch is safely on disk.
        prefs.edit().putLong(LAST_SCAN, now).apply();
        Log.d(TAG, "Inbox scan queued " + queued + " finance SMS");
        return queued;
    }
}
