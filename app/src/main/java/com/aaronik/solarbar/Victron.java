package com.aaronik.solarbar;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Victron {
    static final int COMPANY = 0x02e1;
    static final long FRESH_MS = 25_000;
    static final String[] LABEL = {"Battery charge", "Battery power", "Battery voltage", "Solar", "AC output", "AC apparent", "AC voltage"};
    static final String[] UNIT = {"%", "W", "V", "W", "W", "VA", "V"};
    static final int SOC = 0, BATTERY = 1, VOLTAGE = 2, SOLAR = 3, AC = 4, VA = 5, VAC = 6;

    static final class Frame {
        final byte[] bytes;
        final int kind, hint, model;
        Frame(byte[] bytes) {
            this.bytes = bytes.clone();
            model = (bytes[2] & 255) | ((bytes[3] & 255) << 8);
            kind = model == 0xa3a4 || model == 0xa3a5 ? 254 : bytes[4] & 255;
            hint = bytes[7] & 255;
        }
        boolean supported() { return (kind >= 1 && kind <= 13 && kind != 7) || kind == 15 || kind == 254; }
        Values decode(byte[] key) throws GeneralSecurityException {
            if (!supported() || key == null || key.length != 16 || (key[0] & 255) != hint) return null;
            byte[] iv = new byte[16];
            iv[0] = bytes[5]; iv[1] = bytes[6];
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return fields(kind, cipher.doFinal(bytes, 8, bytes.length - 8));
        }
        String title() {
            switch (kind) {
                case 1: return "Solar charger";
                case 2: return "Battery monitor";
                case 3: return "Inverter";
                case 4: return "DC–DC charger";
                case 5: return "Lithium battery";
                case 6: return "Inverter RS";
                case 7: return "GX device";
                case 8: return "AC charger";
                case 9: return "BatteryProtect";
                case 10: return "Lynx Smart BMS";
                case 11: return "Multi RS";
                case 12: return "VE.Bus inverter/charger";
                case 13: return "DC energy meter";
                case 15: return "Orion XS";
                case 254: return "Smart Battery Sense";
                default: return String.format("Victron model %04X", model);
            }
        }
    }
    // Android strips the two manufacturer-ID bytes before returning manufacturer data.
    static Frame parse(byte[] bytes) { return bytes != null && bytes.length >= 9 && bytes[0] == 0x10 ? new Frame(bytes) : null; }
    static byte[] key(String text) {
        String hex = text.trim();
        if (!hex.matches("[0-9a-fA-F]{32}")) return null;
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte)Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        return key;
    }
    static final class Values {
        Double soc, voltage, current, solar, yield, ac, va, vac, input;
        Double power() { return voltage == null || current == null ? null : voltage * current; }
        boolean hasData() { return Arrays.asList(soc, voltage, current, solar, ac, va, vac, input).stream().anyMatch(v -> v != null); }
    }
    private static final class Bits {
        final byte[] bytes;
        Bits(byte[] bytes) { this.bytes = bytes; }
        Double n(int bit, int width, int na, boolean signed, double scale) {
            if (bit + width > bytes.length * 8) return null;
            int n = 0;
            for (int i = 0; i < width; i++) n |= ((bytes[(bit+i)/8] >> ((bit+i)%8)) & 1) << i;
            if (n == na) return null;
            if (signed && (n & (1 << (width-1))) != 0) n -= 1 << width;
            return n / scale;
        }
        Double u(int bit, int width, double scale) { return n(bit, width, (1 << width)-1, false, scale); }
        Double s(int bit, double scale) { return n(bit, 16, 0x7fff, true, scale); }
    }
    static Values fields(int kind, byte[] bytes) {
        Bits b = new Bits(bytes);
        Values v = new Values();
        switch (kind) {
            case 1: case 6:
                v.voltage = b.s(16, 100); v.current = b.s(32, 10);
                v.solar = b.u(kind == 1 ? 64 : 48, 16, 1);
                v.yield = b.u(kind == 1 ? 48 : 64, 16, 100);
                if (kind == 6) v.ac = b.s(80, 1);
                break;
            case 2: case 13:
                v.voltage = b.s(16, 100); v.current = b.n(66, 22, 0x3fffff, true, 1000);
                if (kind == 2) v.soc = b.u(108, 10, 10);
                break;
            case 10:
                v.voltage = b.s(24, 100); v.current = b.s(40, 10); v.soc = b.u(90, 10, 10);
                break;
            case 11: case 12:
                v.current = b.s(16, 10); v.voltage = b.u(32, 14, 100);
                if (kind == 11) {
                    v.ac = b.s(64, 1); v.solar = b.u(80, 16, 1); v.yield = b.u(96, 16, 100);
                } else { v.ac = b.n(67, 19, 0x3ffff, true, 1); v.soc = b.u(95, 7, 1); }
                break;
            case 3:
                v.voltage = b.s(24, 100); v.va = b.u(40, 16, 1); v.vac = b.u(56, 15, 100);
                break;
            case 4: v.input = b.u(16, 16, 100); v.voltage = b.s(32, 100); break;
            case 15: v.voltage = b.u(16, 16, 100); v.current = b.u(32, 16, 10); v.input = b.u(48, 16, 100); break;
            case 8: v.voltage = b.u(16, 13, 100); v.current = b.u(29, 11, 10); break;
            case 5: v.voltage = b.u(104, 12, 100); break;
            case 9: v.input = b.s(56, 100); v.voltage = b.u(72, 16, 100); break;
            case 254: v.voltage = b.s(16, 100); break;
            default: break;
        }
        if (v.soc != null && v.soc > 100) v.soc = null;
        return v;
    }
    static int batteryRank(int kind) {
        switch (kind) {
            case 2: return 0;
            case 10: return 1;
            case 12: return 2;
            case 6: case 11: return 3;
            case 13: case 254: return 4;
            case 1: case 3: case 5: case 8: return 5; // Voltage fallback when there is no bank-level monitor.
            default: return 99;
        }
    }
    static final class Device {
        final String id;
        String name, status = "Add advertisement key";
        Frame frame;
        byte[] key;
        Values values;
        long seen, packetAt;
        Device(String id) { this.id = id; }
        boolean live(long now) { return values != null && packetAt > now - FRESH_MS && packetAt <= now; }
    }
    static boolean userDismissal(long deadline, long deliveredAt) { return deliveredAt < deadline; }
    static final class Snapshot {
        final Double[] metrics = new Double[LABEL.length];
        long expires = Long.MAX_VALUE;
        long latest;
        boolean hasData() { for (Double v : metrics) if (v != null) return true; return false; }
        void include(Device source) {
            expires = Math.min(expires, source.packetAt + FRESH_MS);
            latest = Math.max(latest, source.packetAt);
        }
        boolean visible(boolean bluetooth, boolean armed, long now) { return bluetooth && armed && hasData() && expires > now; }
    }
    static Snapshot summarize(Iterable<Device> devices, long now) {
        Snapshot s = new Snapshot();
        Device battery = null, inverter = null;
        int bestBattery = 999, bestAc = 999;
        for (Device d : devices) {
            if (!d.live(now)) continue;
            Values v = d.values;
            if (v.solar != null) { s.metrics[SOLAR] = (s.metrics[SOLAR] == null ? 0 : s.metrics[SOLAR]) + v.solar; s.include(d); }
            int bank = batteryRank(d.frame.kind);
            if (bank < 99 && (v.soc != null || v.voltage != null)) {
                int rank = bank * 2 + (v.soc == null ? 1 : 0);
                if (rank < bestBattery || (rank == bestBattery && d.id.compareTo(battery.id) < 0)) { battery = d; bestBattery = rank; }
            }
            int rank = v.ac != null ? 0 : v.va != null ? 1 : v.vac != null ? 2 : 999;
            if (rank < bestAc || (rank < 999 && rank == bestAc && d.id.compareTo(inverter.id) < 0)) { inverter = d; bestAc = rank; }
        }
        if (battery != null) {
            Values v = battery.values;
            s.metrics[SOC] = v.soc; s.metrics[VOLTAGE] = v.voltage;
            if (batteryRank(battery.frame.kind) <= 4) s.metrics[BATTERY] = v.power();
            s.include(battery);
        }
        if (inverter != null) {
            Values v = inverter.values;
            s.metrics[AC] = v.ac; s.metrics[VA] = v.ac == null ? v.va : null; s.metrics[VAC] = v.vac;
            s.include(inverter);
        }
        return s;
    }
}
