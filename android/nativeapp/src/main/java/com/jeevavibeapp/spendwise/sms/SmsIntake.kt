package com.jeevavibeapp.spendwise.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsMessage
import android.util.Log
import com.jeevavibeapp.spendwise.data.AppGraph
import com.jeevavibeapp.spendwise.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Live capture and launch-time catch-up.
 *
 * The old version POSTed each message to a web server running inside the same
 * app, and when that server was not up — which was often, because the app was
 * closed — it wrote the message to a queue file to be replayed later. Two
 * moving parts and a durable queue, to move data between two objects in one
 * process. This writes to the database.
 */
class SmsIntakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val bundle = intent.extras ?: return

        @Suppress("DEPRECATION")
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        // Multipart messages arrive as several PDUs and a bank alert is
        // routinely longer than one, so the parts are joined before parsing.
        val body = StringBuilder()
        var sender: String? = null
        for (pdu in pdus) {
            val sms = try {
                if (format != null) SmsMessage.createFromPdu(pdu as ByteArray, format)
                else @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu as ByteArray)
            } catch (t: Throwable) { continue }   // malformed PDU on some OEM stacks
            sms?.messageBody?.let { body.append(it) }
            if (sender == null) sender = sms?.originatingAddress
        }
        if (body.isEmpty()) return

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppGraph.repo(app).ingestSms(sender, body.toString())
            } catch (t: Throwable) {
                Log.e("SpendWiseSms", "ingest failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Rescans the inbox on launch.
 *
 * A manifest receiver does not fire on MIUI, ColorOS or EMUI without
 * Autostart, and never fires at all while the process is dead — so without
 * this, auto-capture silently does nothing on a large share of Indian
 * phones.
 *
 * Rescanning is safe AND cheap: every message is offered as history rather
 * than as an arrival, so anything already banked or already held is
 * recognised by one indexed lookup and skipped before the expensive part —
 * merchant resolution retrains the categoriser over the whole ledger, and
 * paying that once per known message on every launch is a stall the user
 * would feel.
 */
class InboxScanner(private val context: Context, private val repo: Repo) {

    companion object {
        private const val MAX_MESSAGES = 500
        private const val FIRST_RUN_LOOKBACK_DAYS = 90L
    }

    suspend fun scan(): Int {
        val since = System.currentTimeMillis() -
            FIRST_RUN_LOOKBACK_DAYS * 24 * 60 * 60 * 1000
        var seen = 0
        var captured = 0
        try {
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                "date > ?", arrayOf(since.toString()), "date DESC",
            )?.use { cur ->
                val iAddr = cur.getColumnIndex("address")
                val iBody = cur.getColumnIndex("body")
                val iDate = cur.getColumnIndex("date")
                while (cur.moveToNext() && seen < MAX_MESSAGES) {
                    seen++
                    val body = if (iBody >= 0) cur.getString(iBody) else null
                    if (body.isNullOrEmpty()) continue
                    val addr = if (iAddr >= 0) cur.getString(iAddr) else null
                    // When the bank did not put a date in the message, the
                    // time it was delivered is the transaction's time — and,
                    // because the dedup key falls back to it, the only thing
                    // that keeps that key the same on the next rescan.
                    // Reading the clock instead would file the same purchase
                    // again every day the app was opened.
                    val at = if (iDate >= 0) cur.getLong(iDate) else 0L
                    val result = repo.ingestSms(
                        sender = addr,
                        body = body,
                        // History being walked past again, not a new arrival.
                        live = false,
                        receivedAt = if (at > 0) Repo.localTime(at) else LocalDateTime.now(),
                    )
                    if (result is Repo.Ingest.Captured) captured++
                }
            }
        } catch (t: Throwable) {
            // Missing READ_SMS, an OEM-restricted provider, or a malformed
            // row. A scan failure must never take the app down with it.
            Log.w("SpendWiseSms", "inbox scan skipped", t)
        }
        return captured
    }
}
