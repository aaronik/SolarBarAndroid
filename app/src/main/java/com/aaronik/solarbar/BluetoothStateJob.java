package com.aaronik.solarbar;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;

public final class BluetoothStateJob extends JobService {
    static final int ID = 17547;

    static void watch(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) == null) schedule(context, scheduler);
    }

    private static void schedule(Context context, JobScheduler scheduler) {
        JobInfo job = new JobInfo.Builder(ID, new ComponentName(context, BluetoothStateJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(Settings.Global.getUriFor(Settings.Global.BLUETOOTH_ON), 0))
                .setTriggerContentUpdateDelay(500)
                .setTriggerContentMaxDelay(1_000)
                .build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w("SolarBar", "Could not register Bluetooth state watcher");
    }

    private Runnable pending;

    private boolean waitingForRadio(SolarApp app) {
        return app.nearbyGranted() && !app.enabled()
                && Settings.Global.getInt(getContentResolver(), Settings.Global.BLUETOOTH_ON, 0) == 1;
    }

    @Override public boolean onStartJob(JobParameters params) {
        SolarApp app = (SolarApp) getApplication();
        app.bluetoothChanged(true);
        if (!waitingForRadio(app)) {
            // Content-triggered jobs are one-shot. Replace the consumed job.
            schedule(this, getSystemService(JobScheduler.class));
            return false;
        }
        // The setting can change before Android has brought the BLE scanner up.
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        pending = new Runnable() {
            @Override public void run() {
                if (waitingForRadio(app) && SystemClock.elapsedRealtime() < deadline) {
                    app.handler.postDelayed(this, 500);
                    return;
                }
                pending = null;
                app.bluetoothChanged(true);
                jobFinished(params, false);
                app.handler.post(() -> schedule(BluetoothStateJob.this, getSystemService(JobScheduler.class)));
            }
        };
        app.handler.postDelayed(pending, 500);
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        SolarApp app = (SolarApp) getApplication();
        if (pending != null) app.handler.removeCallbacks(pending);
        pending = null;
        return true;
    }
}
