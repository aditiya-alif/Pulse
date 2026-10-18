// language: Java, file: ChartView.java, target: Android 16 (API 36)
// The curve renderer, shared by the recording detail, the live monitor and the
// PNG export.
//
//   SINGLE    one metric against its own real value axis on the left.
//   PAIR      two metrics at once: the first against the LEFT axis, the second
//             against the RIGHT axis. Two units cannot share one scale, so each
//             line gets its own axis, named at the top in its own colour - a
//             reader always knows which numbers belong to which line. It is a
//             pair, never more: a third line would need a third scale and the
//             plot stops being readable.
//
// Both forms carry a real X axis along the bottom. There are no interior grid
// lines; the axis ticks are the reference, which keeps the plot quiet.
//
// Curves are monotone-clamped cubic Bezier - smooth, but the control points are
// pinned inside each segment's own y-range so the line cannot overshoot into a
// value the data never had. The whole plot is CLIPPED to its rect, so a line can
// never spill over an axis or run into the labels.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class ChartView extends View {
    /** One plotted line. {@code values} index 0 is oldest; NaN means a gap. */
    static final class Series {
        final String label;
        final int color;
        final float[] values;
        final String unit;
        final int decimals;
        /**
         * Whether the axis window must contain zero. True for power, whose SIGN is
         * the point - negative draws, positive charges - so a scale that floats
         * away from zero would hide which side of the line a reading sits on.
         */
        final boolean includeZero;

        Series(String label, int color, float[] values, String unit, int decimals,
               boolean includeZero) {
            this.label = label;
            this.color = color;
            this.values = values;
            this.unit = unit;
            this.decimals = decimals;
            this.includeZero = includeZero;
        }
    }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint swatch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();
    private final RectF r = new RectF();

    private List<Series> series = new ArrayList<>();
    private boolean combined = true;
    /** The three labels under the plot: start, middle, end. Nulls are skipped. */
    private String xl, xm, xr;

    private final float density;
    private int touchIndex = -1;

    // The plot rect, remembered from the last draw so touch maps to the same place.
    private float plotLeft, plotRight, plotTop, plotBottom;

    ChartView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(0.8f * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);

        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(2f * density);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);

        axisLine.setStyle(Paint.Style.STROKE);
        axisLine.setStrokeWidth(1f * density);
        axisLine.setColor(Theme.AXIS);

        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(1f * density);
        tick.setColor(Theme.AXIS);

        axisText.setColor(Theme.MUTED);
        axisText.setTextSize(8.5f * density);
        axisText.setTypeface(android.graphics.Typeface.MONOSPACE);

        titleText.setColor(Theme.TEXT_DIM);
        titleText.setTextSize(10.5f * density);
        titleText.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));

        swatch.setStyle(Paint.Style.FILL);

        cross.setStyle(Paint.Style.STROKE);
        cross.setStrokeWidth(1f * density);
        cross.setColor(0x66FFFFFF);

        tipBg.setStyle(Paint.Style.FILL);
        tipBg.setColor(0xF21A1A20);

        tipText.setColor(Theme.TEXT);
        tipText.setTextSize(10f * density);
        tipText.setTypeface(android.graphics.Typeface.MONOSPACE);

        dot.setStyle(Paint.Style.FILL);
    }

    /** Two metrics: the first on the left axis, the second on the right. */
    void setCombined(List<Series> s) {
        this.series = s == null ? new ArrayList<>() : s;
        this.combined = true;
        this.touchIndex = -1;
        invalidate();
    }

    /** One metric, against its own real axis. */
    void setSingle(Series s) {
        List<Series> l = new ArrayList<>(1);
        if (s != null) l.add(s);
        this.series = l;
        this.combined = false;
        this.touchIndex = -1;
        invalidate();
    }

    void setXLabels(String left, String mid, String right) {
        this.xl = left; this.xm = mid; this.xr = right;
        invalidate();
    }

    void clear() {
        series = new ArrayList<>();
        touchIndex = -1;
        invalidate();
    }

    boolean hasData() {
        for (Series s : series) if (s.values != null && s.values.length > 1) return true;
        return false;
    }

    // ------------------------------------------------------------------ draw

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        boolean dual = combined && series.size() > 1;

        float titleBand = 26f * density;
        float xBand = 20f * density;

        // Value windows and their tick labels first: the side gutters are sized
        // from the widest number, so the numbers must be known before the plot
        // rect can be placed.
        float[] lw = null, rw = null;
        String[] lt = null, rt = null;
        if (hasData()) {
            lw = window(series.get(0).values, series.get(0).includeZero);
            lt = tickLabels(lw, series.get(0).decimals);
            if (dual) {
                rw = window(series.get(1).values, series.get(1).includeZero);
                rt = tickLabels(rw, series.get(1).decimals);
            }
        }

        // Each gutter is the widest number on that axis plus the tick mark and a
        // gap - the same rule the histogram uses. The numbers therefore sit OUTSIDE
        // the plot and can never land on the curve. This is what buys the full
        // width back for the plot itself.
        float gutterL = lt == null ? 2f * density : widest(lt) + 8f * density;
        float gutterR = rt == null ? 2f * density : widest(rt) + 8f * density;

        plotLeft = gutterL;
        plotRight = w - gutterR;
        plotTop = titleBand;
        plotBottom = h - xBand;
        if (plotRight - plotLeft < 24f * density || plotBottom - plotTop < 24f * density) return;

        // The axis names, each in its own colour, so the two scales are told apart
        // at a glance: left name at the left, right name at the right.
        if (!series.isEmpty()) {
            drawTitle(cv, series.get(0), plotLeft, titleBand, false);
            if (dual) drawTitle(cv, series.get(1), plotRight, titleBand, true);
        }

        // The bottom axis is always drawn; the value axes only when there is data.
        cv.drawLine(plotLeft, plotBottom, plotRight, plotBottom, axisLine);

        if (!hasData()) {
            cv.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisLine);
            if (dual) cv.drawLine(plotRight, plotTop, plotRight, plotBottom, axisLine);
            return;
        }

        drawValueAxis(cv, lt, false);
        if (dual) drawValueAxis(cv, rt, true);

        // Curves, clipped to the plot: nothing can leave the axes.
        cv.save();
        cv.clipRect(plotLeft, plotTop, plotRight, plotBottom);
        for (int si = 0; si < series.size(); si++) {
            Series s = series.get(si);
            if (s.values == null || s.values.length < 2) continue;
            float[] win = si == 0 ? lw : rw;
            if (win == null) continue;
            path.reset();
            buildPath(path, s.values, win[0], win[1]);
            glow.setColor(Theme.glow(s.color, 0x33));
            cv.drawPath(path, glow);
            line.setColor(s.color);
            cv.drawPath(path, line);
        }
        cv.restore();

        int idx = touchIndex;
        if (idx >= 0) {
            int len = maxLen();
            if (idx < len) {
                float x = xAt(idx, len);
                for (int si = 0; si < series.size(); si++) {
                    Series s = series.get(si);
                    if (s.values == null || idx >= s.values.length) continue;
                    float v = s.values[idx];
                    if (Float.isNaN(v)) continue;
                    float[] win = si == 0 ? lw : rw;
                    if (win == null) continue;
                    dot.setColor(s.color);
                    cv.drawCircle(x, yAt(v, win[0], win[1]), 3.2f * density, dot);
                }
                cv.drawLine(x, plotTop, x, plotBottom, cross);
                drawTooltip(cv, idx, x);
            }
        }

        if (xl != null || xm != null || xr != null) {
            // Baseline below the axis, measured from the glyphs so the label cannot
            // reach back up into the plot.
            Paint.FontMetrics fm = axisText.getFontMetrics();
            float y = plotBottom + 4f * density - fm.ascent;
            if (xl != null) {
                axisText.setTextAlign(Paint.Align.LEFT);
                cv.drawText(xl, plotLeft, y, axisText);
            }
            if (xm != null) {
                axisText.setTextAlign(Paint.Align.CENTER);
                cv.drawText(xm, (plotLeft + plotRight) / 2f, y, axisText);
            }
            if (xr != null) {
                axisText.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(xr, plotRight, y, axisText);
            }
        }
    }

    /** The five tick labels for a window, bottom to top, with the bottom blank. */
    private String[] tickLabels(float[] win, int decimals) {
        String[] out = new String[5];
        for (int i = 0; i <= 4; i++) {
            if (i == 0) { out[i] = ""; continue; }   // the corner: no number
            float f = i / 4f;
            out[i] = fmt(win[0] + (win[1] - win[0]) * f, decimals);
        }
        return out;
    }

    private float widest(String[] labels) {
        float max = 0;
        for (String s : labels) if (s != null) max = Math.max(max, axisText.measureText(s));
        return max;
    }

    /**
     * One value axis: the line, five ticks, and a number at each tick.
     *
     * The numbers are drawn OUTSIDE the plot, in the gutter the caller measured for
     * them, so they never land on a curve. The number at the bottom corner is
     * blank: it sits where the two axes meet, and it only crowds the origin.
     */
    private void drawValueAxis(Canvas cv, String[] ticks, boolean right) {
        float x = right ? plotRight : plotLeft;
        cv.drawLine(x, plotTop, x, plotBottom, axisLine);

        int n = 4;
        for (int i = 0; i <= n; i++) {
            if (ticks[i] == null || ticks[i].isEmpty()) continue;
            float f = i / (float) n;
            float y = plotBottom - f * (plotBottom - plotTop);
            if (right) {
                cv.drawLine(plotRight, y, plotRight + 3f * density, y, tick);
                axisText.setTextAlign(Paint.Align.LEFT);
                cv.drawText(ticks[i], plotRight + 5f * density, y + 3f * density, axisText);
            } else {
                cv.drawLine(plotLeft, y, plotLeft - 3f * density, y, tick);
                axisText.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(ticks[i], plotLeft - 5f * density, y + 3f * density, axisText);
            }
        }
    }

    /** A colour dot and the metric's name, sitting over its own axis. */
    private void drawTitle(Canvas cv, Series s, float edge, float bandH, boolean rightAlign) {
        float cy = bandH * 0.5f + 1f * density;
        float dotR = 3.5f * density;
        float textW = titleText.measureText(s.label);
        float gap = 5f * density;
        float total = dotR * 2f + gap + textW;
        float x0 = rightAlign ? edge - total : edge;
        swatch.setColor(s.color);
        cv.drawCircle(x0 + dotR, cy, dotR, swatch);
        titleText.setTextAlign(Paint.Align.LEFT);
        cv.drawText(s.label, x0 + dotR * 2f + gap, cy + 3.5f * density, titleText);
    }

    /**
     * Monotone-clamped cubic through the points, skipping NaN as a gap.
     *
     * The control points of each segment are clamped to that segment's own
     * y-range, which is what removes overshoot: a plain Catmull-Rom spline can
     * bulge past both endpoints on a sharp change, drawing a peak the data never
     * contained.
     */
    private void buildPath(Path p, float[] v, float lo, float hi) {
        int n = v.length;
        boolean started = false;
        float prevX = 0, prevY = 0;
        for (int i = 0; i < n; i++) {
            float val = v[i];
            if (Float.isNaN(val)) { started = false; continue; }
            float x = xAt(i, n);
            float y = yAt(val, lo, hi);
            if (!started) {
                p.moveTo(x, y);
                started = true;
            } else {
                float dx = x - prevX, dy = y - prevY;
                float segLo = Math.min(prevY, y), segHi = Math.max(prevY, y);
                float c1y = clamp(prevY + dy / 6f, segLo, segHi);
                float c2y = clamp(y - dy / 6f, segLo, segHi);
                p.cubicTo(prevX + dx / 3f, c1y, x - dx / 3f, c2y, x, y);
            }
            prevX = x; prevY = y;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private float xAt(int i, int n) {
        if (n <= 1) return plotRight;
        return plotLeft + (plotRight - plotLeft) * i / (float) (n - 1);
    }

    private float yAt(float val, float lo, float hi) {
        float f = (val - lo) / (hi - lo);
        return plotBottom - f * (plotBottom - plotTop);
    }

    /**
     * The value window a line is drawn against.
     *
     * It is the data's own range with 12% padding, so the curve fills the plot
     * without ever touching an axis, and a signal that barely moves still reads
     * as a real shape rather than a flat line. When {@code includeZero} is set the
     * window is widened to reach zero, which is what keeps a wattage curve's sign
     * legible.
     */
    private static float[] window(float[] v, boolean includeZero) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float x : v) {
            if (Float.isNaN(x)) continue;
            if (x < lo) lo = x;
            if (x > hi) hi = x;
        }
        if (lo == Float.MAX_VALUE) return new float[]{0f, 1f};
        if (includeZero) {
            if (lo > 0f) lo = 0f;
            if (hi < 0f) hi = 0f;
        }
        if (hi - lo < 1e-6f) {
            float mid = (lo + hi) / 2f;
            lo = mid - 0.5f;
            hi = mid + 0.5f;
        }
        float pad = (hi - lo) * 0.12f;
        return new float[]{lo - pad, hi + pad};
    }

    private int maxLen() {
        int n = 0;
        for (Series s : series) if (s.values != null) n = Math.max(n, s.values.length);
        return n;
    }

    // -------------------------------------------------------------- tooltip

    private void drawTooltip(Canvas cv, int idx, float x) {
        float pad = 7f * density;
        float lineH = 13f * density;
        List<String> lines = new ArrayList<>();
        for (Series s : series) {
            if (s.values == null || idx >= s.values.length) continue;
            float v = s.values[idx];
            if (Float.isNaN(v)) continue;
            lines.add(s.label + "  " + fmt(v, s.decimals)
                    + (s.unit.isEmpty() ? "" : " " + s.unit));
        }
        if (lines.isEmpty()) return;

        float wMax = 0;
        for (String l : lines) wMax = Math.max(wMax, tipText.measureText(l));
        float bw = wMax + pad * 2f;
        float bh = lines.size() * lineH + pad * 2f;

        float bx = x + 10f * density;
        if (bx + bw > plotRight) bx = x - 10f * density - bw;
        if (bx < plotLeft) bx = plotLeft;
        float by = plotTop + 4f * density;
        if (by + bh > plotBottom) by = plotBottom - bh;

        r.set(bx, by, bx + bw, by + bh);
        cv.drawRoundRect(r, 7f * density, 7f * density, tipBg);
        for (int i = 0; i < lines.size(); i++) {
            cv.drawText(lines.get(i), bx + pad, by + pad + lineH * (i + 0.8f), tipText);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int len = maxLen();
        if (len < 2) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float left = plotLeft, right = plotRight;
                if (right - left < 1f) {
                    left = 38f * density;
                    right = getWidth() - 38f * density;
                }
                float f = (e.getX() - left) / Math.max(1f, right - left);
                int i = Math.round(f * (len - 1));
                touchIndex = Math.max(0, Math.min(len - 1, i));
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touchIndex = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    static String fmt(float v, int decimals) {
        if (Float.isNaN(v)) return "--";
        if (decimals <= 0) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.US, "%." + decimals + "f", v);
    }
}
