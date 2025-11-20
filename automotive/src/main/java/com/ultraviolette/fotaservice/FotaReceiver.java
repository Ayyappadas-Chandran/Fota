package com.ultraviolette.fotaservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class FotaReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            //Intent serviceIntent = new Intent(context, FotaService.class);
            //context.startForegroundService(serviceIntent);   // IMPORTANT for Android 8+
            Log.e("FotaService.FotaReceiver","ACTION_BOOT_COMPLETED");
        }
    }
}
