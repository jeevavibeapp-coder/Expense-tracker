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
 *   2. POST {sender, body} to the on-device server (http://127.0.0.1:8765/sms/ingest)
 *      with the per-launch device token so only this app can ingest.
 *   3. If the server isn't up (app closed), append to a queue file that the
 *      app drains on its next launch, so nothing is ever lost.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SpendWiseSms";
    // The server no longer always sits on 8765 — it takes any free loopback
    // port when the preferred one is held, and records which. A receiver that
    // kept posting to a hard-coded 8765 would silently queue every message
    // instead of ingesting it, which looks exactly like auto-capture being
    // broken. The queue file means nothing is lost either way, but the
    // messages would not appear until something else drained them.
    private static final String PREFS = "spendwise";
    private static final String PREF_SERVER_PORT = "server_port";
    private static final int DEFAULT_PORT = 8765;

    private static String ingestUrl(Context context) {
        int port = DEFAULT_PORT;
        try {
            port = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(PREF_SERVER_PORT, DEFAULT_PORT);
        } catch (Throwable ignored) {
        }
        return "http://127.0.0.1:" + port + "/sms/ingest";
    }

    private static final String INBOX_FILE = "sms_inbox.jsonl";
    // Serializes appends to the queue file across the per-message worker threads
    // and the launch-time inbox scanner.
    static final Object QUEUE_LOCK = new Object();

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
        String format = bundle.getString("format");  // "3gpp" / "3gpp2" (CDMA)
        StringBuilder bodyBuilder = new StringBuilder();
        String sender = null;
        for (Object pdu : pdus) {
            SmsMessage sms;
            try {
                sms = format != null
                        ? SmsMessage.createFromPdu((byte[]) pdu, format)
                        : SmsMessage.createFromPdu((byte[]) pdu);
            } catch (Throwable t) {
                continue;  // malformed PDU on some OEM stacks — skip this part
            }
            if (sms == null) {
                continue;
            }
            String part = sms.getMessageBody();
            if (part != null) {
                bodyBuilder.append(part);
            }
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
        // Keystore-backed. If it is somehow unreadable the token comes back
        // null-safe and postToServer just gets a 401, which routes the message
        // to the durable queue instead of dropping it.
        String tok;
        try {
            tok = SecretVault.getOrCreate(appContext, SecretVault.DEVICE_TOKEN);
        } catch (Throwable t) {
            tok = null;
        }
        final String token = tok;
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!postToServer(appContext, fSender, body, token)) {
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

    /** Cheap pre-filter so we don't forward OTPs / personal messages.
     *  Shared with the launch-time inbox scanner. */
    static boolean looksFinancial(String body) {
        String b = body.toLowerCase();
        // Reject marketing/lending/scam messages up front. They carry amounts
        // and even transaction words, so without this they flood the queue
        // (a real device ended up with 134 junk "transactions"). The Python
        // parser is still the authoritative gate; this keeps the volume down.
        String[] junk = {
            "credit score", "cibil", "pre-approved", "preapproved", "loan offer",
            "personal loan", "instant loan", "apply now", "click here", "bit.ly",
            "tinyurl", "congratulations", "you have won", "claim now", "lucky draw",
            "t&c apply", "unsubscribe", "verify kyc", "kyc update", "will be blocked",
            "cashback", "discount", "coupon", "% off", "offer", "recharge",
            "amount due", "bill generated", "statement generated", "emi of",
            "otp", "one time password", "low interest", "no documents",
        };
        for (String j : junk) {
            if (b.contains(j)) {
                return false;
            }
        }
        // NOTE: no bare "rs" — it substring-matches ordinary words ("offers",
        // "hours"). No bare "credit"/"debit" either: those match "credit score"
        // and "debit card blocked". Only inflected transaction verbs count.
        String[] keys = {
            "debited", "credited", "spent", "txn", "transaction", "a/c", "acct",
            "upi", "inr", "rs.", "rs ", "₹", "paid", "received",
            "withdrawn", "purchase", "payment", "transfer", "imps",
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
    private static boolean postToServer(Context context, String sender, String body,
                                        String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ingestUrl(context));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("X-SpendWise-Token", token);
            }
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

    /** Append to the offline queue drained on next app launch (serialized). */
    private static void queue(Context context, String sender, String body) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("sender", sender == null ? "" : sender);
        obj.put("body", body);
        String line = obj.toString() + "\n";
        File file = new File(context.getFilesDir(), INBOX_FILE);
        synchronized (QUEUE_LOCK) {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(file, true))) {
                w.write(line);
            }
        }
        Log.d(TAG, "Queued finance SMS for next launch");
    }
}
