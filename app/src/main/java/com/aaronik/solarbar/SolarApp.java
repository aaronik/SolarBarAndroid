package com.aaronik.solarbar;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SolarApp extends Application {
    static final String SCAN = "com.aaronik.solarbar.SCAN", DISMISS = "com.aaronik.solarbar.DISMISS";
    static final String EXPIRES = "expires_elapsed_realtime";
    private static final String CHANNEL = "live_readings";
    private static final int NOTICE_ID = 10;
    final Handler handler = new Handler(Looper.getMainLooper());
    final Map<String, Victron.Device> devices = new LinkedHashMap<>();
    LocalData.History history;
    private LocalData.Keys keys;
    private NotificationManager notices;
    private SharedPreferences settings;
    private BluetoothAdapter adapter;
    private boolean scanRequested, published, armed;
    private long nextStart, nextPost;
    private int scanError;
    private Runnable observer;
    private final Runnable tick = () -> refresh(false);
    private final Runnable retry = this::ensureScan;

    @Override public void onCreate() {
        super.onCreate();
        keys = new LocalData.Keys(this);
        history = new LocalData.History(this);
        settings = getSharedPreferences("monitor", MODE_PRIVATE);
        armed = settings.getBoolean("armed", false);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        notices = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Live Victron readings", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        notices.createNotificationChannel(channel);
        // The Bluetooth stack may be in a separate privileged process; this is a protected broadcast.
        BroadcastReceiver bluetoothChanges = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (enabled()) { ensureScan(); refresh(true); }
                else { stopScan(); refresh(true); }
            }
        };
        IntentFilter state = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothChanges, state, Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetoothChanges, state);
    }

    boolean nearbyGranted() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    boolean enabled() { return nearbyGranted() && adapter != null && adapter.isEnabled(); }
    boolean notificationsAllowed() {
        return notices.areNotificationsEnabled() && notices.getNotificationChannel(CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    boolean armed() { return armed; }
    String status() {
        if (adapter == null) return "Bluetooth LE is unavailable on this phone.";
        if (!nearbyGranted()) return "Allow Nearby devices to find your Victron equipment.";
        if (!enabled()) return "Bluetooth is off. No live notification will be shown.";
        if (scanError != 0) return "Android paused Bluetooth scanning. Retrying…";
        if (snapshot().hasData()) return "Receiving live Victron readings";
        return "Listening for Victron Instant Readout nearby…";
    }
    void observe(Runnable render) { observer = render; ensureScan(); refresh(true); }
    void unobserve(Runnable render) { if (observer == render) observer = null; }
    void arm() { armed = true; settings.edit().putBoolean("armed", true).apply(); ensureScan(); refresh(true); }
    void dismiss() { armed = false; settings.edit().putBoolean("armed", false).apply(); refresh(true); }
    Victron.Snapshot snapshot() { return Victron.summarize(enabled() ? devices.values() : Collections.emptyList(), SystemClock.elapsedRealtime()); }
    List<Victron.Device> nearby() {
        List<Victron.Device> result = new ArrayList<>();
        long cutoff = SystemClock.elapsedRealtime() - 120_000;
        for (Victron.Device d : devices.values()) if (d.seen >= cutoff || d.key != null) result.add(d);
        result.sort(Comparator.comparing(d -> d.name + d.id));
        return result;
    }
    void save(Victron.Device d, String text) throws GeneralSecurityException, IOException {
        byte[] key = Victron.key(text);
        if (key == null) throw new IllegalArgumentException("Enter the 32-character hexadecimal advertisement key.");
        if (d.seen > SystemClock.elapsedRealtime() - 120_000 && d.frame.hint != (key[0] & 255))
            throw new IllegalArgumentException("That key does not match this device’s broadcast.");
        keys.save(d.id, key);
        d.key = key;
        decode(d);
        record();
        refresh(true);
    }
    void forget(Victron.Device d) throws IOException {
        keys.forget(d.id);
        d.key = null;
        decode(d);
        refresh(true);
    }

    private PendingIntent callback(int flags) {
        return PendingIntent.getBroadcast(this, 1, new Intent(this, SolarReceiver.class).setAction(SCAN), flags | PendingIntent.FLAG_MUTABLE);
    }
    void ensureScan() {
        if (!enabled()) { stopScan(); return; }
        if (scanRequested) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextStart) { handler.removeCallbacks(retry); handler.postDelayed(retry, nextStart - now); return; }
        try {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { failed(-1); return; }
            ScanFilter filter = new ScanFilter.Builder().setManufacturerData(Victron.COMPANY, new byte[]{0x10}).build();
            ScanSettings options = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build();
            int error = scanner.startScan(Collections.singletonList(filter), options, callback(PendingIntent.FLAG_UPDATE_CURRENT));
            if (error != 0 && error != ScanCallback.SCAN_FAILED_ALREADY_STARTED) { failed(error); return; }
            scanRequested = true;
            // If Android later delivers an async scan error, the receiver backs off before restarting.
            scanError = 0;
        } catch (IllegalStateException | SecurityException e) { failed(-1); }
    }
    private void stopScan() {
        scanRequested = false;
        scanError = 0;
        nextStart = 0;
        handler.removeCallbacks(retry);
        PendingIntent callback = callback(PendingIntent.FLAG_NO_CREATE);
        if (callback != null) {
            try {
                if (adapter != null && nearbyGranted()) {
                    BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                    if (scanner != null) scanner.stopScan(callback);
                }
            } catch (IllegalStateException | SecurityException ignored) { }
            callback.cancel();
        }
        for (Victron.Device d : devices.values()) { d.values = null; d.packetAt = Long.MIN_VALUE; }
    }
    private void failed(int error) {
        scanError = error;
        scanRequested = false;
        nextStart = SystemClock.elapsedRealtime() + 35_000;
        handler.removeCallbacks(retry);
        if (enabled()) handler.postDelayed(retry, 35_000);
        refresh(false);
    }
    void scan(Intent intent) {
        if (!enabled()) { stopScan(); refresh(true); return; }
        int error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0);
        if (error != 0 && error != ScanCallback.SCAN_FAILED_ALREADY_STARTED) { failed(error); return; }
        List<ScanResult> results;
        if (Build.VERSION.SDK_INT >= 33) results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult.class);
        else results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        if (results == null || results.isEmpty()) return;
        scanRequested = true;
        scanError = 0;
        boolean changed = false;
        for (ScanResult result : results) changed |= accept(result);
        if (changed) record();
        refresh(false);
    }
    private boolean accept(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return false;
        Victron.Frame frame = Victron.parse(record.getManufacturerSpecificData(Victron.COMPANY));
        if (frame == null) return false;
        long now = SystemClock.elapsedRealtime(), seen = result.getTimestampNanos() / 1_000_000L;
        if (seen > now || seen < now - 120_000) return false;
        String id = result.getDevice().getAddress();
        Victron.Device d = devices.get(id);
        if (d == null) {
            if (devices.size() >= 100) {
                Victron.Device oldest = Collections.min(devices.values(), Comparator.comparingLong(v -> v.seen));
                devices.remove(oldest.id);
            }
            d = new Victron.Device(id);
            try { d.key = keys.load(id); }
            catch (GeneralSecurityException | IOException | IllegalArgumentException e) { d.status = "Saved key unavailable — add it again"; }
            devices.put(id, d);
        }
        String name = record.getDeviceName();
        if (name != null && !name.trim().isEmpty()) d.name = name;
        if (d.name == null) d.name = frame.title();
        d.seen = Math.max(d.seen, seen);
        if (d.frame != null && (seen < d.packetAt || (d.packetAt != Long.MIN_VALUE && Arrays.equals(d.frame.bytes, frame.bytes)))) return false;
        d.frame = frame;
        d.packetAt = seen;
        decode(d);
        return d.live(now);
    }
    private void decode(Victron.Device d) {
        d.values = null;
        if (!d.frame.supported()) { d.status = "Instant Readout format not supported"; return; }
        if (d.key == null) {
            if (!d.status.startsWith("Saved key unavailable")) d.status = "Add advertisement key";
            return;
        }
        if ((d.key[0] & 255) != d.frame.hint) { d.status = "Advertisement key doesn’t match"; return; }
        try {
            d.values = d.frame.decode(d.key);
            if (d.values != null && !d.values.hasData()) d.values = null;
            d.status = d.values == null ? "No measurements in this broadcast" : "Receiving readings";
        } catch (GeneralSecurityException e) { d.status = "Could not decode broadcast"; }
    }
    private void record() {
        Victron.Snapshot s = snapshot();
        if (s.hasData()) history.append(System.currentTimeMillis(), s);
    }
    private void refresh(boolean force) {
        handler.removeCallbacks(tick);
        long now = SystemClock.elapsedRealtime();
        Victron.Snapshot s = snapshot();
        if (s.visible(enabled(), armed, now) && notificationsAllowed()) {
            if (force || !published || now >= nextPost) {
                post(s, now);
                nextPost = now + 4_000;
                published = true;
            }
            handler.postDelayed(tick, Math.max(10, Math.min(s.expires, nextPost) - now));
        } else {
            notices.cancel(NOTICE_ID);
            published = false;
            if (observer != null) handler.postDelayed(tick, 1500);
        }
        if (observer != null) observer.run();
    }
    private void post(Victron.Snapshot s, long now) {
        List<String> compact = new ArrayList<>();
        long wall = System.currentTimeMillis();
        List<LocalData.Point> points = history.load(wall - NotificationCharts.WINDOW, wall);
        int[] order = {Victron.SOC, Victron.SOLAR, Victron.AC, Victron.VA, Victron.BATTERY, Victron.VOLTAGE, Victron.VAC};
        for (int i : order) {
            Double value = s.metrics[i];
            if (value == null) continue;
            if (compact.size() < 3 && (i != Victron.VOLTAGE || s.metrics[Victron.SOC] == null)) {
                compact.add(NotificationCharts.SHORT_NAMES[i] + " " + format(i, value));
            }
        }
        RemoteViews collapsed = NotificationCharts.compact(this, s, points, wall);
        RemoteViews chart = NotificationCharts.render(this, s, points, wall);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class).setAction("com.aaronik.solarbar.OPEN")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        // Android may send deleteIntent for system timeouts as well as user swipes.
        // Bind the original deadline to this particular notification update.
        Intent removed = new Intent(this, SolarReceiver.class).setAction(DISMISS)
                .setIdentifier(Long.toString(s.expires)).putExtra(EXPIRES, s.expires);
        PendingIntent delete = PendingIntent.getBroadcast(this, 3, removed, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
        Notification.Builder notice = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle("Victron · Live").setContentText(String.join("  ·  ", compact))
                .setContentIntent(open).setDeleteIntent(delete)
                .setOnlyAlertOnce(true).setOngoing(false).setAutoCancel(false).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(Math.max(1, s.expires - now));
        notice.setStyle(new Notification.DecoratedCustomViewStyle()).setCustomContentView(collapsed);
        if (chart != null) notice.setCustomBigContentView(chart);
        notices.notify(NOTICE_ID, notice.build());
    }
    static String format(int metric, double value) {
        if (metric == Victron.SOC) return String.format(Locale.getDefault(), "%.0f%%", value);
        String unit = Victron.UNIT[metric];
        if ((unit.equals("W") || unit.equals("VA")) && Math.abs(value) >= 1000) return String.format(Locale.getDefault(), "%.2f k%s", value/1000, unit);
        return String.format(Locale.getDefault(), unit.equals("V") ? "%.2f %s" : "%.0f %s", value, unit);
    }
}
