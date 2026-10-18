// language: Java, file: BarView.java, target: Android 16 (API 36)
// The one-line overlay bar, drawn as a single custom view.
//
// One view, not a row of TextViews: the bar must measure to its content, wrap
// when the screen is narrow, and update in place. A custom onDraw does all three
// without a layout pass per sample.
//
// Content: the focused app's icon (when it resolves at this size), then the
// chosen items - CPU, MEM, TEMP, FPS, W by default. No color chips: at this size
// they read as noise, and the colors belong to the chart, not the bar. While a
// recording runs, a red dot and the elapsed time sit at the front.
//
// WRAPPING: the bar is one overlay window, so wrapping happens inside this view.
// onMeasure breaks the items into rows that fit the given maximum width; onDraw
// walks the same break list, so measurement and drawing can never disagree.
package com.adit.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class BarView extends View {
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private final float density;
    private final float padH, padV, gapItem, gapValue, iconSz, rowH, dotR;

    private Sample s;
    private Drawable appIcon;
    private String appLabel = "";
    private boolean recording;
    private boolean recPaused;
    private long recElapsedMs;

    /** Bar item ids to show, in order. */
    private List<String> items = new ArrayList<>();
    /** Row breaks computed in onMeasure; rowStart[i] is the first item of row i. */
    private final List<Integer> rowStart = new ArrayList<>();
    private int maxWidth;

    BarView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        padH = 8f * density;
        padV = 4.5f * density;
        gapItem = 11f * density;
        gapValue = 3.5f * density;
        iconSz = 12f * density;
        rowH = 14f * density;
        dotR = 3f * density;

        pill.setStyle(Paint.Style.FILL);
        pill.setColor(Theme.OVERLAY_BG);

        label.setColor(Theme.TEXT_DIM);
        label.setTextSize(9f * density);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        value.setColor(Theme.TEXT);
        value.setTextSize(10f * density);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        sep.setColor(0x1FFFFFFF);
        sep.setStrokeWidth(1f * density);

        rec.setStyle(Paint.Style.FILL);
        rec.setColor(0xFFD03B3B);
    }

    void setItems(List<String> ids) {
        this.items = ids;
        requestLayout();
        invalidate();
    }

    /**
     * Update the leading app icon/label.
     *
     * Re-layout ONLY when it actually changed: this is called on every sample,
     * and an unconditional requestLayout here re-laid the overlay window twice a
     * second - which is exactly the stutter felt while dragging the bar.
     */
    void setApp(Drawable icon, String text) {
        String t = text == null ? "" : text;
        boolean sameIcon = (icon == null && appIcon == null)
                || (icon != null && appIcon != null && icon.getConstantState() != null
                    && appIcon.getConstantState() != null
                    && icon.getConstantState().equals(appIcon.getConstantState()));
        if (sameIcon && t.equals(appLabel)) return;
        this.appIcon = icon;
        this.appLabel = t;
        requestLayout();
        invalidate();
    }

    void setSample(Sample s) {
        this.s = s;
        invalidate();
    }

    void setRecording(boolean on, long elapsedMs, boolean paused) {
        this.recording = on;
        this.recElapsedMs = elapsedMs;
        this.recPaused = paused;
        invalidate();
    }

    void setMaxWidth(int px) {
        if (px == maxWidth) return;
        maxWidth = px;
        requestLayout();
    }

    private boolean live() { return s != null && s.valid; }

    /** GPU has no reading on most MediaTek builds; it is dropped until it does. */
    private boolean visible(Bar.Item it) {
        if (it.id.equals("gpu")) return live() && !Float.isNaN(s.gpu);
        return true;
    }

    // -------------------------------------------------------------- measure

    @Override protected void onMeasure(int wSpec, int hSpec) {
        rowStart.clear();
        float natural = naturalWidth();
        int width = maxWidth > 0 ? (int) Math.min(natural, maxWidth) : (int) Math.ceil(natural);

        float avail = width - padH * 2f;
        float x = 0;
        int rows = 1;
        rowStart.add(0);
        for (int i = 0; i < items.size(); i++) {
            Bar.Item it = Bar.byId(items.get(i));
            if (it == null || !visible(it)) continue;
            float iw = itemWidth(it);
            float need = x == 0 ? iw : gapItem + iw;
            if (x > 0 && x + need > avail) {
                rows++;
                rowStart.add(i);
                x = iw;
            } else {
                x += need;
            }
        }
        float h = padV * 2f + rows * rowH + (rows - 1) * 2f * density;
        setMeasuredDimension(width, (int) Math.ceil(h));
    }

    private float naturalWidth() {
        float w = padH * 2f;
        if (appIcon != null || !appLabel.isEmpty()) w += leadingWidth();
        boolean first = true;
        for (String id : items) {
            Bar.Item it = Bar.byId(id);
            if (it == null || !visible(it)) continue;
            if (!first || appIcon != null || !appLabel.isEmpty()) w += gapItem;
            first = false;
            w += itemWidth(it);
        }
        return w;
    }

    private float leadingWidth() {
        if (recording) {
            // Reserve the width of a WIDE time, not the current one: the timer
            // changes every second, and letting the bar's width follow it would
            // reflow the overlay on every tick.
            return dotR * 2f + 5f * density + label.measureText("00:00") + 9f * density;
        }
        if (appIcon != null) return iconSz + 5f * density;
        if (!appLabel.isEmpty()) return label.measureText(appLabel) + 7f * density;
        return 0f;
    }

    private float itemWidth(Bar.Item it) {
        float w = 0;
        if (it.showLabel) w += label.measureText(it.label) + gapValue;
        return w + value.measureText(reserve(it));
    }

    /**
     * A fixed-width stand-in for an item's value, used ONLY for measurement.
     *
     * The bar is a WRAP_CONTENT overlay window, so if its measured width followed
     * the live value, every sample that changed a digit count would resize the
     * WINDOW - and WindowManager relayouting the window mid-drag is felt as a
     * stutter, once per sample, which is exactly the "stutters with the interval"
     * that was reported. Measuring against a widest-plausible template keeps the
     * window's size constant while the digits inside it change.
     *
     * Digits are the same width in this face, so a template of the right digit
     * count is a true upper bound.
     */
    private static String reserve(Bar.Item it) {
        switch (it.id) {
            case "cpu":  return "100%";
            case "mem":  return "00.0/00G";
            case "temp": return "00°/00°";
            case "fps":  return "000";
            case "gpu":  return "100%";
            case "w":    return "-00.0W";
            case "batt": return "100%";
            default:     return "000";
        }
    }

    private String recTime() {
        long sec = recElapsedMs / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    private boolean hasLeading() {
        return recording || appIcon != null || !appLabel.isEmpty();
    }

    /** The leading block's width at its CURRENT content (not the reserve). */
    private float leadingWidthActual() {
        if (recording) {
            return dotR * 2f + 5f * density
                    + label.measureText(recPaused ? "jeda" : recTime()) + 9f * density;
        }
        if (appIcon != null) return iconSz + 5f * density;
        if (!appLabel.isEmpty()) return label.measureText(appLabel) + 7f * density;
        return 0f;
    }

    /** An item's width at its CURRENT value (not the reserve). */
    private float itemWidthActual(Bar.Item it) {
        float w = 0;
        if (it.showLabel) w += label.measureText(it.label) + gapValue;
        return w + value.measureText(itemValue(it));
    }

    // ----------------------------------------------------------------- draw

    /**
     * The window is a FIXED size (measured from reserve templates, so it never
     * resizes and never stutters), but the PILL inside it hugs the real content.
     *
     * That is the whole trick for "no empty space on the right": the leftover
     * width stays in the window, transparent and untouchable, while the visible
     * pill is exactly as wide as the text it holds and centred in the window.
     */
    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        int rows = Math.max(1, rowStart.size());

        // Pass 1: each row's real content width.
        float[] rowW = new float[rows];
        boolean[] rowAny = new boolean[rows];
        if (hasLeading()) { rowW[0] = leadingWidthActual(); rowAny[0] = true; }

        int row = 0;
        for (int i = 0; i < items.size(); i++) {
            Bar.Item it = Bar.byId(items.get(i));
            if (it == null || !visible(it)) continue;
            while (row + 1 < rows && rowStart.get(row + 1) == i) row++;
            rowW[row] += (rowAny[row] ? gapItem : 0f) + itemWidthActual(it);
            rowAny[row] = true;
        }
        float contentW = 0;
        for (float rw : rowW) contentW = Math.max(contentW, rw);

        float pillW = Math.min(w, contentW + padH * 2f);
        float pillLeft = (w - pillW) / 2f;
        float radius = Math.min(h / 2f, 14f * density);
        r.set(pillLeft, 0, pillLeft + pillW, h);
        cv.drawRoundRect(r, radius, radius, pill);

        // Pass 2: draw the content inside the pill.
        float x = pillLeft + padH;
        float rowTop = padV;
        float cy = rowTop + rowH / 2f;

        if (recording) {
            rec.setColor(recPaused ? Theme.WARNING : Theme.CRITICAL);
            cv.drawCircle(x + dotR, cy, dotR, rec);
            x += dotR * 2f + 5f * density;
            cv.drawText(recPaused ? "jeda" : recTime(), x, cy + 3.2f * density, label);
            x += label.measureText(recPaused ? "jeda" : recTime()) + 9f * density;
        } else if (appIcon != null) {
            int sz = (int) iconSz;
            appIcon.setBounds((int) x, (int) (cy - sz / 2f), (int) x + sz, (int) (cy + sz / 2f));
            appIcon.draw(cv);
            x += sz + 5f * density;
        } else if (!appLabel.isEmpty()) {
            cv.drawText(appLabel, x, cy + 3.2f * density, label);
            x += label.measureText(appLabel) + 7f * density;
        }

        row = 0;
        boolean rowHasContent = rowAny[0];
        boolean firstItem = true;
        for (int i = 0; i < items.size(); i++) {
            Bar.Item it = Bar.byId(items.get(i));
            if (it == null || !visible(it)) continue;
            while (row + 1 < rows && rowStart.get(row + 1) == i) {
                row++;
                rowTop = padV + row * (rowH + 2f * density);
                cy = rowTop + rowH / 2f;
                x = pillLeft + padH;
                rowHasContent = rowAny[row];
            }
            // A separator appears in exactly one place: between the recording
            // timer and the metrics, because the timer is a different kind of
            // thing from a reading and the line marks that. Between two metrics
            // there is none - the readings are one group, and a line between each
            // pair read as clutter.
            if (rowHasContent && firstItem && recording) {
                cv.drawLine(x - gapItem / 2f, cy - 5f * density,
                        x - gapItem / 2f, cy + 5f * density, sep);
            }
            if (rowHasContent) x += gapItem / 2f;
            rowHasContent = true;
            firstItem = false;
            if (it.showLabel) {
                cv.drawText(it.label, x, cy + 3.2f * density, label);
                x += label.measureText(it.label) + gapValue;
            }
            String v = itemValue(it);
            cv.drawText(v, x, cy + 3.4f * density, value);
            x += value.measureText(v) + gapItem / 2f;
        }
    }

    /** The value text for one item, at the precision that reading deserves. */
    private String itemValue(Bar.Item it) {
        if (!live()) return "--";
        switch (it.id) {
            case "cpu":  return ChartView.fmt(s.cpuPct, 0) + "%";
            case "mem":  return ChartView.fmt(s.memUsedKb / 1048576f, 1) + "/"
                    + ChartView.fmt(s.memTotalKb / 1048576f, 0) + "G";
            case "temp": return ChartView.fmt(s.cpuTemp, 0) + "°/" + ChartView.fmt(s.batTemp, 0) + "°";
            case "fps":  return ChartView.fmt(s.fps < 0 ? 0 : s.fps, 0);
            case "gpu":  return Float.isNaN(s.gpu) ? "--" : ChartView.fmt(s.gpu, 0) + "%";
            case "w":    return ChartView.fmt(s.watts, 1) + "W";
            case "batt": return s.level < 0 ? "--" : s.level + "%";
            default:     return "--";
        }
    }
}
