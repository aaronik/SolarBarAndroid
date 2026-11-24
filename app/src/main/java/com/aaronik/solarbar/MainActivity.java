package com.aaronik.solarbar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int BG = 0xff101619, CARD = 0xff1b2428, TEXT = 0xffeef6f4, MUTED = 0xffadbfbd;
    private static final int[] COLORS = {0xff82edc3, 0xff85cbff, 0xffa9dfc4, 0xffffc674, 0xffd5b1ff, 0xffd5b1ff, 0xffd5b1ff};
    private static final long[] WINDOWS = {3_600_000L, 21_600_000L, LocalData.DAY};
    private static final String[] WINDOW_NAMES = {"1 hour", "6 hours", "24 hours"};
    private static final int[] ORDER = {Victron.SOC, Victron.BATTERY, Victron.SOLAR, Victron.AC, Victron.VA, Victron.VOLTAGE, Victron.VAC};
    private SolarApp app;
    private LinearLayout root, deviceRows, charts;
    private Button nearby, notification;
    private TextView status, age, help;
    private final Chart[] plots = new Chart[Victron.LABEL.length];
    private final TextView[] values = new TextView[Victron.LABEL.length];
    private final Runnable observer = this::renderSoon;
    private final Runnable renderer = this::render;
    private int window = 1;
    private long nextRender, lastHistoryBucket = Long.MIN_VALUE;
    private boolean deviceListChanged = true;
    private String deviceSignature = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        app = (SolarApp)getApplication();
        if (saved != null) window = saved.getInt("window", 1);
        getWindow().setDecorFitsSystemWindows(false);
        makeLayout();
    }
    @Override public void onResume() { super.onResume(); app.observe(observer); }
    @Override public void onPause() { app.unobserve(observer); app.handler.removeCallbacks(renderer); super.onPause(); }
    @Override public void onSaveInstanceState(Bundle out) { out.putInt("window", window); super.onSaveInstanceState(out); }
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if ((request == 1 && !app.nearbyGranted()) || (request == 2 && !app.notificationsAllowed())) {
            if (names.length > 0 && !shouldShowRequestPermissionRationale(names[0])) appSettings();
        }
        app.observe(observer);
    }
    private void appSettings() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private void notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }
    private int dp(float dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }
    private static void update(TextView view, String text) { if (!text.contentEquals(view.getText())) view.setText(text); }
    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column();
        c.setBackground(background(CARD, 14)); c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8); parent.addView(c, p);
        return c;
    }
    private void heading(LinearLayout parent, String title) {
        TextView label = text(title, 18, TEXT); label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, dp(17), 0, dp(8)); parent.addView(label);
    }
    private Button button(String title, View.OnClickListener click) {
        Button button = new Button(this); button.setAllCaps(false); button.setText(title); button.setOnClickListener(click); return button;
    }
    private void makeLayout() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        root = column(); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(dp(17) + bars.left, dp(14) + bars.top, dp(17) + bars.right, dp(16) + bars.bottom);
            return insets;
        });
        setContentView(scroll);
        TextView mark = text("SOLARBAR  /  VICTRON", 13, COLORS[0]); mark.setLetterSpacing(.14f); root.addView(mark);
        TextView title = text("Power monitor", 30, TEXT); title.setTypeface(null, Typeface.BOLD); root.addView(title);
        status = text("", 15, MUTED); status.setPadding(0, dp(5), 0, 0); root.addView(status);
        age = text("", 13, MUTED); age.setPadding(0, 0, 0, dp(5)); root.addView(age);
        nearby = button("Allow Nearby devices", v -> {
            if (!app.nearbyGranted()) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
            else startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        });
        root.addView(nearby);
        notification = button("Allow live notification", v -> { if (!app.notificationsAllowed()) notificationSettings(); else app.arm(); });
        root.addView(notification);
        heading(root, "Current & recent history");
        Button range = button("History: " + WINDOW_NAMES[window], v -> new AlertDialog.Builder(this).setTitle("History range")
                .setSingleChoiceItems(WINDOW_NAMES, window, (dialog, which) -> {
                    window = which; ((Button)v).setText("History: " + WINDOW_NAMES[which]);
                    lastHistoryBucket = Long.MIN_VALUE; render(); dialog.dismiss();
                }).setNegativeButton("Cancel", null).show());
        root.addView(range);
        charts = column(); root.addView(charts);
        help = text("No recorded readings yet. Add a device’s advertisement key below to start.", 15, MUTED);
        card(charts).addView(help);
        for (int i : ORDER) {
            LinearLayout c = card(charts);
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); c.addView(row);
            row.addView(text(Victron.LABEL[i], 15, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            values[i] = text("—", 19, COLORS[i]); row.addView(values[i]);
            plots[i] = new Chart(i); c.addView(plots[i], new LinearLayout.LayoutParams(-1, dp(52)));
        }
        TextView caption = text("Time runs left to right, ending now. A gap means no readings were collected.", 13, MUTED); root.addView(caption);
        heading(root, "Victron devices");
        TextView setup = text("In VictronConnect, open your device → Settings → ⋯ → Product info → Instant Readout Details. Enable Instant Readout if needed and copy the Advertisement Key (not the PIN). Disconnect VictronConnect if the device is not discovered.", 14, MUTED);
        setup.setPadding(0, 0, 0, dp(10)); root.addView(setup);
        deviceRows = column(); root.addView(deviceRows);
    }
    private void renderSoon() {
        if (app.handler.hasCallbacks(renderer)) return;
        app.handler.postDelayed(renderer, Math.max(0, nextRender - SystemClock.uptimeMillis()));
    }
    private void render() {
        nextRender = SystemClock.uptimeMillis() + 700;
        Victron.Snapshot s = app.snapshot();
        update(status, app.status());
        age.setVisibility(s.hasData() ? View.VISIBLE : View.GONE);
        if (s.hasData()) update(age, "Latest broadcast " + Math.max(0, (SystemClock.elapsedRealtime() - s.latest)/1000) + "s ago");
        nearby.setVisibility(app.enabled() ? View.GONE : View.VISIBLE);
        update(nearby, app.nearbyGranted() ? "Bluetooth settings" : "Allow Nearby devices");
        notification.setVisibility(!app.armed() || !app.notificationsAllowed() ? View.VISIBLE : View.GONE);
        update(notification, !app.notificationsAllowed() ? "Allow live notification" : "Restore live notification");
        long now = System.currentTimeMillis(), bucket = now / LocalData.PERIOD;
        if (lastHistoryBucket != bucket || lastHistoryBucket == Long.MIN_VALUE || (s.hasData() && !hadLive)) {
            long from = now - WINDOWS[window];
            app.history.prune(now);
            List<LocalData.Point> points = app.history.load(from, now);
            for (Chart chart : plots) chart.load(points, from, now);
            lastHistoryBucket = bucket;
        }
        hadLive = s.hasData();
        boolean any = false;
        for (int i : ORDER) {
            boolean show = s.metrics[i] != null || plots[i].hasPoints;
            any |= show;
            View tile = (View)plots[i].getParent();
            tile.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) update(values[i], s.metrics[i] == null ? "—" : SolarApp.format(i, s.metrics[i]));
        }
        ((View)help.getParent()).setVisibility(any ? View.GONE : View.VISIBLE);
        renderDevices();
    }
    private boolean hadLive;
    private void renderDevices() {
        List<Victron.Device> devices = app.nearby();
        StringBuilder signature = new StringBuilder();
        long now = SystemClock.elapsedRealtime();
        for (Victron.Device d : devices) signature.append(d.id).append('\n').append(d.name).append('\n').append(deviceStatus(d, now)).append('\n');
        if (!deviceListChanged && deviceSignature.equals(signature.toString())) return;
        deviceListChanged = false;
        deviceSignature = signature.toString();
        deviceRows.removeAllViews();
        if (devices.isEmpty()) card(deviceRows).addView(text("No Victron Instant Readout devices found yet.", 14, MUTED));
        for (Victron.Device d : devices) {
            LinearLayout row = card(deviceRows);
            row.addView(text(d.name + "  ·  " + d.id.substring(Math.max(0, d.id.length()-5)), 16, TEXT));
            row.addView(text(deviceStatus(d, now), 14, MUTED));
            if (d.frame.supported()) row.addView(button(d.key == null ? "Add key" : "Manage key", v -> editKey(d)));
        }
    }
    private String deviceStatus(Victron.Device d, long now) {
        if (!app.enabled() || d.seen <= now - Victron.FRESH_MS) return d.frame.title() + " · Not seen recently";
        if (d.values != null && !d.live(now)) return d.frame.title() + " · Waiting for a fresh reading";
        return d.frame.title() + " · " + d.status;
    }
    private void editKey(Victron.Device d) {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint("32-character advertisement key");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (d.key != null) {
            StringBuilder hex = new StringBuilder(32);
            for (byte b : d.key) hex.append(Character.forDigit((b & 0xff) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            input.setText(hex);
            input.setSelection(input.length());
        }
        CheckBox reveal = new CheckBox(this);
        reveal.setText("Show key");
        reveal.setOnCheckedChangeListener((button, show) -> {
            int start = input.getSelectionStart(), end = input.getSelectionEnd();
            input.setTransformationMethod(show ? null : PasswordTransformationMethod.getInstance());
            if (start >= 0 && end >= 0) input.setSelection(start, end);
        });
        TextView count = text("", 13, MUTED);
        Runnable updateCount = () -> {
            String entered = input.getText().toString().trim();
            count.setText(entered.length() + " / 32 characters" + (entered.matches("[0-9a-fA-F]*") ? "" : " · Use only 0–9 and A–F"));
        };
        updateCount.run();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateCount.run(); }
            @Override public void afterTextChanged(Editable editable) { }
        });
        LinearLayout wrap = column(); wrap.setPadding(dp(20), dp(5), dp(20), 0);
        wrap.addView(input); wrap.addView(count); wrap.addView(reveal);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(d.name).setView(wrap)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null);
        if (d.key != null) b.setNeutralButton("Forget key", (dialog, which) -> {
            try { app.forget(d); }
            catch (IOException e) { new AlertDialog.Builder(this).setMessage("Could not remove the saved key.").setPositiveButton("OK", null).show(); }
        });
        AlertDialog alert = b.create();
        alert.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        alert.show();
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { app.save(d, input.getText().toString()); alert.dismiss(); }
            catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
            catch (GeneralSecurityException | IOException e) { input.setError("Could not save the key securely."); }
        });
    }
    private final class Chart extends View {
        final int metric;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        List<LocalData.Point> points = Collections.emptyList();
        boolean hasPoints;
        long from, to;
        double low, high;
        Chart(int metric) { super(MainActivity.this); this.metric = metric; }
        void load(List<LocalData.Point> points, long from, long to) {
            this.points = points; this.from = from; this.to = to;
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (LocalData.Point p : points) if (p.values[metric] != null) { lo = Math.min(lo, p.values[metric]); hi = Math.max(hi, p.values[metric]); }
            hasPoints = lo <= hi;
            if (metric == Victron.SOC) { low = 0; high = 100; }
            else if (hasPoints) {
                if (metric == Victron.BATTERY || metric == Victron.AC) { lo = Math.min(lo, 0); hi = Math.max(hi, 0); }
                double pad = Math.max(0.03 * Math.max(Math.abs(lo), Math.abs(hi)), Victron.UNIT[metric].equals("V") ? .05 : 1);
                low = lo - pad; high = hi + pad;
            }
            if (hasPoints) setContentDescription(Victron.LABEL[metric] + " trend over the last " + WINDOW_NAMES[window]);
            invalidate();
        }
        @Override public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!hasPoints) return;
            float top = dp(6), h = Math.max(1, getHeight() - dp(12)), left = dp(2), w = Math.max(1, getWidth() - dp(4));
            paint.setColor(0xff354449); paint.setStrokeWidth(dp(1));
            float guide = low < 0 && high > 0 ? top + (float)(high / (high - low)) * h : top + h;
            canvas.drawLine(left, guide, left + w, guide, paint);
            paint.setColor(COLORS[metric]); paint.setStrokeWidth(dp(2)); paint.setStrokeCap(Paint.Cap.ROUND);
            float lastX = 0, lastY = 0; long last = 0;
            for (LocalData.Point p : points) {
                Double value = p.values[metric];
                if (value == null) { last = 0; continue; }
                float x = left + ((float)(p.time - from) / (to - from)) * w;
                float y = top + (float)((high - value) / (high - low)) * h;
                if (last != 0 && p.time - last <= 25_000) canvas.drawLine(lastX, lastY, x, y, paint);
                else canvas.drawPoint(x, y, paint);
                last = p.time; lastX = x; lastY = y;
            }
        }
    }
}
