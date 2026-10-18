// language: Java, file: TempView.java, target: Android 16 (API 36)
// One small temperature curve per sensor, stacked vertically.
//
// NOT combined onto one axis. These sensors sit at very different levels - skin is
// around 40 C while the SoC is near 80 - and drawn together the cool ones flatten
// into a single line at the bottom. Small multiples give each sensor its own
// vertical scale, so each curve uses the full height and its shape is readable.
//
// Each row carries its own Y-axis markers (top = its ceiling, bottom = its floor)
// and the current value, so a row is a complete little chart. Vertical layout,
// like the frame-rate distribution in a recording, one curve per sensor.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

final class TempView extends View {
    /** One sensor's series. */
    static final class Line {
        final String name;
        final int color;
        final float[] values;
        final float min, max;
        Line(String name, int color, float[] values, float min, float max) {
            this.name = name; this.color = color;
            this.values = values; this.min = min; this.max = max;
        }
    }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float density;
    private List<Line> lines;

    TempView(Context c) {
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

    void setLines(List<Line> l) {
        this.lines = l;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolve(wSpec, Ui.dp(getContext(), 320));
        int h = resolve(hSpec, Ui.dp(getContext(), 200));
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
        if (lines == null || lines.isEmpty()) return;

        int n = lines.size();
        float rowH = h / n;
        // The plot spans the FULL width - no side padding - so it lines up with the
        // separator above it rather than being inset from it.
        float left = 0f;
        float right = w;

        for (int i = 0; i < n; i++) {
            drawRow(cv, lines.get(i), i * rowH, rowH, left, right);
        }
    }

    private void drawRow(Canvas cv, Line ln, float y0, float rowH, float left, float right) {
        // Equal gaps above and below: the label row sits in the top pad, the curve
        // in the rest, so the spacing between rows is even.
        float padT = 12f * density, padB = 12f * density;
        float top = y0 + padT, bottom = y0 + rowH - padB;
        if (bottom <= top || right <= left) return;

        float span = Math.max(1f, ln.max - ln.min);

        // The baseline, and one faint midline. No vertical spine and no axis
        // labels - the sensor's name and its current value say everything the row
        // needs to.
        cv.drawLine(left, bottom, right, bottom, axis);
        cv.drawLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f, grid);

        // The curve.
        line.setColor(ln.color);
        path.reset();
        boolean started = false;
        int len = ln.values.length;
        for (int i = 0; i < len; i++) {
            float v = ln.values[i];
            if (Float.isNaN(v)) { started = false; continue; }
            float px = left + (right - left) * (len <= 1 ? 0f : i / (float) (len - 1));
            float frac = Math.max(0f, Math.min(1f, (v - ln.min) / span));
            float py = bottom - (bottom - top) * frac;
            if (!started) { path.moveTo(px, py); started = true; }
            else path.lineTo(px, py);
        }
        cv.drawPath(path, line);

        // Sensor name and current value, top-right of the row.
        float last = Float.NaN;
        for (int i = len - 1; i >= 0; i--) {
            if (!Float.isNaN(ln.values[i])) { last = ln.values[i]; break; }
        }
        text.setColor(ln.color);
        String label = ln.name + " " + (Float.isNaN(last) ? "--" : Math.round(last) + "°");
        cv.drawText(label, right - text.measureText(label), y0 + 10f * density, text);
    }
}
