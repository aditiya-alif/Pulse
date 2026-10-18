// language: Java, file: SplashActivity.java, target: Android 16 (API 36)
// The launcher screen: a short animated mark, then the main screen.
//
// It exists rather than an animated drawable because the app has real work to do
// at startup - the wireless-debugging reconnect and the GPU probe - and doing it
// behind a drawn splash is what keeps the first frame of the settings screen from
// stalling. The splash does not wait for that work; it just covers the first
// moments and hands over.
//
// The animation is the app's own mark: a pulse trace that draws itself left to
// right, then the wordmark rising under it, then the whole thing fades into the
// settings screen.
package com.adit.pulse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {
    private static final long TOTAL_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.init(this);
        goEdgeToEdge();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TraceView trace = new TraceView(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 150), Ui.dp(this, 60));
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(trace, tlp);

        TextView word = new TextView(this);
        word.setText("Pulse");
        word.setTextColor(Theme.TEXT);
        word.setTextSize(28);
        word.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        word.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.gravity = Gravity.CENTER_HORIZONTAL;
        wlp.topMargin = Ui.dp(this, 14);
        col.addView(word, wlp);

        TextView tag = new TextView(this);
        tag.setText("Performance monitor");
        tag.setTextColor(Theme.MUTED);
        tag.setTextSize(12);
        tag.setLetterSpacing(0.14f);
        tag.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = Ui.dp(this, 4);
        col.addView(tag, glp);

        // Wordmark and tagline rise in after the trace has drawn.
        word.setAlpha(0f);
        word.setTranslationY(Ui.dp(this, 10));
        tag.setAlpha(0f);
        tag.setTranslationY(Ui.dp(this, 8));

        setContentView(root);
        applyInsets(root, col);

        trace.start();
        main.postDelayed(() -> {
            word.animate().alpha(1f).translationY(0).setDuration(420)
                    .setInterpolator(Anim.SETTLE).start();
            main.postDelayed(() -> tag.animate().alpha(1f).translationY(0)
                    .setDuration(380).setInterpolator(Anim.EASE).start(), 120);
        }, 520);

        main.postDelayed(this::go, TOTAL_MS);
    }

    private void go() {
        startActivity(new Intent(this, MainActivity.class));
        // Cross-fade out rather than a hard cut; the settings screen fades in.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override public void onBackPressed() {
        // The splash is not a place to go back to.
        main.removeCallbacksAndMessages(null);
        go();
    }

    private void goEdgeToEdge() {
        android.view.Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams a = w.getAttributes();
            a.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(a);
        }
        w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
    }

    private void applyInsets(View root, LinearLayout content) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(0, top, 0, bottom);
            return insets;
        });
    }

    /**
     * The mark: the app's pulse trace drawing itself left to right.
     *
     * Drawn with a PathMeasure so the line can be revealed progressively - a
     * stroke that grows along its own length, which is what reads as "a signal
     * arriving" rather than a shape fading in.
     */
    private static final class TraceView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private PathMeasure measure;
        private float len;
        private float t;          // 0..1 reveal
        private final float density;
        /** Stops the reveal ticker from re-posting once the view is gone. */
        private boolean detached;

        TraceView(android.content.Context c) {
            super(c);
            density = getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4f * density);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(Theme.SERIES[0]);
        }

        void start() {
            postOnAnimation(new Runnable() {
                long t0 = 0;
                @Override public void run() {
                    if (detached) return;
                    if (t0 == 0) t0 = System.currentTimeMillis();
                    long dt = System.currentTimeMillis() - t0;
                    t = Math.min(1f, dt / 900f);
                    invalidate();
                    if (t < 1f) postOnAnimation(this);
                }
            });
        }

        /** Stop the reveal if the splash is torn down mid-animation. */
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            detached = true;
            removeCallbacks(null);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            path.reset();
            float mid = h / 2f;
            float pad = 4f * density;
            float x0 = pad, x1 = w - pad;
            float step = (x1 - x0) / 6f;
            path.moveTo(x0, mid);
            path.lineTo(x0 + step, mid);
            path.lineTo(x0 + step * 1.6f, mid + h * 0.28f);
            path.lineTo(x0 + step * 2.6f, mid - h * 0.32f);
            path.lineTo(x0 + step * 3.6f, mid + h * 0.18f);
            path.lineTo(x0 + step * 4.4f, mid);
            path.lineTo(x1, mid);
            measure = new PathMeasure(path, false);
            len = measure.getLength();
        }

        @Override protected void onDraw(Canvas cv) {
            if (measure == null || len <= 0) return;
            Path part = new Path();
            measure.getSegment(0, len * t, part, true);
            cv.drawPath(part, line);
        }
    }
}
