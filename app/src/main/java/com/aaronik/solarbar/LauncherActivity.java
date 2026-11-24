package com.aaronik.solarbar;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

// Keep the launcher entry separate from the dashboard so Android task restoration
// and notification taps cannot accidentally bypass or trigger rearming.
public final class LauncherActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ((SolarApp)getApplication()).arm();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
