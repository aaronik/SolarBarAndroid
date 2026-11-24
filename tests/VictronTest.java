package com.aaronik.solarbar;

import java.util.Arrays;
import java.util.Collections;

final class VictronTest {
    // SolarBar's public test fixtures, encrypted with a synthetic key.
    private static final String TEST_KEY = "000102030405060708090a0b0c0d0e0f";
    private static final String SHUNT = "100289a302b04000659dd8e746f48bf054c68ca5751368";
    private static final String SOLAR = "100242a001620700ef399571166713475d96c4ad";
    private static final String VEBUS = "100380270ce1b200b3b47c4b5d78d1c0426a34cba8";
    private static final String MULTI = "100043a40bf4e40076e497a8460dbf50339f191b4e35";
    private static byte[] bytes(String hex) {
        byte[] b = new byte[hex.length()/2];
        for (int i = 0; i < b.length; i++) b[i] = (byte)Integer.parseInt(hex.substring(i*2,i*2+2), 16);
        return b;
    }
    private static Victron.Frame frame(String packet) { return Victron.parse(bytes(packet)); }
    private static Victron.Device device(String packet, String id, long time) throws Exception {
        Victron.Device d = new Victron.Device(id);
        d.frame = frame(packet); d.values = d.frame.decode(Victron.key(TEST_KEY)); d.packetAt = time;
        return d;
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    private static void eq(Double got, double want, String what) { check(got != null && Math.abs(got-want) < 1e-8, what + ": " + got); }
    public static void main(String[] args) throws Exception {
        long now = 100_000;
        Victron.Device shunt = device(SHUNT, "1", now-1000), solar = device(SOLAR, "2", now-500),
                solar2 = device(SOLAR, "3", now-5000), veBus = device(VEBUS, "4", now-2000), multi = device(MULTI, "5", now);
        eq(shunt.values.soc, 50, "Shunt SOC"); eq(shunt.values.voltage, 12.53, "Shunt voltage");
        eq(shunt.values.current, 0, "Shunt zero is not missing"); eq(solar.values.solar, 19, "MPPT power");
        eq(solar.values.yield, .03, "MPPT yield"); eq(veBus.values.ac, 45, "VE.Bus watts"); eq(veBus.values.current, -5.9, "Signed current");
        eq(multi.values.ac, 722, "Multi RS watts"); eq(multi.values.yield, 5.32, "Multi RS yield"); eq(multi.values.solar, 0, "Multi RS zero solar");
        check(frame(SHUNT).decode(bytes("ff0102030405060708090a0b0c0d0e0f")) == null, "Key hint mismatch");
        check(Victron.key("gg0102030405060708090a0b0c0d0e0f") == null, "Invalid key");
        check(frame("100289a302b040af") == null && frame("110289a302b040af01") == null, "Bad header");
        check(!Victron.fields(2, new byte[0]).hasData(), "Truncated record");
        Victron.Values inverter = Victron.fields(3, bytes("0900004e14d002e02e1e00"));
        eq(inverter.va, 720, "Apparent power"); eq(inverter.vac, 120, "AC voltage");
        Victron.Values voltageOnly = Victron.fields(3, bytes("0900004e14ffffe4aeff03"));
        check(voltageOnly.va == null && voltageOnly.ac == null, "Unavailable watts and VA");
        eq(voltageOnly.vac, 120.04, "Voltage separate from power");
        Victron.Device phoenix = new Victron.Device("0"); phoenix.frame = frame("100000000300000000"); phoenix.values = inverter; phoenix.packetAt = now;
        Victron.Snapshot s = Victron.summarize(Arrays.asList(shunt, solar, solar2, veBus, multi, phoenix), now);
        eq(s.metrics[Victron.SOLAR], 38, "Solar adds across devices"); eq(s.metrics[Victron.SOC], 50, "Shunt preferred");
        eq(s.metrics[Victron.AC], 45, "A single inverter; watts outrank VA"); check(s.metrics[Victron.VA] == null, "Do not double count VA");
        s = Victron.summarize(Collections.singletonList(phoenix), now);
        eq(s.metrics[Victron.VA], 720, "VA fallback"); check(s.metrics[Victron.AC] == null, "VA is not W");
        check(s.visible(true, true, now), "Icon launch + live readings");
        check(!s.visible(false, true, now), "Bluetooth off");
        check(!s.visible(true, false, now), "Swipe dismissed");
        check(!s.visible(true, true, now + Victron.FRESH_MS), "Notification timeout");
        check(!Victron.summarize(Arrays.asList(shunt, solar, veBus), now + Victron.FRESH_MS).visible(true, true, now + Victron.FRESH_MS), "All devices out of range");
        check(Victron.userDismissal(now + 1000, now), "Manual swipe disarms the notification");
        check(!Victron.userDismissal(now, now), "A system timeout does not disarm the notification");
        System.out.println("Victron decoder, aggregation, and notification eligibility tests passed");
    }
}
