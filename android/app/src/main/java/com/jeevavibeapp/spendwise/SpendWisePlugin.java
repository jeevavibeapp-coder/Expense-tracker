package com.jeevavibeapp.spendwise;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "SpendWise")
public class SpendWisePlugin extends Plugin {

    private static SpendWisePlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    @Override
    protected void handleOnDestroy() {
        // Clear the static ref so a destroyed activity's plugin (and its
        // bridge/WebView) can be garbage-collected across recreations.
        if (instance == this) {
            instance = null;
        }
        super.handleOnDestroy();
    }

    public static void onSmsReceived(String sender, String body) {
        if (instance != null) {
            JSObject ret = new JSObject();
            ret.put("sender", sender);
            ret.put("body", body);
            instance.notifyListeners("onSMSReceived", ret);
        }
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        boolean granted = ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }
}
