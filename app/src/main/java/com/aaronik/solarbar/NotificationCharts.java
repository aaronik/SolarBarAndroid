package com.aaronik.solarbar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.List;

final class NotificationCharts {
    static final long WINDOW = 600_000;
    private static final int[] COLORS = {0xff82edc3, 0xff85cbff, 0xffa9dfc4, 0xffffc674, 0xffd5b1ff, 0xffd5b1ff, 0xffd5b1ff};
    private static final int[] ORDER = {Victron.SOC, Victron.SOLAR, Victron.AC, Victron.VA, Victron.BATTERY, Victron.VOLTAGE, Victron.VAC};
    static final String[] SHORT_NAMES = {"Battery", "Bank", "Battery", "Solar", "AC", "AC", "AC"};

    private static final int[] COMPACT_ORDER = {Victron.SOC, Victron.SOLAR, Victron.AC, Victron.VA,
            Victron.BATTERY, Victron.VOLTAGE, Victron.VAC};
    private static final int[] TREND_ORDER = {Victron.SOLAR, Victron.BATTERY, Victron.AC, Victron.VA,
            Victron.SOC, Victron.VOLTAGE, Victron.VAC};
    private static final String[] COMPACT_LABELS = {"BATTERY", "BANK POWER", "BANK VOLTS", "SOLAR", "AC OUTPUT", "AC APPARENT", "AC VOLTS"};

    static RemoteViews compact(Context context, Victron.Snapshot s, List<LocalData.Point> points, long now) {
        int trend = -1;
        for (int i : TREND_ORDER) {
            if (s.metrics[i] == null) continue;
            int recorded = 0;
            for (LocalData.Point point : points) if (point.values[i] != null) recorded++;
            if (recorded >= 2) { trend = i; break; }
        }
        Bitmap image = Bitmap.createBitmap(640, 88, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(image);
        c.scale(2, 2);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff162326);
        c.drawRoundRect(0, 0, 320, 44, 7, 7, p);
        StringBuilder description = new StringBuilder("Victron live readings. ");
        List<Integer> active = new ArrayList<>();
        for (int i : COMPACT_ORDER) if (s.metrics[i] != null && active.size() < 6) active.add(i);
        boolean twoRows = active.size() > 3;
        int columns = Math.max(1, twoRows ? 3 : active.size());
        float width = (trend < 0 ? 306f : 217f) / columns;
        for (int slot = 0; slot < active.size(); slot++) {
            int i = active.get(slot);
            float x = 7 + (slot % columns) * width;
            float y = twoRows ? 3 + 20 * (slot / columns) : 9;
            String value = SolarApp.format(i, s.metrics[i]);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setColor(0xffc0d0cf);
            p.setTextSize(twoRows ? 7.7f : 9.5f);
            c.drawText(COMPACT_LABELS[i], x, y + 7, p);
            p.setColor(COLORS[i]);
            p.setTextSize(twoRows ? 11.5f : 14);
            c.drawText(value, x, y + (twoRows ? 17 : 23), p);
            description.append(Victron.LABEL[i]).append(' ').append(value).append(". ");
        }
        if (trend >= 0) {
            p.setColor(0xff435355);
            p.setStrokeWidth(1);
            c.drawLine(225, 6, 225, 38, p);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setColor(COLORS[trend]);
            p.setTextSize(8);
            c.drawText("10m " + COMPACT_LABELS[trend], 232, 12, p);
            plot(c, p, points, trend, s.metrics[trend], now, 232, 313, 16, 37);
            description.append("10-minute ").append(Victron.LABEL[trend]).append(" trend.");
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_compact);
        views.setImageViewBitmap(R.id.notification_compact_image, image);
        views.setContentDescription(R.id.notification_compact_image, description.toString());
        return views;
    }

    static RemoteViews render(Context context, Victron.Snapshot snapshot, List<LocalData.Point> points, long now) {
        List<Integer> metrics = new ArrayList<>();
        for (int i : ORDER) {
            if (snapshot.metrics[i] == null || (i == Victron.VOLTAGE && snapshot.metrics[Victron.SOC] != null)
                    || (i == Victron.VAC && (snapshot.metrics[Victron.AC] != null || snapshot.metrics[Victron.VA] != null))) continue;
            metrics.add(i);
            if (metrics.size() == 3) break;
        }
        if (metrics.isEmpty() || points.isEmpty()) return null;
        boolean hasHistory = false;
        for (LocalData.Point point : points) for (int i : metrics) if (point.values[i] != null) hasHistory = true;
        if (!hasHistory) return null;
        Bitmap image = Bitmap.createBitmap(640, 260, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(image);
        c.scale(2, 2);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff162326);
        c.drawRoundRect(0, 0, 320, 130, 9, 9, p);
        p.setColor(0xffc0d0cf);
        p.setTextSize(11);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText("LAST 10 MIN", 11, 16, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("NOW", 309, 16, p);
        p.setTextAlign(Paint.Align.LEFT);
        StringBuilder description = new StringBuilder("Last 10 minutes. ");
        float rowHeight = 99f / metrics.size();
        for (int row = 0; row < metrics.size(); row++) {
            int i = metrics.get(row);
            float top = 22 + row * rowHeight, bottom = top + rowHeight - 4;
            String label = i == Victron.VA ? "AC apparent" : Victron.LABEL[i];
            String value = SolarApp.format(i, snapshot.metrics[i]);
            p.setTypeface(Typeface.DEFAULT);
            p.setColor(0xffc0d0cf);
            p.setTextSize(11);
            c.drawText(label, 11, top + 11, p);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setColor(COLORS[i]);
            p.setTextSize(12);
            c.drawText(value, 11, top + 24, p);
            description.append(label).append(' ').append(value).append(". ");
            plot(c, p, points, i, snapshot.metrics[i], now, 108, 308, top + 3, bottom);
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_charts);
        views.setImageViewBitmap(R.id.notification_chart_image, image);
        views.setContentDescription(R.id.notification_chart_image, description.toString());
        return views;
    }

    private static void plot(Canvas c, Paint p, List<LocalData.Point> points, int i, double latest, long now, float left, float right, float top, float bottom) {
        double min = latest, max = latest;
        for (LocalData.Point point : points) if (point.values[i] != null) {
            min = Math.min(min, point.values[i]); max = Math.max(max, point.values[i]);
        }
        if (i == Victron.SOC) { min = 0; max = 100; }
        else {
            if (Victron.UNIT[i].equals("W") || Victron.UNIT[i].equals("VA")) { min = Math.min(0, min); max = Math.max(0, max); }
            double pad = Math.max((max - min) * .08, Victron.UNIT[i].equals("V") ? .05 : 1);
            min -= pad; max += pad;
        }
        p.setStrokeWidth(1);
        p.setColor(0xff435355);
        float guide = min < 0 && max > 0 ? top + (float)(max/(max-min)) * (bottom-top) : bottom;
        c.drawLine(left, guide, right, guide, p);
        p.setColor(COLORS[i]);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        LocalData.Point previous = null;
        float lastX = 0, lastY = 0;
        for (LocalData.Point point : points) {
            Double v = point.values[i];
            if (v == null) { previous = null; continue; }
            float x = left + (right-left) * (point.time - (now-WINDOW)) / WINDOW;
            float y = top + (float)((max - v) / (max - min)) * (bottom-top);
            if (previous != null && point.time - previous.time <= Victron.FRESH_MS) c.drawLine(lastX, lastY, x, y, p);
            else c.drawPoint(x, y, p);
            lastX = x; lastY = y; previous = point;
        }
    }
}
