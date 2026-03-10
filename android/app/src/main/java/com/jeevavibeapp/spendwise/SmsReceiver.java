package com.jeevavibeapp.spendwise;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SpendWiseSms";

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null) {
                    for (Object pdu : pdus) {
                        SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                        String body = smsMessage.getMessageBody();
                        String sender = smsMessage.getOriginatingAddress();
                        
                        Log.d(TAG, "SMS Received from: " + sender + " Body: " + body);
                        
                        // Pass this to our plugin listener if the app is running
                        SpendWisePlugin.onSmsReceived(sender, body);
                    }
                }
            }
        }
    }
}
