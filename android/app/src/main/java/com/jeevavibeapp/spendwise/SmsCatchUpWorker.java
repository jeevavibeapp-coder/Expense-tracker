package com.jeevavibeapp.spendwise;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Periodic safety net for SMS capture.
 *
 * <p>Auto-capture currently depends on two things happening: the SMS_RECEIVED
 * broadcast reaching {@link SmsReceiver}, and the user opening the app so
 * {@link SmsInboxScanner} can sweep the inbox. Both fail in ways the user
 * never sees:
 *
 * <ul>
 *   <li>MIUI, ColorOS, EMUI and OneUI aggressively deny background broadcasts
 *       to apps not on the OEM's "Autostart" allowlist, so the receiver is
 *       simply never invoked.</li>
 *   <li>If the process is killed while messages arrive, the queue file is
 *       written but never drained until the next launch.</li>
 *   <li>A user who does not open the app for a week loses nothing
 *       permanently, but their ledger silently stops being current — which
 *       looks exactly like the app being broken.</li>
 * </ul>
 *
 * <p>WorkManager survives process death, reboot and Doze (it defers rather
 * than drops), and is the only scheduling primitive on Android that OEM
 * battery managers are contractually required to honour. This worker runs the
 * same inbox scan the app runs at launch, so it needs no new logic and no new
 * failure modes — dedup at the ingest layer makes re-scanning free.
 *
 * <p><b>Battery:</b> the period is 6 hours with a battery-not-low constraint.
 * A scan queries the SMS content provider for messages newer than the last
 * watermark (typically zero rows) and exits, so the cost is a few
 * milliseconds of CPU per run. It deliberately does NOT request a network,
 * wake lock, or foreground service.
 */
public class SmsCatchUpWorker extends Worker {

    private static final String TAG = "SpendWiseWork";
    private static final String WORK_NAME = "spendwise-sms-catchup";
    /** Long enough to be invisible on battery, short enough that a user who
     *  opens the app once a day never sees a stale ledger. WorkManager's
     *  minimum period is 15 minutes; 6 hours is well clear of any OEM
     *  throttling threshold. */
    private static final long PERIOD_HOURS = 6;

    public SmsCatchUpWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Register (or re-register) the periodic sweep. Idempotent. */
    static void schedule(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    SmsCatchUpWorker.class, PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build();
            // KEEP, not REPLACE: REPLACE would reset the period on every app
            // launch, so a user who opens the app often would never actually
            // reach a scheduled run.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Throwable t) {
            // Never let a scheduling failure break app startup — the launch
            // scan still works, this is only the fallback.
            Log.w(TAG, "Could not schedule SMS catch-up work", t);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            // Nothing to do and nothing to retry — the user has not granted
            // access. Returning success avoids WorkManager's backoff churn.
            return Result.success();
        }
        try {
            int queued = SmsInboxScanner.scan(context);
            if (queued > 0) {
                Log.i(TAG, "Background catch-up queued " + queued + " message(s)");
            }
            // Deliberately NOT starting the Python server here. Booting a
            // whole interpreter from a background worker would cost far more
            // battery than it saves, and the queue file is durable — the next
            // launch drains it. The worker's job is to make sure the messages
            // are CAPTURED, not to process them immediately.
            return Result.success();
        } catch (Throwable t) {
            Log.w(TAG, "Background catch-up failed", t);
            // Retry with WorkManager's exponential backoff: a transient
            // content-provider failure should not skip a whole 6-hour cycle.
            return Result.retry();
        }
    }
}
