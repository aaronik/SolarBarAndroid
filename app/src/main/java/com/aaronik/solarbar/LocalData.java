package com.aaronik.solarbar;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class LocalData {
    static final long DAY = 86_400_000L, PERIOD = 10_000L;
    static final class Point {
        final long time;
        final Double[] values;
        Point(long time, Double[] values) { this.time = time; this.values = values; }
    }
    static final class History extends SQLiteOpenHelper {
        long lastBucket = Long.MIN_VALUE;
        long nextPrune;
        History(Context context) { super(context, "readings.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE samples (bucket INTEGER PRIMARY KEY, time INTEGER NOT NULL, m0 REAL, m1 REAL, m2 REAL, m3 REAL, m4 REAL, m5 REAL, m6 REAL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int old, int version) { throw new IllegalStateException("Missing history migration"); }
        void append(long time, Victron.Snapshot snapshot) {
            long bucket = time / PERIOD;
            if (bucket == lastBucket) return;
            SQLiteDatabase db = getWritableDatabase();
            ContentValues row = new ContentValues();
            row.put("bucket", bucket); row.put("time", time);
            for (int i = 0; i < snapshot.metrics.length; i++) row.put("m"+i, snapshot.metrics[i]);
            db.insertWithOnConflict("samples", null, row, SQLiteDatabase.CONFLICT_IGNORE);
            lastBucket = bucket;
            prune(time);
        }
        void prune(long now) {
            if (now >= nextPrune || now < nextPrune - 3_600_000L) {
                getWritableDatabase().delete("samples", "bucket < ?", new String[]{Long.toString((now-DAY)/PERIOD)});
                nextPrune = now + 3_600_000L;
            }
        }
        List<Point> load(long from, long now) {
            List<Point> out = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query("samples", null, "bucket >= ? AND bucket <= ? AND time >= ? AND time <= ?",
                    new String[]{Long.toString(from/PERIOD), Long.toString(now/PERIOD), Long.toString(from), Long.toString(now)}, null, null, "bucket ASC")) {
                while (cursor.moveToNext()) {
                    Double[] metrics = new Double[Victron.LABEL.length];
                    for (int i = 0; i < metrics.length; i++) if (!cursor.isNull(i+2)) metrics[i] = cursor.getDouble(i+2);
                    out.add(new Point(cursor.getLong(1), metrics));
                }
            }
            return out;
        }
    }
    static final class Keys {
        private static final String ALIAS = "solarbar-advertisement-key-encryption-v1";
        private final SharedPreferences prefs;
        Keys(Context context) { prefs = context.getSharedPreferences("encrypted-devices", Context.MODE_PRIVATE); }
        private static SecretKey master(boolean create) throws GeneralSecurityException, IOException {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
            if (!create) throw new GeneralSecurityException("Key unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        byte[] load(String id) throws GeneralSecurityException, IOException {
            String saved = prefs.getString(id, null);
            if (saved == null) return null;
            String[] parts = saved.split(":", -1);
            if (parts.length != 2) throw new GeneralSecurityException("Invalid saved key");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, master(false), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            cipher.updateAAD(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
        }
        void save(String id, byte[] key) throws GeneralSecurityException, IOException {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, master(true));
            cipher.updateAAD(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String data = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(key), Base64.NO_WRAP);
            if (!prefs.edit().putString(id, data).commit()) throw new IOException("Key could not be saved");
        }
        void forget(String id) throws IOException {
            if (!prefs.edit().remove(id).commit()) throw new IOException("Key could not be removed");
        }
    }
}
