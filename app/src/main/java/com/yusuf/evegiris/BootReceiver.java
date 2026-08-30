package com.yusuf.evegiris;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.serviceEnabled(context)) return;
        Intent s = new Intent(context, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(s);
            else context.startService(s);
        } catch (Exception e) {
            Prefs.status(context, "Boot servis baslatma hatasi: " + e.getMessage());
        }
    }
}
