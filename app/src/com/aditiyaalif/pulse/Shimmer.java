// language: Java, file: Shimmer.java, target: Android 16 (API 36)
// A placeholder for content that is still being fetched.
//
// Where a screen reads the device over adb, the first result can take a moment -
// and a card that is simply empty reads as "nothing here" rather than "not yet".
// These are the app's own rounded shapes, greyed, with a highlight that sweeps
// left to right.
//
// THREE SHAPES, because a placeholder should sit where its content will be. A row
// of text lines is right for a list, but the home screen's readout is a ring with
// a stack of bars beside it, and its chart box is a grid of curves - so each of
// those gets a placeholder drawn in its own shape. A generic bar where a ring will
// be reads as a glitch, not as loading.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

final class Shimmer extends View {
    /** Text lines, for a list. */
    static final int LINES = 0;
    /** A ring with a stack of bars, for the home readout. */
    static final int READOUT = 1;
    /** A grid of curves over a few wide rows, for the home chart box. */
    static final int CHART = 2;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    /** Reused for clipping to a rounded shape, so the sweep cannot spill corners. */
    private final Path clip = new Path();

    private final float density;
    private final int kind;
    private final int rows;

    /** Sweep position, 0..1 across a period that extends past both edges. */
    private float phase;
    private long last;

    static Shimmer lines(Context c, int rows) { return new Shimmer(c, LINES, rows); }
    static Shimmer readout(Context c) { return new Shimmer(c, READOUT, 0); }
    static Shimmer chart(Context c) { return new Shimmer(c, CHART, 0); }

    private Shimmer(Context c, int kind, int rows) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        this.kind = kind;
        this.rows = Math.max(1, rows);
        bar.setStyle(Paint.Style.FILL);
        bar.setColor(Theme.CARD_HI);
        sweep.setStyle(Paint.Style.FILL);
        hair.setStyle(Paint.Style.FILL);
        hair.setColor(Theme.HAIRLINE);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolve(wSpec, Ui.dp(getContext(), 200));
        int defH;
        switch (kind) {
            case READOUT: defH = Ui.dp(getContext(), 150); break;
            case CHART:   defH = Ui.dp(getContext(), 320); break;
            default:      defH = Ui.dp(getContext(), 20 * rows + 8 * (rows - 1));
        }
        int h = resolve(hSpec, defH);
        setMeasuredDimension(w, h);
    }

    private static int resolve(int spec, int def) {
        int mode = MeasureSpec.getMode(spec);
        int size = MeasureSpec.getSize(spec);
        if (mode == MeasureSpec.EXACTLY) return size;
        if (mode == MeasureSpec.AT_MOST) return Math.min(size, def);
        return def;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = 0;
        postOnAnimation(tick);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (last == 0) last = now;
            float dt = (now - last) / 1000f;
            last = now;
            phase = (phase + dt / 1.1f) % 1f;   // ~1.1 s per sweep
            invalidate();
            postOnAnimation(this);
        }
    };

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // The highlight band, moving left to right across the whole view. It is
        // BRIGHT: on a dark card a subtle white wash was invisible, so the sweep
        // reads as a moving light rather than a barely-there tint. Three stops -
        // clear, bright, clear - with the peak well above the card so it shows.
        float bandW = w * 0.42f;
        float cx = -bandW + (w + bandW * 2f) * phase;
        sweep.setShader(new LinearGradient(cx - bandW, 0, cx + bandW, 0,
                new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));

        switch (kind) {
            case READOUT: drawReadout(cv, w, h); break;
            case CHART:   drawChart(cv, w, h); break;
            default:      drawLines(cv, w, h); break;
        }
    }

    /**
     * Rounded bar + sweep, clipped to the SAME rounded shape.
     *
     * The sweep is clipped with a rounded path, not a plain rectangle: a square
     * clip let the highlight spill past the bar's rounded ends and fill the corners,
     * which is why every placeholder read as a rectangle rather than a rounded bar.
     */
    private void shape(Canvas cv, float left, float top, float right, float bottom,
                       float radius) {
        r.set(left, top, right, bottom);
        cv.drawRoundRect(r, radius, radius, bar);
        cv.save();
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        cv.clipPath(clip);
        cv.drawRect(left, top, right, bottom, sweep);
        cv.restore();
    }

    private void drawLines(Canvas cv, float w, float h) {
        float gap = 8f * density;
        float barH = (h - gap * (rows - 1)) / rows;
        float radius = Math.min(barH / 2f, 8f * density);
        for (int i = 0; i < rows; i++) {
            float top = i * (barH + gap);
            float ww = i == rows - 1 ? w * 0.6f : w;
            shape(cv, 0, top, ww, top + barH, radius);
        }
    }

    /** A ring on the left, a stack of bars on the right - the home readout. */
    private void drawReadout(Canvas cv, float w, float h) {
        float d = Math.min(h * 0.86f, w * 0.36f);
        float cx = d / 2f, cy = h / 2f;
        // The ring: a filled disc, so it reads as the gauge it will become. The
        // sweep is clipped to the CIRCLE, so the highlight follows the disc's edge.
        bar.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, d / 2f, bar);
        cv.save();
        clip.reset();
        clip.addCircle(cx, cy, d / 2f, Path.Direction.CW);
        cv.clipPath(clip);
        cv.drawRect(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f, sweep);
        cv.restore();

        // Five bars beside it, matching the five meter rows.
        float bx = d + 16f * density;
        float bw = Math.max(0, w - bx);
        float barH = Math.min(10f * density, h / 8f);
        float gap = (h - 5 * barH) / 6f;
        for (int i = 0; i < 5; i++) {
            float top = gap + i * (barH + gap);
            shape(cv, bx, top, bx + bw, top + barH, barH / 2f);
        }
    }

    /** Eight small curves over three wide rows - the home chart box. */
    private void drawChart(Canvas cv, float w, float h) {
        float gapX = 5f * density, gapY = 10f * density;
        float cw = (w - gapX * 3f) / 4f;
        float coreH = h * 0.42f;
        float chh = (coreH - gapY) / 2f;
        float radius = 4f * density;

        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                float left = col * (cw + gapX);
                float top = row * (chh + gapY);
                shape(cv, left, top, left + cw, top + chh * 0.72f, radius);
            }
        }

        // The divider between the two halves.
        float mid = h * 0.47f;
        cv.drawRect(0, mid, w, mid + 1f * density, hair);

        // Three wide rows for the temperatures.
        float ty = h * 0.52f;
        float th = (h - ty) / 3f;
        for (int i = 0; i < 3; i++) {
            float top = ty + i * th;
            shape(cv, 0, top, w, top + th * 0.5f, radius);
        }
    }
}
