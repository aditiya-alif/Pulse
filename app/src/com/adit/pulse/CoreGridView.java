// language: Java, file: CoreGridView.java, target: Android 16 (API 36)
// Eight CPU cores, one small curve each, laid out four across and two down.
//
// One curve per core rather than eight lines on one axis: at eight series the
// lines overlap into a knot, and what a reader wants here is per-core behaviour -
// is core 7 parked while core 4 is pinned, or are they all moving together.
//
// Each mini-curve is a real little chart: a vertical axis on the left, a baseline
// under it, the core's MAXIMUM clock printed at the top of that axis, and the
// current value below. The maximum comes from the device (`cpuinfo_max_freq`), so
// a 2.2 GHz core is not drawn against a made-up 3.4 GHz ceiling.
//
// The line's COLOUR is the reading, not the core: green when the core is near its
// top clock, amber in the middle, red when it is pinned - so a glance at the
// colours says which cores are working without reading any number.
package com.adit.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;
import java.util.Locale;

final class CoreGridView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final float density;
    private int cores;
    private float[] maxMhz;          // per core, from the device

    CoreGridView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setTextSize(9f * density);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        axis.setStyle(Paint.Style.STROKE);
        axis.setStrokeWidth(1f * density);
        axis.setColor(Theme.AXIS);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        grid.setColor(Theme.GRID);
    }

    /**
     * Feed the history. {@code maxPerCore} is each core's hardware maximum clock,
     * so a core with a lower ceiling is not drawn as if it were idle.
     *
     * The scratch arrays are REUSED across calls. This runs once a second for as
     * long as the home screen is open, and allocating a fresh 8 x N array (plus the
     * per-core rows) every second was steady garbage - a few hundred KB a minute
     * that the collector then had to walk. They are grown only when the history
     * actually gets longer, which happens for the first minute and then never.
     */
    private float[][] values;        // [core][time], reused
    private float[] current;         // per core, latest, reused

    void setData(List<Sample> samples, float[] maxPerCore) {
        this.maxMhz = maxPerCore;
        this.cores = maxPerCore.length;
        int n = samples.size();
        if (cores == 0 || n < 2) { invalidate(); return; }

        // Grow (never shrink) the buffers to fit, keeping the old arrays otherwise.
        if (values == null || values.length < cores || values[0].length < n) {
            values = new float[cores][n];
            current = new float[cores];
        }
        for (int c = 0; c < cores; c++) {
            float[] row = values[c];
            for (int i = 0; i < n; i++) {
                float[] m = samples.get(i).coreMhz;
                row[i] = c < m.length ? m[c] : Float.NaN;
            }
            current[c] = row[n - 1];
        }
        // The draw code walks each row up to the CURRENT length, so it must know it.
        this.count = n;
        invalidate();
    }

    /** How many time slots of each row are valid. */
    private int count;

    /**
     * The height is DERIVED from the width, so the view fits its curves exactly.
     *
     * Each cell's plot is as wide as the cell and 0.75 of that tall; the view
     * reports the total height those rows need. A fixed height would leave unused
     * space at the bottom, which then reads as an oversized gap before the divider
     * that follows.
     */
    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolve(wSpec, Ui.dp(getContext(), 320));
        int rows = Math.max(1, (Math.max(cores, 8) + COLS - 1) / COLS);
        float cellW = (w - GAP_DP * density * (COLS - 1)) / COLS;
        float plotH = cellW * 0.75f;
        float rowH = PAD_T_DP * density + plotH;
        int h = Math.round(rows * rowH);
        setMeasuredDimension(w, h);
    }

    private static int resolve(int spec, int def) {
        int mode = MeasureSpec.getMode(spec);
        int size = MeasureSpec.getSize(spec);
        if (mode == MeasureSpec.EXACTLY) return size;
        if (mode == MeasureSpec.AT_MOST) return Math.min(size, def);
        return def;
    }

    private static final int COLS = 4;
    private static final float GAP_DP = 5f;      // between columns, not on the edges
    private static final float PAD_T_DP = 12f;   // room for the label row above

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (cores == 0 || values == null) return;

        int rows = (cores + COLS - 1) / COLS;      // 8 cores -> 2 rows
        float gapX = GAP_DP * density;
        // The columns span the FULL width: the first curve starts at the left edge
        // and the last ends at the right edge, so the grid lines up with the
        // separator above it. The gap is only BETWEEN columns.
        float cellW = (w - gapX * (COLS - 1)) / COLS;
        float cellH = h / rows;

        for (int c = 0; c < cores; c++) {
            int col = c % COLS, row = c / COLS;
            float left = col * (cellW + gapX);
            drawCell(cv, c, left, row * cellH, cellW, cellH);
        }
    }

    private void drawCell(Canvas cv, int c, float x0, float y0, float cw, float ch) {
        float left = x0;
        float top = y0 + PAD_T_DP * density;
        // Width is the whole cell; height is three quarters of it.
        float width = cw;
        float height = width * 0.75f;
        // Never overflow the row if the view was given a shorter height.
        float availH = ch - PAD_T_DP * density;
        if (height > availH) { height = availH; width = height / 0.75f; }
        if (width <= 0 || height <= 0) return;
        float right = left + width;
        float bottom = top + height;

        float max = maxMhz[c] > 0 ? maxMhz[c] : 1f;

        // The baseline, and one faint midline. No vertical spine: with the axis
        // maximum gone there is nothing for a Y axis to label, so the curve stands
        // on its own baseline.
        cv.drawLine(left, bottom, right, bottom, axis);
        cv.drawLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f, grid);

        // The curve, coloured by where the current value sits in the core's range.
        float[] v = values[c];
        float now = current[c];
        int heat = heat(Float.isNaN(now) ? 0f : now / max);
        line.setColor(heat);
        path.reset();
        boolean started = false;
        // Only the first `count` slots of the reused row are valid.
        int len = Math.min(count, v.length);
        for (int i = 0; i < len; i++) {
            float val = v[i];
            if (Float.isNaN(val)) { started = false; continue; }
            float px = left + (right - left) * (i / (float) (len - 1));
            float py = bottom - (bottom - top) * Math.min(1f, val / max);
            if (!started) { path.moveTo(px, py); started = true; }
            else path.lineTo(px, py);
        }
        cv.drawPath(path, line);

        // Above the curve: "Core n" at the left, the current clock at the right.
        // Only the clock carries the heat colour; the name stays muted so the
        // number is what the eye lands on.
        float labelBase = top - 4f * density;
        text.setColor(Theme.MUTED);
        cv.drawText("Core " + c, left, labelBase, text);
        text.setColor(heat);
        String clock = Float.isNaN(now) ? "--"
                : String.format(Locale.US, "%.2fGHz", now / 1000f);
        cv.drawText(clock, right - text.measureText(clock), labelBase, text);
    }

    /** Green when the core is relaxed, amber mid, red when it is pinned. */
    private static int heat(float frac) {
        if (frac >= 0.75f) return Theme.CRITICAL;   // red   - working hard
        if (frac >= 0.40f) return Theme.WARNING;    // amber - mid
        return Theme.SERIES[5];                     // green - relaxed
    }
}
