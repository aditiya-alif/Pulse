// language: Java, file: Toggle.java, target: Android 16 (API 36)
// A small on/off switch, drawn rather than themed.
//
// The platform Switch wears the system accent and shape, which is a different
// design language from the rest of the app; and a pair of words ("Shown" /
// "Hidden") reads as text to parse rather than a control to flip. This is a
// pill track with a knob that slides, coloured by the app's own palette, with a
// short spring on the knob so the change is felt as well as seen.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class Toggle extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private final float density;
    private boolean on;
    private float pos;          // 0 = off, 1 = on; animated
    private float animFrom, animTo;
    private long animStart;
    private static final long ANIM_MS = 220;

    private Runnable onChange;

    Toggle(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.FILL);
        knob.setStyle(Paint.Style.FILL);
        knob.setColor(0xFFFFFFFF);
        setOnClickListener(v -> {
            on = !on;
            animateTo(on ? 1f : 0f);
            if (onChange != null) onChange.run();
        });
    }

    void setOn(boolean v, boolean animate) {
        if (v == on && animate) return;
        on = v;
        if (animate) animateTo(on ? 1f : 0f);
        else { pos = on ? 1f : 0f; invalidate(); }
    }

    boolean isOn() { return on; }

    void setOnChange(Runnable r) { this.onChange = r; }

    private void animateTo(float target) {
        animFrom = pos;
        animTo = target;
        animStart = System.currentTimeMillis();
        postOnAnimation(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long dt = System.currentTimeMillis() - animStart;
            float t = Math.min(1f, dt / (float) ANIM_MS);
            // Ease-out-back, so the knob settles with a small overshoot.
            float c = 1.70158f;
            float e = 1f + (c + 1f) * (float) Math.pow(t - 1, 3)
                    + c * (float) Math.pow(t - 1, 2);
            pos = animFrom + (animTo - animFrom) * e;
            invalidate();
            if (t < 1f) postOnAnimation(this);
            else pos = animTo;
        }
    };

    /**
     * Stop the knob animation when the view leaves the window.
     *
     * The ticker re-posts itself until it reaches the end, which is normally a few
     * frames - but a toggle detached mid-animation (the screen is closing, the row
     * is removed) would otherwise keep a Choreographer callback alive against a
     * view nobody can see. Cheap to prevent, and it keeps the view honest about
     * when it is doing work.
     */
    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(ticker);
        pos = animTo;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension((int) (44 * density), (int) (26 * density));
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float radius = h / 2f;

        // Track: interpolate between the off and on colours so the fill follows
        // the knob rather than snapping.
        int off = Theme.CARD_HI;
        int onC = Theme.ACCENT;
        float p = Math.max(0f, Math.min(1f, pos));
        track.setColor(blend(off, onC, p));
        r.set(0, 0, w, h);
        cv.drawRoundRect(r, radius, radius, track);

        float pad = 2.5f * density;
        float d = h - pad * 2f;
        float cx = pad + d / 2f + (w - d - pad * 2f) * p;
        knob.setColor(blend(0xFFB9B9C4, 0xFFFFFFFF, p));
        cv.drawCircle(cx, h / 2f, d / 2f, knob);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
