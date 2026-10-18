// language: Java, file: Icon.java, target: Android 16 (API 36)
// Line icons, drawn by hand.
//
// The app uses no framework widgets and no icon assets - a Material vector would
// be a different drawing language, and a PNG set would need six densities kept in
// sync. These are strokes on a 24x24 grid, scaled to the view, so one class covers
// every icon and they all share the same weight and round caps.
package com.adit.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class Icon extends View {
    // Names, so call sites read as intent rather than coordinates.
    static final String SLIDERS = "sliders";
    static final String CHART   = "chart";
    static final String GEAR    = "gear";
    static final String GRID    = "grid";
    static final String BATTERY = "battery";
    static final String LAYERS  = "layers";
    static final String TERMINAL= "terminal";
    static final String PULSE   = "pulse";
    static final String DEVICE  = "device";
    static final String OVERLAY = "overlay";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private String name;

    Icon(Context c, String name, int color) {
        super(c);
        this.name = name;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    void setColor(int color) { p.setColor(color); invalidate(); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolve(wSpec, Ui.dp(getContext(), 24));
        int h = resolve(hSpec, Ui.dp(getContext(), 24));
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
        float s = Math.min(w, h) / 24f;          // 24-unit design grid
        float ox = (w - 24f * s) / 2f;
        float oy = (h - 24f * s) / 2f;
        cv.save();
        cv.translate(ox, oy);
        cv.scale(s, s);
        p.setStrokeWidth(1.9f);                  // in design units; scales with the view

        switch (name == null ? "" : name) {
            case SLIDERS: drawSliders(cv); break;
            case CHART:   drawChart(cv); break;
            case GEAR:    drawGear(cv); break;
            case GRID:    drawGrid(cv); break;
            case BATTERY: drawBattery(cv); break;
            case LAYERS:  drawLayers(cv); break;
            case TERMINAL:drawTerminal(cv); break;
            case PULSE:   drawPulse(cv); break;
            case DEVICE:  drawDevice(cv); break;
            case OVERLAY: drawOverlay(cv); break;
            default:      drawGrid(cv); break;
        }
        cv.restore();
    }

    // ------------------------------------------------------------- each glyph

    /** Tools: three faders with the knobs offset. */
    private void drawSliders(Canvas cv) {
        line(cv, 4, 7, 20, 7);
        line(cv, 4, 12, 20, 12);
        line(cv, 4, 17, 20, 17);
        dot(cv, 9, 7);
        dot(cv, 15, 12);
        dot(cv, 7, 17);
    }

    /** Recordings: a line chart, for saved performance captures. */
    private void drawChart(Canvas cv) {
        // Axes.
        line(cv, 4, 4, 4, 20);
        line(cv, 4, 20, 20, 20);
        // A rising trace across them.
        path.reset();
        path.moveTo(6, 17);
        path.lineTo(10, 13);
        path.lineTo(14, 15);
        path.lineTo(19, 7);
        cv.drawPath(path, p);
    }

    /** Settings: a gear as a ring with eight radial teeth. */
    private void drawGear(Canvas cv) {
        cv.drawCircle(12, 12, 4.4f, p);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8.0;
            float cx = 12 + (float) Math.cos(a) * 7.4f;
            float cy = 12 + (float) Math.sin(a) * 7.4f;
            float ix = 12 + (float) Math.cos(a) * 5.4f;
            float iy = 12 + (float) Math.sin(a) * 5.4f;
            line(cv, ix, iy, cx, cy);
        }
    }

    /** Apps: four tiles. */
    private void drawGrid(Canvas cv) {
        r.set(4, 4, 11, 11);  cv.drawRoundRect(r, 2, 2, p);
        r.set(13, 4, 20, 11); cv.drawRoundRect(r, 2, 2, p);
        r.set(4, 13, 11, 20); cv.drawRoundRect(r, 2, 2, p);
        r.set(13, 13, 20, 20); cv.drawRoundRect(r, 2, 2, p);
    }

    /** Battery: body, terminal, and a charge bar. */
    private void drawBattery(Canvas cv) {
        r.set(3, 8, 18, 17);
        cv.drawRoundRect(r, 2.2f, 2.2f, p);
        line(cv, 20, 11, 20, 14);
        line(cv, 6, 12.5f, 12, 12.5f);
    }

    /** Processes: stacked layers. */
    private void drawLayers(Canvas cv) {
        path.reset();
        path.moveTo(12, 4); path.lineTo(20, 9); path.lineTo(12, 14); path.lineTo(4, 9);
        path.close();
        cv.drawPath(path, p);
        line(cv, 4, 13.5f, 12, 18.5f);
        line(cv, 20, 13.5f, 12, 18.5f);
    }

    /** Commands: a terminal box with a prompt. */
    private void drawTerminal(Canvas cv) {
        r.set(3, 5, 21, 19);
        cv.drawRoundRect(r, 3, 3, p);
        line(cv, 7, 10, 10, 12);
        line(cv, 10, 12, 7, 14);
        line(cv, 12.5f, 15, 17, 15);
    }

    /** Diagnostics: a pulse trace. */
    private void drawPulse(Canvas cv) {
        path.reset();
        path.moveTo(3, 12);
        path.lineTo(7, 12);
        path.lineTo(9, 7);
        path.lineTo(12, 17);
        path.lineTo(14.5f, 12);
        path.lineTo(21, 12);
        cv.drawPath(path, p);
    }

    /** System: a device outline. */
    private void drawDevice(Canvas cv) {
        r.set(6, 3, 18, 21);
        cv.drawRoundRect(r, 3, 3, p);
        line(cv, 10, 18, 14, 18);
    }

    /** Overlay: a bar floating above a screen, i.e. something shown on top. */
    private void drawOverlay(Canvas cv) {
        // The device outline.
        r.set(4, 5, 20, 19);
        cv.drawRoundRect(r, 3, 3, p);
        // The floating bar sitting over it, and its little value ticks.
        r.set(7, 9, 17, 13);
        cv.drawRoundRect(r, 2, 2, p);
        dot(cv, 9, 11);
        line(cv, 11.5f, 11, 15, 11);
    }

    // ---------------------------------------------------------------- strokes

    private void line(Canvas cv, float x1, float y1, float x2, float y2) {
        cv.drawLine(x1, y1, x2, y2, p);
    }

    private void dot(Canvas cv, float x, float y) {
        Paint f = new Paint(p);
        f.setStyle(Paint.Style.FILL);
        cv.drawCircle(x, y, p.getStrokeWidth() * 0.72f, f);
    }
}
