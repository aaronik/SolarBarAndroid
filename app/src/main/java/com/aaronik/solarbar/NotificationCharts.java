package com.aaronik.solarbar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.widget.RemoteViews;
import android.view.View;
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
        Bitmap image = Bitmap.createBitmap(640, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(image);
        c.scale(2, 2);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff162326);
        c.drawRoundRect(0, 0, 320, 48, 7, 7, p);
        StringBuilder description = new StringBuilder("Victron live readings. ");
        List<Integer> active = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        Rect bounds = new Rect();
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        final float gap = 9, inset = 7;
        float used = 2 * inset;
        for (int i : COMPACT_ORDER) {
            if (s.metrics[i] == null) continue;
            String value = SolarApp.format(i, s.metrics[i]);
            p.setTextSize(30);
            p.getTextBounds(value, 0, value.length(), bounds);
            float width = Math.max(44, bounds.width() + 1);
            p.setTextSize(10);
            width = Math.max(width, p.measureText(COMPACT_LABELS[i]) + 1);
            float next = used + (active.isEmpty() ? 0 : gap) + width;
            // Leave the remaining readings to the dashboard rather than shrink the whole row.
            if (!active.isEmpty() && (next > 320 || active.size() == 4)) break;
            active.add(i);
            values.add(value);
            widths.add(width);
            used = next;
        }
        float divider = 320;
        if (trend >= 0 && used <= 246) divider = 246;
        else trend = -1;
        float extra = (divider - used) / Math.max(1, active.size());
        float x = inset;
        for (int cell = 0; cell < active.size(); cell++) {
            int i = active.get(cell);
            float width = widths.get(cell) + extra;
            String value = values.get(cell);
            p.setColor(0xffc0d0cf);
            drawFitted(c, p, bounds, COMPACT_LABELS[i], x, 2, width, 12, 12);
            p.setColor(COLORS[i]);
            drawFitted(c, p, bounds, value, x, 13, width, 46, 46);
            description.append(Victron.LABEL[i]).append(' ').append(value).append(". ");
            x += width + gap;
        }
        if (trend >= 0) {
            p.setColor(0xff435355);
            p.setStrokeWidth(1);
            c.drawLine(divider, 5, divider, 43, p);
            p.setColor(COLORS[trend]);
            float left = divider + 5;
            drawFitted(c, p, bounds, "10m " + COMPACT_LABELS[trend], left, 4, 314 - left, 13, 10);
            plot(c, p, points, trend, s.metrics[trend], now, left, 314, 16, 42);
            description.append("10-minute ").append(Victron.LABEL[trend]).append(" trend.");
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_compact);
        views.setImageViewBitmap(R.id.notification_compact_image, image);
        views.setContentDescription(R.id.notification_compact_image, description.toString());
        return views;
    }

    // Fit the actual ink, including minus signs, unit glyphs, and localized digits.
    private static void drawFitted(Canvas c, Paint p, Rect bounds, String text,
                                   float x, float top, float width, float bottom, float maxSize) {
        float low = 0, high = maxSize;
        for (int step = 0; step < 10; step++) {
            float size = (low + high) / 2;
            p.setTextSize(size);
            p.getTextBounds(text, 0, text.length(), bounds);
            if (bounds.width() <= width && bounds.height() <= bottom - top) low = size;
            else high = size;
        }
        p.setTextSize(low);
        p.getTextBounds(text, 0, text.length(), bounds);
        int layer = c.save();
        c.clipRect(x, top, x + width, bottom);
        c.drawText(text, x - bounds.left, top + (bottom - top - bounds.height()) / 2 - bounds.top, p);
        c.restoreToCount(layer);
    }

    static RemoteViews render(Context context, Victron.Snapshot snapshot, List<LocalData.Point> points, long now) {
        List<Integer> metrics = new ArrayList<>();
        for (int i : ORDER) {
            if (snapshot.metrics[i] == null || (i == Victron.VOLTAGE && snapshot.metrics[Victron.SOC] != null)
                    || (i == Victron.VAC && (snapshot.metrics[Victron.AC] != null || snapshot.metrics[Victron.VA] != null))) continue;
            metrics.add(i);
            if (metrics.size() == 3) break;
        }
        if (metrics.isEmpty()) return null;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.notification_charts);
        views.removeAllViews(R.id.expanded_metrics);
        for (int i : metrics) {
            RemoteViews metric = new RemoteViews(context.getPackageName(), R.layout.notification_metric);
            metric.setTextViewText(R.id.expanded_metric_value, SolarApp.format(i, snapshot.metrics[i]));
            metric.setTextColor(R.id.expanded_metric_value, COLORS[i]);
            metric.setTextViewText(R.id.expanded_metric_label, compactName(i));
            views.addView(R.id.expanded_metrics, metric);
        }
        List<Integer> trends = new ArrayList<>();
        for (int i : metrics) {
            for (LocalData.Point point : points) if (point.values[i] != null) { trends.add(i); break; }
        }
        if (trends.isEmpty()) {
            views.setTextViewText(R.id.notification_chart_caption, "Collecting 10-minute history…");
            views.setViewVisibility(R.id.notification_chart_image, View.GONE);
            return views;
        }
        Bitmap image = Bitmap.createBitmap(640, 136, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(image);
        c.scale(2, 2);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setColor(0xff162326);
        c.drawRoundRect(0, 0, 320, 68, 7, 7, p);
        Rect bounds = new Rect();
        StringBuilder description = new StringBuilder("10-minute trends: ");
        float height = 62f / trends.size();
        for (int row = 0; row < trends.size(); row++) {
            int i = trends.get(row);
            float top = 3 + row * height;
            p.setColor(COLORS[i]);
            drawFitted(c, p, bounds, compactName(i), 7, top, 70, top + height, 13);
            plot(c, p, points, i, snapshot.metrics[i], now, 83, 312, top + 3, top + height - 3);
            description.append(Victron.LABEL[i]).append(". ");
        }
        views.setImageViewBitmap(R.id.notification_chart_image, image);
        views.setContentDescription(R.id.notification_chart_image, description.toString());
        return views;
    }

    private static String compactName(int metric) {
        switch (metric) {
            case Victron.SOC: return "Battery";
            case Victron.BATTERY: return "Bank power";
            case Victron.VOLTAGE: return "Bank volts";
            case Victron.SOLAR: return "Solar";
            case Victron.AC: return "AC output";
            case Victron.VA: return "AC apparent";
            default: return "AC volts";
        }
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
