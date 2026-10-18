// language: Java, file: MonitorPanel.java, target: Android 16 (API 36)
// The live monitor, opened by LONG-PRESSING the overlay bar.
//
// It answers the brief's "monitoring langsung": which app is being watched (its
// icon and name, from the sampler's focused package), and the history as curves.
// The pair view is the primary tab - frame rate on the left axis, CPU load on the
// right, each line named over the axis it is read against. The other tabs show a
// single metric against its own real axis.
//
// The panel is its own overlay window: a modal card over a dimmed backdrop,
// sliding up with an overshoot. Tapping the backdrop closes it.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class MonitorPanel {
    /** How many recent samples a curve shows (120 at 0.5 s = one minute). */
    private static final int WINDOW = 120;

    /** The recording actions the panel exposes, implemented by the service. */
    interface Control {
        boolean recording();
        boolean paused();
        long activeMs();
        void toggle();
    }

    private final Context ctx;
    private final Sampler sampler;
    private final Control control;
    private final Runnable onClose;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private View card;
    private ChartView chart;
    private TextView appName;
    private ImageView appIcon;
    private TextView status;
    private TextView recBtn;
    private LinearLayout tabs;

    private final List<String> tabKeys = new ArrayList<>();   // "all" then metric keys
    private String selected = "all";
    private boolean gpuShown;

    MonitorPanel(Context ctx, Sampler sampler, Control control, Runnable onClose) {
        this.ctx = ctx;
        this.sampler = sampler;
        this.control = control;
        this.onClose = onClose;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    // -------------------------------------------------------------- lifecycle

    void show() {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(0x99000000);
        root.setOnClickListener(v -> dismiss());

        card = buildCard();
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        root.addView(card, clp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        try { wm.addView(root, lp); }
        catch (Throwable t) { Diag.log("panel add failed", t); return; }

        // Arrive: dim fades in, card rises with a small overshoot.
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(180).start();
        card.setTranslationY(Ui.dp(ctx, 60));
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().translationY(0).scaleX(1f).scaleY(1f)
                .setDuration(340).setInterpolator(Anim.SETTLE).start();

        refresh(sampler.snapshot(), null);
    }

    void dismiss() {
        if (root == null) return;
        View r = root;
        root = null;
        if (card != null) {
            card.animate().translationY(Ui.dp(ctx, 60)).alpha(0f)
                    .setDuration(180).setInterpolator(Anim.EASE).start();
        }
        r.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    try { wm.removeView(r); } catch (Throwable ignored) { }
                }).start();
        if (onClose != null) onClose.run();
    }

    void onSample(Sample s, List<Sample> history) { refresh(history, s); }

    /** Called by the service when a sample was kept or dropped by the recorder. */
    void onRecording(Recorder r, boolean kept) {
        main.post(this::syncRecBtn);
    }

    void onLink(boolean up, String msg) {
        main.post(() -> { if (status != null) status.setText(msg); });
    }

    /** Reflect the recorder state in the header button. */
    private void syncRecBtn() {
        if (recBtn == null || control == null) return;
        if (!control.recording()) {
            recBtn.setText("●  Start recording");
            recBtn.setTextColor(Theme.TEXT);
            recBtn.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(ctx, 14)));
        } else {
            long sec = control.activeMs() / 1000;
            String t = String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60);
            recBtn.setText((control.paused() ? "❚❚  Paused · " : "■  Stop · ") + t);
            recBtn.setTextColor(0xFFFFFFFF);
            recBtn.setBackground(Ui.round(control.paused() ? Theme.WARNING : Theme.CRITICAL,
                    Ui.dp(ctx, 14)));
        }
    }

    // ----------------------------------------------------------------- build

    private View buildCard() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Ui.roundStroke(Theme.CARD, Ui.dp(ctx, 26),
                Theme.HAIRLINE, Ui.dp(ctx, 1)));
        Ui.pad(col, ctx, 16, 14, 16, 14);

        // grabber
        View handle = new View(ctx);
        handle.setBackground(Ui.round(0x33FFFFFF, Ui.dp(ctx, 2)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = Ui.dp(ctx, 12);
        col.addView(handle, hlp);

        // header: app icon + name, and link status
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        appIcon = new ImageView(ctx);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(ctx, 30), Ui.dp(ctx, 30));
        appIcon.setVisibility(View.GONE);
        head.addView(appIcon, ilp);

        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(ctx, 10);
        appName = new TextView(ctx);
        appName.setText("Monitoring…");
        appName.setTextColor(Theme.TEXT);
        appName.setTextSize(15);
        appName.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        status = new TextView(ctx);
        status.setText("Waiting for data");
        status.setTextColor(Theme.MUTED);
        status.setTextSize(11);
        names.addView(appName);
        names.addView(status);
        head.addView(names, nlp);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextColor(Theme.TEXT_DIM);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(ctx, 14)));
        close.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 30), Ui.dp(ctx, 30)));
        close.setOnClickListener(v -> dismiss());
        head.addView(close);
        col.addView(head);

        // Record control: the same action the bar's tap performs, reachable from
        // here too. Its label follows the recorder's state.
        recBtn = new TextView(ctx);
        recBtn.setGravity(Gravity.CENTER);
        recBtn.setTextSize(13);
        recBtn.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        recBtn.setOnClickListener(v -> {
            Anim.bounce(v);
            control.toggle();
            syncRecBtn();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 38));
        rlp.topMargin = Ui.dp(ctx, 14);
        col.addView(recBtn, rlp);
        syncRecBtn();

        // segmented control: eight chips can outgrow a narrow screen, so it scrolls
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(ctx, 14);
        col.addView(hsv, tlp);

        tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(tabs, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuildTabs();

        // chart
        chart = new ChartView(ctx);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 168));
        chp.topMargin = Ui.dp(ctx, 10);
        col.addView(chart, chp);

        return col;
    }

    private void rebuildTabs() {
        tabKeys.clear();
        tabKeys.add("all");
        for (Metric m : Metric.ALL) {
            if (m.key.equals("gpu") && !gpuShown) continue;
            tabKeys.add(m.key);
        }
        if (!tabKeys.contains(selected)) selected = "all";
        buildTabs();
    }

    private void buildTabs() {
        tabs.removeAllViews();
        for (String key : tabKeys) {
            boolean on = key.equals(selected);
            TextView t = new TextView(ctx);
            String label = key.equals("all") ? "FPS + CPU" : Metric.byKey(key).shortLabel;
            t.setText(label);
            t.setTextSize(11.5f);
            t.setGravity(Gravity.CENTER);
            int color = key.equals("all") ? Theme.ACCENT : Metric.byKey(key).color();
            t.setTextColor(on ? 0xFFFFFFFF : Theme.TEXT_DIM);
            t.setBackground(Ui.round(on ? color : Theme.CARD_HI, Ui.dp(ctx, 13)));
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 26));
            lp.rightMargin = Ui.dp(ctx, 6);
            t.setPadding(Ui.dp(ctx, 11), 0, Ui.dp(ctx, 11), 0);
            t.setOnClickListener(v -> {
                if (key.equals(selected)) return;
                selected = key;
                Anim.bounce(t);
                buildTabs();
                refresh(sampler.snapshot(), null);
            });
            tabs.addView(t, lp);
        }
    }

    // ---------------------------------------------------------------- render

    private void refresh(List<Sample> history, Sample latest) {
        main.post(() -> {
            if (root == null || chart == null) return;

            // Which app is being watched: the focused package the sampler reports.
            Sample s = latest;
            if (s == null) {
                for (int i = history.size() - 1; i >= 0; i--) {
                    if (!history.get(i).pkg.isEmpty()) { s = history.get(i); break; }
                }
            }
            if (s != null && !s.pkg.isEmpty()) {
                AppIcon.Info info = AppIcon.get(ctx, s.pkg);
                appName.setText(info.label.isEmpty() ? s.pkg : info.label);
                if (info.icon != null) {
                    appIcon.setImageDrawable(info.icon);
                    appIcon.setVisibility(View.VISIBLE);
                } else {
                    appIcon.setVisibility(View.GONE);
                }
            }
            syncRecBtn();

            if (history.size() < 2) {
                chart.clear();
                return;
            }
            List<Sample> win = tail(history, WINDOW);

            // GPU's tab appears the first time a reading arrives, and only then.
            boolean gpuNow = false;
            for (Sample x : win) if (!Float.isNaN(x.gpu)) { gpuNow = true; break; }
            if (gpuNow != gpuShown) {
                gpuShown = gpuNow;
                rebuildTabs();
            }

            if ("all".equals(selected)) {
                List<ChartView.Series> list = new ArrayList<>();
                // The pair: frame rate on the left axis, CPU load on the right.
                // Two lines is what the dual-axis plot can carry honestly; the
                // rest are one tap away in their own tabs.
                for (String key : new String[]{"fps", "cpu"}) {
                    Metric m = Metric.byKey(key);
                    if (m == null) continue;
                    float[] v = values(win, m);
                    int n = 0;
                    for (float x : v) if (!Float.isNaN(x)) n++;
                    if (n < 2) continue;
                    list.add(new ChartView.Series(m.shortLabel, m.color(), v,
                            m.unit, m.decimals, false));
                }
                if (list.size() < 2) chart.clear();
                else {
                    chart.setCombined(list);
                    chart.setXLabels("-" + (WINDOW / 2) + " s", null, "now");
                }
            } else {
                Metric m = Metric.byKey(selected);
                if (m == null) { chart.clear(); return; }
                float[] v = values(win, m);
                if (v.length < 2) { chart.clear(); return; }
                chart.setSingle(new ChartView.Series(m.shortLabel, m.color(), v,
                        m.unit, m.decimals, m.key.equals("w")));
                chart.setXLabels("-" + (WINDOW / 2) + " s", null, "now");
            }
        });
    }

    private static List<Sample> tail(List<Sample> all, int n) {
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }

    /** Pull one metric's series out of the history, NaN where it is unavailable. */
    private static float[] values(List<Sample> win, Metric m) {
        float[] v = new float[win.size()];
        for (int i = 0; i < win.size(); i++) {
            Sample s = win.get(i);
            switch (m.key) {
                case "cpu":   v[i] = s.valid ? s.cpuPct : Float.NaN; break;
                case "mem":   v[i] = s.memTotalKb > 0
                        ? s.memUsedKb / 1048576f : Float.NaN; break;
                case "tcpu":  v[i] = s.cpuTemp; break;
                case "tbat":  v[i] = s.batTemp; break;
                case "fps":   v[i] = s.fps < 0 ? Float.NaN : s.fps; break;
                case "gpu":   v[i] = s.gpu; break;
                case "w":     v[i] = s.voltMv > 0 ? s.watts : Float.NaN; break;
                case "batt":  v[i] = s.level >= 0 ? s.level : Float.NaN; break;
                default:      v[i] = Float.NaN;
            }
        }
        return v;
    }
}
