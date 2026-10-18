// language: Java, file: HistView.java, target: Android 16 (API 36)
// A distribution: how many samples landed in each bucket.
//
// This is the one chart that answers a question a curve cannot - whether a
// session was mostly smooth with a few bad frames, or consistently mediocre.
// A single average hides that difference; the shape does not.
//
// It is labelled like the curves are: the unit along the bottom, the count on the
// left. Touching it shows a moving line with the band's own numbers, the same
// gesture the curves use. The plot runs flush to the card's right edge, and its
// left edge is set by the widest count label so the numbers sit outside the bars.
package com.adit.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

final class HistView extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private final float density;
    private int[] buckets = new int[0];
    private float lo, hi;
    /** Count in the tallest bucket; the vertical scale. Zero means nothing to draw. */
    private int tallest;
    private int barColor = Theme.SERIES[4];
    /** The unit shown at the bottom-right ("fps" / "ms"), or null for none. */
    private String unit;
    /** Bucket under the finger, or -1 when nothing is touched. */
    private int touchBucket = -1;

    private float plotLeft, plotRight, plotTop, plotBottom;

    HistView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        bar.setStyle(Paint.Style.FILL);
        bar.setColor(Theme.SERIES[4]);                 // magenta, the FPS slot
        axis.setStyle(Paint.Style.STROKE);
        axis.setColor(Theme.AXIS);
        axis.setStrokeWidth(1f * density);
        cross.setStyle(Paint.Style.STROKE);
        cross.setStrokeWidth(1f * density);
        cross.setColor(0x88FFFFFF);
        text.setColor(Theme.MUTED);
        text.setTextSize(8.5f * density);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        tipBg.setStyle(Paint.Style.FILL);
        tipBg.setColor(0xF21A1A20);
        tipText.setColor(Theme.TEXT);
        tipText.setTextSize(10f * density);
        tipText.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    /** How many samples are in the tallest bucket, for the count label. */
    private void finish(int color, String unit) {
        // The tallest bucket sets the vertical scale. This MUST be the field, not
        // a local: an earlier version declared a local `max` here that shadowed
        // the field, so the field stayed 0 and onDraw divided by it - producing
        // Infinity for every bar height and drawing nothing at all.
        tallest = 0;
        for (int v : buckets) tallest = Math.max(tallest, v);
        barColor = color;
        this.unit = unit;
        invalidate();
    }

    /**
     * Build the distribution from the samples' frame rates.
     *
     * Buckets span 0..(observed max + a little), with a floor of 30 so a session
     * pinned at a low rate still gets a sensible axis.
     */
    void setSamples(List<Sample> samples, int nBuckets) {
        float observed = -Float.MAX_VALUE;
        int count = 0;
        for (Sample s : samples) {
            if (s.fps < 0) continue;
            observed = Math.max(observed, s.fps);
            count++;
        }
        if (count == 0) { buckets = new int[0]; invalidate(); return; }

        lo = 0;
        hi = Math.max(observed + 2f, 30f);
        buckets = new int[nBuckets];
        for (Sample s : samples) {
            if (s.fps < 0) continue;
            int b = (int) ((s.fps - lo) / (hi - lo) * nBuckets);
            b = Math.max(0, Math.min(nBuckets - 1, b));
            buckets[b]++;
        }
        finish(Theme.SERIES[4], "fps");   // FPS slot colour
    }

    /**
     * Build the distribution from frame TIMES instead of frame rates.
     *
     * The same session, read the way a stutter is felt: a frame rate is frames per
     * second, but a hitch is one frame that took too long. The frame time is the
     * reciprocal of the rate (1000 / fps ms), so no new data is needed - and the
     * buckets are milliseconds rather than frames.
     */
    void setFrameTimes(List<Sample> samples, int nBuckets) {
        float observed = 0f;
        int count = 0;
        for (Sample s : samples) {
            if (s.fps <= 0) continue;
            observed = Math.max(observed, 1000f / s.fps);
            count++;
        }
        if (count == 0) { buckets = new int[0]; invalidate(); return; }

        lo = 0;
        // Round the ceiling up to a tidy millisecond value, floor 33 ms (30 fps).
        hi = Math.max(33f, (float) Math.ceil(observed / 5f) * 5f);
        buckets = new int[nBuckets];
        for (Sample s : samples) {
            if (s.fps <= 0) continue;
            float ms = 1000f / s.fps;
            int b = (int) ((ms - lo) / (hi - lo) * nBuckets);
            b = Math.max(0, Math.min(nBuckets - 1, b));
            buckets[b]++;
        }
        finish(Theme.SERIES[2], "ms");    // aqua, distinct from FPS
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || buckets.length == 0 || tallest <= 0) return;

        // The plot runs to the card's right edge; its left edge is set by the
        // widest count label, so the numbers sit in a gutter OUTSIDE the bars
        // rather than on top of them.
        float labelW = text.measureText(String.valueOf(tallest));
        plotLeft = labelW + 7f * density;
        plotRight = w - 1f * density;
        plotTop = 10f * density;
        plotBottom = h - 14f * density;
        if (plotRight <= plotLeft || plotBottom <= plotTop) return;

        // The count at the top of the scale only - the zero line needs no number,
        // its position is the axis itself.
        text.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(String.valueOf(tallest), plotLeft - 4f * density,
                plotTop + 3f * density, text);

        // Left axis and bottom axis.
        cv.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axis);
        cv.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axis);

        float bw = (plotRight - plotLeft) / buckets.length;
        float gap = Math.min(2f * density, bw * 0.25f);
        bar.setColor(barColor);
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] == 0) continue;
            float bh = (plotBottom - plotTop) * (buckets[i] / (float) tallest);
            float x = plotLeft + i * bw;
            r.set(x + gap / 2f, plotBottom - bh, x + bw - gap / 2f, plotBottom);
            cv.drawRoundRect(r, 2f * density, 2f * density, bar);
        }

        // The touched band: a line and its numbers. Never on screen unless a finger
        // is down.
        if (touchBucket >= 0 && touchBucket < buckets.length) {
            float x = plotLeft + (touchBucket + 0.5f) * bw;
            cv.drawLine(x, plotTop, x, plotBottom, cross);
            float bandLo = lo + (hi - lo) * touchBucket / buckets.length;
            float bandHi = lo + (hi - lo) * (touchBucket + 1) / buckets.length;
            String line = fmtBand(bandLo, bandHi) + "  ·  " + buckets[touchBucket];
            float tw = tipText.measureText(line);
            float pad = 6f * density;
            float bwid = tw + pad * 2f;
            float bx = x + 8f * density;
            if (bx + bwid > plotRight) bx = x - 8f * density - bwid;
            if (bx < plotLeft) bx = plotLeft;
            float bh2 = 18f * density;
            float by = plotTop + 4f * density;
            r.set(bx, by, bx + bwid, by + bh2);
            cv.drawRoundRect(r, 6f * density, 6f * density, tipBg);
            cv.drawText(line, bx + pad, by + 12.5f * density, tipText);
        }

        // The upper bound at the right, with the unit. No zero at the left: the
        // axis corner already says where the scale starts.
        text.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(Math.round(hi) + (unit == null ? "" : " " + unit),
                plotRight, h - 3f * density, text);
    }

    /** The band's range as a compact label, e.g. "58-63" or "40-45 ms". */
    private String fmtBand(float a, float b) {
        int ia = Math.round(a), ib = Math.round(b);
        return ia + "-" + ib + (unit == null ? "" : " " + unit);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (buckets.length == 0 || plotRight <= plotLeft) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float bw = (plotRight - plotLeft) / buckets.length;
                int i = (int) ((e.getX() - plotLeft) / Math.max(1f, bw));
                touchBucket = Math.max(0, Math.min(buckets.length - 1, i));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchBucket = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
