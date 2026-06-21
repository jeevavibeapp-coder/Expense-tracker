package com.jeevavibeapp.spendwise;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Captures incoming SMS and feeds finance messages straight into the embedded
 * SpendWise app — no copy/paste required.
 *
 * Flow:
 *   1. Filter to likely finance messages.
 *   2. POST {sender, body} to the on-device server (http://127.0.0.1:8765/sms/ingest).
 *   3. If the server isn't up (app closed), append to a queue file that the
 *      app drains on its next launch, so nothing is ever lost.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SpendWiseSms";
    private static final String INGEST_URL = "http://127.0.0.1:8765/sms/ingest";
    private static final String INBOX_FILE = "sms_inbox.jsonl";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            return;
        }
        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }
        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) {
            return;
        }

        // Concatenate multipart SMS bodies from the same sender.
        StringBuilder bodyBuilder = new StringBuilder();
        String sender = null;
        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu);
            if (sms == null) {
                continue;
            }
            bodyBuilder.append(sms.getMessageBody());
            if (sender == null) {
                sender = sms.getOriginatingAddress();
            }
        }
        final String body = bodyBuilder.toString();
        final String fSender = sender;
        if (body.isEmpty() || !looksFinancial(body)) {
            return;
        }

        // Network must not run on the main thread; keep the process alive while
        // we deliver the message.
        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!postToServer(fSender, body)) {
                        queue(appContext, fSender, body);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "SMS delivery failed", t);
                    try {
                        queue(appContext, fSender, body);
                    } catch (Throwable ignored) {
                    }
                } finally {
                    pending.finish();
                }
            }
        }, "spendwise-sms").start();
    }

    /** Cheap pre-filter so we don't forward OTPs / personal messages. */
    private static boolean looksFinancial(String body) {
        String b = body.toLowerCase();
        String[] keys = {
            "debited", "credited", "spent", "txn", "transaction", "a/c", "acct",
            "account", "upi", "inr", "rs.", "rs ", "₹", "paid", "received",
            "withdrawn", "balance", "purchase", "payment", "transfer", "imps",
            "neft", "deposited",
        };
        for (String k : keys) {
            if (b.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** POST to the running embedded server. Returns true on a 2xx response. */
    private static boolean postToServer(String sender, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(INGEST_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String form = "sender=" + URLEncoder.encode(sender == null ? "" : sender, "UTF-8")
                    + "&body=" + URLEncoder.encode(body, "UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(form.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Append to the offline queue drained on next app launch. */
    private static void queue(Context context, String sender, String body) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("sender", sender == null ? "" : sender);
        obj.put("body", body);
        File file = new File(context.getFilesDir(), INBOX_FILE);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file, true))) {
            w.write(obj.toString());
            w.newLine();
        }
        Log.d(TAG, "Queued finance SMS for next launch");
    }
}
