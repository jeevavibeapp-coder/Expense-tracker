package com.jeevavibeapp.spendwise;

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
        // Implementation for checking SMS permissions
        JSObject ret = new JSObject();
        ret.put("granted", true); // Simplified for now
        call.resolve(ret);
    }
}
