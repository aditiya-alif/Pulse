// language: Java, file: Meter.java, target: Android 16 (API 36)
// A straight bar: a rounded track with a rounded fill, a label and a value.
//
// The other half of the app's stat language. Where a reading is best compared
// along a line - memory used, battery level, a thermal share - a bar says it more
// directly than a number, and a row of them is easier to scan than a row of
// digits. Horizontal by default; vertical for the few places a column reads
// better.
//
// Hand-drawn, like the ring: no progress-bar widget, so it matches the app's own
// shapes rather than the platform's.
package com.adit.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

final class Meter extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private float frac;
    private int color = Theme.ACCENT;
    private String label = "", value = "";

    Meter(Context c) {
        super(c);
        track.setColor(Theme.CARD_HI);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(Theme.TEXT);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    void set(float frac, int color, String label, String value) {
        this.frac = Float.isNaN(frac) ? 0f : Math.max(0f, Math.min(1f, frac));
        this.color = color;
        this.label = label == null ? "" : label;
        this.value = value == null ? "" : value;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolve(wSpec, Ui.dp(getContext(), 200));
        int h = resolve(hSpec, Ui.dp(getContext(), 40));
        setMeasuredDimension(w, h);
    }

    private static int resolve(int spec, int def) {
        int mode = MeasureSpec.getMode(spec);
        int size = MeasureSpec.getSize(spec);
        if (mode == MeasureSpec.EXACTLY) return size;
        if (mode == MeasureSpec.AT_MOST) return Math.min(size, def);
        return def;
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float barH = Math.min(h * 0.30f, Ui.dp(getContext(), 10));
        float barY = h - barH;
        float radius = barH / 2f;

        // Label left, value right, sitting JUST above the bar. The text is placed
        // against the bar rather than at the top of the box: a top-aligned text
        // left a band of empty space between it and its own bar, which read as two
        // separate things instead of a label and its bar.
        float baseline = barY - Ui.dp(getContext(), 4);
        text.setTextSize(h * 0.34f);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Theme.MUTED);
        cv.drawText(label, 0, baseline, text);

        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(color);
        cv.drawText(value, w, baseline, text);

        // Track, then fill.
        box.set(0, barY, w, h);
        cv.drawRoundRect(box, radius, radius, track);
        if (frac > 0.001f) {
            fill.setColor(color);
            box.set(0, barY, Math.max(barH, w * frac), h);
            cv.drawRoundRect(box, radius, radius, fill);
        }
    }
}
