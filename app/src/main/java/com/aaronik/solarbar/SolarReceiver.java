package com.aaronik.solarbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

public final class SolarReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SolarApp app = (SolarApp) context.getApplicationContext();
        if (SolarApp.DISMISS.equals(intent.getAction())) {
            if (Victron.userDismissal(intent.getLongExtra(SolarApp.EXPIRES, 0), SystemClock.elapsedRealtime())) app.dismiss();
        } else if (SolarApp.SCAN.equals(intent.getAction())) app.scan(intent);
    }
}
