// language: Java, file: Ring.java, target: Android 16 (API 36)
// A round gauge: one or two arcs on a track, with a value in the middle.
//
// A ring rather than a number because a reading like "29%" means nothing until it
// is compared to its range, and a ring shows that comparison at a glance. Two
// rings can sit on the same centre - an outer and a smaller inner - so a single
// round shape can carry two related readings instead of taking two boxes.
//
// Drawn with arcs and round caps, matching the app's rounded language; no
// framework widget is involved.
package com.adit.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

final class Ring extends View {
    private static final float START = 144f;   // bottom-left
    private static final float SWEEP = 252f;   // leaving a gap at the bottom

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private float outerFrac, innerFrac;
    private int outerColor = Theme.ACCENT, innerColor = Theme.SERIES[7];
    private String value = "--";
    private String label = "";

    Ring(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(Theme.TEXT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    void set(float outer, int outerColor, float inner, int innerColor,
             String value, String label) {
        this.outerFrac = clamp(outer);
        this.innerFrac = clamp(inner);
        this.outerColor = outerColor;
        this.innerColor = innerColor;
        this.value = value;
        this.label = label;
        invalidate();
    }

    private static float clamp(float f) {
        if (Float.isNaN(f)) return 0f;
        return Math.max(0f, Math.min(1f, f));
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolve(wSpec, Ui.dp(getContext(), 140));
        int h = resolve(hSpec, Ui.dp(getContext(), 140));
        int s = Math.min(w, h);
        setMeasuredDimension(s, s);
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
        float cx = w / 2f, cy = h / 2f;
        float outerStroke = w * 0.085f;
        float innerStroke = w * 0.07f;

        // Outer ring.
        float outerR = w / 2f - outerStroke / 2f - w * 0.03f;
        track.setStrokeWidth(outerStroke);
        track.setColor(Theme.CARD_HI);
        box.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        cv.drawArc(box, START, SWEEP, false, track);
        if (outerFrac > 0.001f) {
            fill.setStrokeWidth(outerStroke);
            fill.setColor(outerColor);
            cv.drawArc(box, START, SWEEP * outerFrac, false, fill);
        }

        // Inner ring, smaller and inside the first.
        float innerR = outerR - outerStroke - w * 0.035f;
        if (innerR > innerStroke) {
            track.setStrokeWidth(innerStroke);
            box.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
            cv.drawArc(box, START, SWEEP, false, track);
            if (innerFrac > 0.001f) {
                fill.setStrokeWidth(innerStroke);
                fill.setColor(innerColor);
                cv.drawArc(box, START, SWEEP * innerFrac, false, fill);
            }
        }

        // Value in the middle, with an optional short label under it. Each wears
        // the colour of the arc it belongs to - the percentage is the outer (CPU)
        // arc's colour, the memory text the inner (RAM) arc's - so the two readings
        // are tied to their arcs by colour as well as position.
        text.setTextSize(w * 0.20f);
        text.setColor(outerColor);
        cv.drawText(value, cx, cy + w * 0.02f, text);
        if (!label.isEmpty()) {
            text.setTextSize(w * 0.075f);
            text.setColor(innerColor);
            text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            cv.drawText(label, cx, cy + w * 0.155f, text);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        }
    }
}
