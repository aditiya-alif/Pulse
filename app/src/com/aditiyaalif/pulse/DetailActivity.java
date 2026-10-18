// language: Java, file: DetailActivity.java, target: Android 16 (API 36)
// One saved recording, opened from the history list.
//
// Three boxes, as the brief asks:
//   1. the numbers that decide whether a session was good - 1% low, average,
//      max frame rate, average and peak wattage and temperature, and the rest;
//   2. curves - a pair plot the reader builds (two metrics, one per axis) and a
//      single plot against a real axis;
//   3. the analysis the raw numbers do not state: the frame-rate and frame-time
//      distributions, and the drift of temperature and battery.
//
// The curves box used to draw six metrics on one shared scale with no axes at
// all, which meant a line's height said nothing a reader could put a number to.
// It is now a pair: each line is named in its own colour over the axis it is read
// against, both axes are numbered, and the bottom carries time. Everything a
// reader needs to read a value off the plot is on the plot.
//
// Extends Screen, so the cards, buttons and the sheet it opens are the same
// vocabulary every other screen uses - one design, not two.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DetailActivity extends Screen {
    static final String EXTRA_ID = "id";

    private Recording rec;

    /** Metrics with at least two real readings, so a curve can actually be drawn. */
    private final List<Metric> available = new ArrayList<>();
    /** Which two metrics the pair plot shows: indices into {@link #available}. */
    private final int[] pairSlots = { 0, 1 };
    /** The metric the single plot shows. */
    private String singleSel;

    private ChartView chart;
    private ChartView chartSingle;
    private LinearLayout slotHost;
    private LinearLayout singleTabs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent().getStringExtra(EXTRA_ID);
        rec = id == null ? null : new Store(this).get(id);
        if (rec == null) { finish(); return; }
        LinearLayout col = scaffold();
        addBackBar(null);
        col.addView(header());
        col.addView(boxStats());
        col.addView(boxCurves());
        col.addView(boxAnalysis());
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        AppIcon.Info info = AppIcon.get(this, rec.pkg);
        if (info.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(info.icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
            row.addView(iv, ilp);
        }

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.dp(this, 12);
        TextView name = text(info.label.isEmpty() ? rec.pkg : info.label, 20, Theme.TEXT, Typeface.BOLD);
        names.addView(name);
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US);
        names.addView(text(f.format(new java.util.Date(rec.startedAt))
                + "  ·  " + fmtDur(rec.durationMs()), 12, Theme.MUTED, Typeface.NORMAL));
        row.addView(names, nlp);

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(this, 16);
        row.setLayoutParams(hlp);
        return row;
    }

    // -------------------------------------------------------------- box one

    private View boxStats() {
        LinearLayout card = card("Summary");

        // Frame rate first - it is what the capture is usually about.
        card.addView(groupLabel("FPS"));
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        addStat(fpsRow, "1% low", num(rec.fps1Low, 0), Theme.SERIES[4]);
        addStat(fpsRow, "0.1% low", num(rec.fps01Low, 0), Theme.SERIES[4]);
        addStat(fpsRow, "average", num(rec.fpsAvg, 1), Theme.SERIES[4]);
        addStat(fpsRow, "max", num(rec.fpsMax, 0), Theme.SERIES[4]);
        card.addView(fpsRow);

        card.addView(divider());
        card.addView(groupLabel("Power & Temperature"));
        LinearLayout pwr = new LinearLayout(this);
        pwr.setOrientation(LinearLayout.HORIZONTAL);
        addStat(pwr, "W avg", num(rec.wattAvg, 2), Theme.SERIES[6]);
        addStat(pwr, "W peak", num(rec.wattPeak, 2), Theme.SERIES[6]);
        addStat(pwr, "CPU avg", num(rec.tempCpuAvg, 1) + "°", Theme.SERIES[2]);
        addStat(pwr, "CPU max", num(rec.tempCpuMax, 1) + "°", Theme.SERIES[2]);
        card.addView(pwr);

        LinearLayout pwr2 = new LinearLayout(this);
        pwr2.setOrientation(LinearLayout.HORIZONTAL);
        addStat(pwr2, "BAT avg", num(rec.tempBatAvg, 1) + "°", Theme.SERIES[3]);
        addStat(pwr2, "BAT max", num(rec.tempBatMax, 1) + "°", Theme.SERIES[3]);
        addStat(pwr2, "CPU load", num(rec.cpuAvg, 0) + "%", Theme.SERIES[0]);
        addStat(pwr2, "RAM avg", num(rec.memAvgGb, 1) + "G", Theme.SERIES[1]);
        card.addView(pwr2, statRowParams());

        // Battery drain and GPU, when the capture has them.
        LinearLayout pwr3 = new LinearLayout(this);
        pwr3.setOrientation(LinearLayout.HORIZONTAL);
        addStat(pwr3, "RAM max", num(rec.memMaxGb, 1) + "G", Theme.SERIES[1]);
        addStat(pwr3, "CPU max", num(rec.cpuMax, 0) + "%", Theme.SERIES[0]);
        addStat(pwr3, "Battery", rec.battDrop() > 0 ? ("-" + rec.battDrop() + "%")
                : (rec.battDrop() < 0 ? "+" + (-rec.battDrop()) + "%" : "0%"),
                Theme.SERIES[7]);
        float gpuAvg = gpuAverage();
        addStat(pwr3, "GPU avg", Float.isNaN(gpuAvg) ? "--" : num(gpuAvg, 0) + "%",
                Theme.SERIES[5]);
        card.addView(pwr3, statRowParams());

        card.addView(divider());
        card.addView(groupLabel("Misc"));
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        addStat(meta, "Duration", fmtDur(rec.durationMs()), Theme.MUTED);
        addStat(meta, "Active", fmtDur(rec.activeMs), Theme.MUTED);
        addStat(meta, "Samples", String.valueOf(rec.samples.size()), Theme.MUTED);
        addStat(meta, "Pauses", rec.pauses + "×", Theme.MUTED);
        card.addView(meta);

        return card;
    }

    private float gpuAverage() {
        double sum = 0;
        int n = 0;
        for (Sample s : rec.samples) if (!Float.isNaN(s.gpu)) { sum += s.gpu; n++; }
        return n == 0 ? Float.NaN : (float) (sum / n);
    }

    /**
     * Row params for a stat row that follows another: a gap above it.
     *
     * Two stat rows stacked directly on each other read as one four-line block;
     * the margin is what makes them two rows.
     */
    private LinearLayout.LayoutParams statRowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        return lp;
    }

    /**
     * One stat cell: a value over its label, centred.
     *
     * Centred because a row of these reads as a table, and a table whose columns
     * are all flush left leaves a ragged gap on the right. The card's own headings
     * ("FPS", "Misc") stay left-aligned - they are headings, not table cells.
     */
    private void addStat(LinearLayout row, String label, String value, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        c.setLayoutParams(lp);

        TextView v = text(value, 17, Theme.TEXT, Typeface.BOLD);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView l = text(label, 10, Theme.MUTED, Typeface.NORMAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(v);
        // A gap between the value and its label: with the two set tight, a row of
        // cells read as one cramped block.
        LinearLayout.LayoutParams llp = wrap();
        llp.topMargin = Ui.dp(this, 3);
        c.addView(l, llp);
        row.addView(c);
    }

    // -------------------------------------------------------------- box two

    private View boxCurves() {
        computeAvailable();
        LinearLayout card = card("Curves");

        if (available.size() < 2) {
            card.addView(text("This recording does not have enough readings to draw "
                    + "curves.", 12, Theme.MUTED, Typeface.NORMAL));
            return card;
        }

        // ---- the pair plot: two metrics, one on each axis ----
        card.addView(groupLabel("Compare two"));
        card.addView(text("The first metric is read on the left axis, the second on "
                + "the right. Tap a tile to change it.", 11.5f, Theme.MUTED,
                Typeface.NORMAL));

        slotHost = row();
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shlp.topMargin = Ui.dp(this, 10);
        slotHost.setLayoutParams(shlp);
        card.addView(slotHost);
        buildSlots();

        chart = new ChartView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190));
        clp.topMargin = Ui.dp(this, 10);
        card.addView(chart, clp);
        renderPair();

        card.addView(divider());

        // ---- the single plot: one metric against its own real axis ----
        card.addView(groupLabel("One metric"));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        singleTabs = new LinearLayout(this);
        singleTabs.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(singleTabs);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = Ui.dp(this, 8);
        card.addView(hsv, tlp);

        chartSingle = new ChartView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 160));
        card.addView(chartSingle, slp);
        renderSingle();

        return card;
    }

    /** Keep only the metrics this recording actually has two readings of. */
    private void computeAvailable() {
        available.clear();
        for (Metric m : Metric.ALL) {
            if (m.key.equals("gpu") && !hasGpu()) continue;
            float[] v = values(m);
            int n = 0;
            for (float x : v) if (!Float.isNaN(x)) n++;
            if (n >= 2) available.add(m);
        }
        if (available.isEmpty()) return;
        if (available.size() >= 2) {
            // Sensible defaults: frame rate against CPU load is the pair a capture
            // is usually about. Fall back to the first two when one is absent.
            int fps = indexOf("fps"), cpu = indexOf("cpu");
            pairSlots[0] = fps >= 0 ? fps : 0;
            pairSlots[1] = (cpu >= 0 && cpu != pairSlots[0]) ? cpu
                    : (pairSlots[0] == 0 ? 1 : 0);
        }
        if (singleSel == null || Metric.byKey(singleSel) == null
                || !available.contains(Metric.byKey(singleSel))) {
            singleSel = available.get(0).key;
        }
    }

    private int indexOf(String key) {
        for (int i = 0; i < available.size(); i++) {
            if (available.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    private boolean hasGpu() {
        for (Sample s : rec.samples) if (!Float.isNaN(s.gpu)) return true;
        return false;
    }

    // ------------------------------------------------------------ pair plot

    /**
     * The two axis pickers: fixed-width tiles, one at each edge.
     *
     * The left tile sits at the left and the right tile at the right, matching the
     * axis each one names - a reader's eye goes straight to the side of the plot
     * the tile belongs to. Both are the SAME fixed width, derived from the
     * "Left axis" label, so neither resizes as the metric name changes and the two
     * never fight over the row. The tap opens the picker, so there is no separate
     * "Change" button saying what the tile already says.
     */
    private void buildSlots() {
        slotHost.removeAllViews();

        TextView probe = text("Left axis", 10.5f, Theme.MUTED, Typeface.NORMAL);
        float base = probe.getPaint().measureText("Left axis") + 2f * Ui.dp(this, 12);
        int fixedW = Math.round(base * 1.5f);

        slotHost.addView(slotTile(0, "Left axis", fixedW));

        View spacer = new View(this);
        slotHost.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

        slotHost.addView(slotTile(1, "Right axis", fixedW));
    }

    private View slotTile(int slot, String axis, int fixedW) {
        Metric m = available.get(pairSlots[slot]);
        LinearLayout tile = column();
        tile.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        Ui.pad(tile, this, 8, 10, 8, 10);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setLayoutParams(new LinearLayout.LayoutParams(
                fixedW, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView a = text(axis, 10.5f, Theme.MUTED, Typeface.NORMAL);
        a.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.addView(a);

        LinearLayout name = row();
        name.setGravity(Gravity.CENTER);
        View dot = new View(this);
        dot.setBackground(Ui.round(m.color(), Ui.dp(this, 4)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 7), Ui.dp(this, 7));
        dlp.rightMargin = Ui.dp(this, 5);
        name.addView(dot, dlp);
        // The metric name is set small and pinned to one line: the tile's width is
        // fixed, so a long label must ellipsize rather than widen the tile.
        TextView nm = text(m.shortLabel, 10.5f, m.color(), Typeface.BOLD);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.addView(nm);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = Ui.dp(this, 3);
        tile.addView(name, nlp);

        tile.setOnClickListener(v -> {
            Anim.bounce(v);
            pickMetric(slot, axis);
        });
        return tile;
    }

    private void pickMetric(int slot, String axis) {
        Sheet s = sheet("Pick the " + axis.toLowerCase(Locale.US) + " metric");
        for (Metric m : available) {
            // The other axis is already using this metric; a plot of a line against
            // itself says nothing, so it is left out of the list.
            if (m == available.get(pairSlots[1 - slot])) continue;
            s.action(m.shortLabel, m.color(), () -> {
                pairSlots[slot] = available.indexOf(m);
                buildSlots();
                renderPair();
            });
        }
        s.note("The line's colour matches the axis name it is drawn against.");
    }

    private void renderPair() {
        if (chart == null || available.size() < 2) return;
        List<ChartView.Series> list = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Metric m = available.get(pairSlots[i]);
            list.add(new ChartView.Series(m.shortLabel, m.color(), values(m),
                    m.unit, m.decimals, m.includeZero()));
        }
        chart.setCombined(list);
        chart.setXLabels(null, null, fmtDur(rec.spanMs()));
    }

    // ---------------------------------------------------------- single plot

    private void renderSingle() {
        if (chartSingle == null) return;
        Metric m = Metric.byKey(singleSel);
        if (m == null) { chartSingle.clear(); return; }
        chartSingle.setSingle(new ChartView.Series(m.shortLabel, m.color(), values(m),
                m.unit, m.decimals, m.includeZero()));
        chartSingle.setXLabels(null, null, fmtDur(rec.spanMs()));
        buildSingleTabs();
    }

    private void buildSingleTabs() {
        singleTabs.removeAllViews();
        for (Metric m : available) {
            boolean on = m.key.equals(singleSel);
            TextView t = text(m.shortLabel, 11.5f, 0xFFFFFFFF, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.round(on ? m.color() : Theme.CARD_HI, Ui.dp(this, 13)));
            t.setTextColor(on ? 0xFFFFFFFF : Theme.TEXT_DIM);
            t.setPadding(Ui.dp(this, 11), 0, Ui.dp(this, 11), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 26));
            lp.rightMargin = Ui.dp(this, 6);
            t.setOnClickListener(v -> {
                if (m.key.equals(singleSel)) return;
                singleSel = m.key;
                Anim.bounce(v);
                renderSingle();
            });
            singleTabs.addView(t, lp);
        }
    }

    private float[] values(Metric m) {
        float[] v = new float[rec.samples.size()];
        for (int i = 0; i < v.length; i++) {
            Sample s = rec.samples.get(i);
            switch (m.key) {
                case "cpu":   v[i] = s.cpuPct; break;
                case "mem":   v[i] = s.memUsedKb / 1048576f; break;
                case "tcpu":  v[i] = s.cpuTemp; break;
                case "tbat":  v[i] = s.batTemp; break;
                case "fps":   v[i] = s.fps < 0 ? Float.NaN : s.fps; break;
                case "gpu":   v[i] = s.gpu; break;
                case "w":     v[i] = s.watts; break;
                case "batt":  v[i] = s.level >= 0 ? s.level : Float.NaN; break;
                default:      v[i] = Float.NaN;
            }
        }
        return v;
    }

    // ------------------------------------------------------------ box three

    private View boxAnalysis() {
        LinearLayout card = card("Analysis");

        card.addView(groupLabel("Frame rate distribution"));
        card.addView(text("Bar height is how many samples fell in that frame-rate band. "
                + "Left is slow, right is smooth - so a smooth session piles up on the "
                + "right, and a stretch of stutter leaves a bar on the left.",
                11.5f, Theme.MUTED, Typeface.NORMAL));
        HistView hist = new HistView(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 120));
        hlp.topMargin = Ui.dp(this, 10);
        card.addView(hist, hlp);
        hist.setSamples(rec.samples, 24);

        card.addView(divider());

        // Frame time: the same data read the way a stutter is actually felt. A
        // frame rate says how many frames a second; the frame time says how long
        // ONE frame took, which is what a dropped frame is.
        card.addView(groupLabel("Frame time distribution"));
        card.addView(text("The same session, in milliseconds per frame. Left is fast, "
                + "right is slow - the opposite direction from the chart above, because "
                + "here a bigger number is a longer frame. The marker is the average.",
                11.5f, Theme.MUTED, Typeface.NORMAL));
        HistView ftime = new HistView(this);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 120));
        flp.topMargin = Ui.dp(this, 10);
        card.addView(ftime, flp);
        ftime.setFrameTimes(rec.samples, 24);

        card.addView(divider());

        // Auto observations: the facts a reader would otherwise have to derive.
        card.addView(groupLabel("Notes"));
        LinearLayout notes = new LinearLayout(this);
        notes.setOrientation(LinearLayout.VERTICAL);
        card.addView(notes);
        for (String line : observations()) {
            TextView t = text("·  " + line, 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
            t.setLineSpacing(Ui.dp(this, 2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 6);
            notes.addView(t, lp);
        }
        return card;
    }

    /**
     * Plain-language observations derived from the capture.
     *
     * Each is a fact the numbers already contain but do not state: where the
     * frame rate bottomed out, how much the temperature climbed, whether the
     * capture was interrupted. No verdict is invented - every line is arithmetic
     * over the samples.
     */
    private List<String> observations() {
        List<String> out = new ArrayList<>();

        if (!Float.isNaN(rec.fpsAvg) && !Float.isNaN(rec.fps1Low)) {
            float drop = rec.fpsAvg - rec.fps1Low;
            float pct = rec.fpsAvg > 0 ? drop / rec.fpsAvg * 100f : 0;
            out.add(String.format(Locale.US,
                    "Lowest frame rate (1%% low) %.0f fps, %.0f%% below the %.1f fps average.",
                    rec.fps1Low, pct, rec.fpsAvg));
        }

        // The worst stretch: the sample with the lowest frame rate, and when.
        int worstIdx = -1;
        float worst = Float.MAX_VALUE;
        for (int i = 0; i < rec.samples.size(); i++) {
            float f = rec.samples.get(i).fps;
            if (f >= 0 && f < worst) { worst = f; worstIdx = i; }
        }
        if (worstIdx >= 0) {
            long atMs = (rec.samples.get(worstIdx).t - rec.samples.get(0).t) / 1_000_000;
            out.add(String.format(Locale.US,
                    "Worst point %d fps at second %d of the recording.",
                    Math.round(worst), atMs / 1000));
        }

        if (!Float.isNaN(rec.tempCpuAvg) && !Float.isNaN(rec.tempCpuMax)) {
            float rise = rec.tempCpuMax - rec.samples.get(0).cpuTemp;
            if (!Float.isNaN(rise) && Math.abs(rise) >= 1f) {
                out.add(String.format(Locale.US,
                        "CPU temperature %s %.1f°C during the recording (start %.1f°C, peak %.1f°C).",
                        rise >= 0 ? "rose" : "fell", Math.abs(rise),
                        rec.samples.get(0).cpuTemp, rec.tempCpuMax));
            }
        }

        if (rec.battStart >= 0 && rec.battEnd >= 0) {
            int drop = rec.battDrop();
            if (drop > 0) {
                double hours = rec.durationMs() / 3600000.0;
                String rate = hours > 0.01
                        ? String.format(Locale.US, " (%.0f%%/h)", drop / hours) : "";
                out.add("Battery dropped " + drop + "% during the recording" + rate + ".");
            } else if (drop < 0) {
                out.add("Battery rose " + (-drop) + "% — the device was charging.");
            }
        }

        if (rec.pauses > 0) {
            out.add("Recording paused " + rec.pauses + "× because focus left "
                    + "this app; paused time is not counted.");
        } else {
            out.add("Focus never left this app during the recording.");
        }

        out.add("Active duration " + fmtDur(rec.activeMs) + " (" + rec.samples.size()
                + " samples, " + intervalText() + " interval).");

        return out;
    }

    /**
     * The real sampling interval, measured from the capture's own timestamps.
     *
     * This is read rather than asserted: the script's nominal cadence and its real
     * one differ (device work runs between sleeps), and a hardcoded figure goes
     * stale the moment the script changes. The median gap is used so one long
     * paused stretch - which shows up as a single huge gap - cannot drag it.
     */
    private String intervalText() {
        if (rec.samples.size() < 2) return "unknown";
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < rec.samples.size(); i++) {
            long d = rec.samples.get(i).t - rec.samples.get(i - 1).t;
            if (d > 0) gaps.add(d);
        }
        if (gaps.isEmpty()) return "unknown";
        java.util.Collections.sort(gaps);
        long medNs = gaps.get(gaps.size() / 2);
        float s = medNs / 1_000_000_000f;
        return String.format(Locale.US, "%.1f second", s);
    }

    // -------------------------------------------------------------- helpers

    private static String num(float v, int decimals) {
        if (Float.isNaN(v)) return "--";
        return String.format(Locale.US, "%." + decimals + "f", v);
    }
}
