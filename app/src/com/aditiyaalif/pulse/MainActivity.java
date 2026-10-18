// language: Java, file: MainActivity.java, target: Android 16 (API 36)
// Home. The welcome screen.
//
// Three things and nothing else:
//   a live readout of the device, drawn as gauges rather than a wall of numbers;
//   a live curve of every CPU core's clock and the GPU / skin / amplifier
//     temperatures - the "what is the chip doing right now" view;
//   the overlay on/off control, because that is the one action used every day;
//   three icon buttons in the corner for everything else.
//
// The modules and the recordings each live on their own screen, reached from those
// buttons or the tools screen - this one stays a welcome.
package com.aditiyaalif.pulse;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Screen implements Live.Sink {
    /**
     * How many samples the live curves keep.
     *
     * 30 points at the sampler's ~1.3 s cadence is about 40 seconds of history.
     * That is deliberately short: at 60 the points packed so tightly that eight
     * core curves read as a solid band, and a shorter window gives each sample
     * more width so the shape is legible. This is a "right now" view, not a log.
     */
    private static final int HIST = 30;

    private Prefs prefs;

    private View overlayButton;
    private Ring ring;
    private Meter mCpuT, mBatT, mBat, mW, mFps;
    private CoreGridView coreGrid;
    private TempView tempView;
    private TextView statusLine;

    /** Rolling history for the two charts, oldest first. */
    private final ArrayDeque<Sample> hist = new ArrayDeque<>();

    /** Hosts that hold a shimmer until the first sample arrives. */
    private LinearLayout readoutHost, chartHost;
    /** The real content inside each host, hidden while its shimmer shows. */
    private LinearLayout readoutContent, chartContent;
    private boolean gaugesReady;

    /** A sampler of our own, started only when the overlay is not already one. */
    private Sampler local;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        build();
        askNotificationPermission();
        // A device that has never been paired has nothing to show and no way to
        // get anything - so the pairing prompt is put in front of the user rather
        // than left for them to find in Settings.
        main.postDelayed(this::promptPairingIfNeeded, 700);
        // On every launch, make sure the wireless-debugging toggle is ON before the
        // connect runs: a reboot can leave it off, and then a perfectly paired
        // device has no port to reach. Best effort, and silent when already on.
        new Thread(() -> {
            if (prefs.paired()) Wireless.ensureWirelessEnabled(this);
        }, "pulse-ensure-adb").start();
    }

    @Override protected void onResume() {
        super.onResume();
        Live.add(this);
        syncOverlayToggle();
        // Show the last known values at once, so the screen does not flash "--"
        // while it waits for the next sample. They refresh as soon as one arrives.
        if (Live.last != null) {
            gaugesReady = true;
            onSample(Live.last);
        } else {
            // Nothing cached yet: cover BOTH boxes with their placeholders at the
            // same time. They share one flag, so they appear and disappear together
            // rather than one after the other.
            showGaugeLoading();
        }
        refreshStatus();
        startLocalSamplerIfNeeded();
        main.postDelayed(this::warnPendingHolds, 600);
    }

    /**
     * Cover both home boxes with shape-matched placeholders.
     *
     * The real content in each host is hidden, not removed, so the placeholder sits
     * exactly where the gauges will be - appending it below left the empty gauges
     * on show with the shimmer underneath.
     */
    private void showGaugeLoading() {
        readoutContent.setVisibility(View.GONE);
        chartContent.setVisibility(View.GONE);
        showLoading(readoutHost, Shimmer.readout(this));
        showLoading(chartHost, Shimmer.chart(this));
    }

    @Override protected void onPause() {
        super.onPause();
        Live.remove(this);
        stopLocalSampler();
    }

    // ----------------------------------------------------------------- build

    private void build() {
        LinearLayout col = scaffold();

        // Header: Tools on the left; overlay, recordings and settings on the right.
        LinearLayout header = row();
        header.addView(iconButton(Icon.SLIDERS, v -> open(HubActivity.class)));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout nav = row();
        overlayButton = iconButton(Icon.OVERLAY, v -> toggleOverlay());
        nav.addView(overlayButton);
        nav.addView(gap(10));
        nav.addView(iconButton(Icon.CHART, v -> open(RecordingsActivity.class)));
        nav.addView(gap(10));
        nav.addView(iconButton(Icon.GEAR, v -> open(SettingsActivity.class)));
        header.addView(nav);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(this, 18);
        col.addView(header, hlp);

        addHeading("Pulse", "Your device at a glance.");

        // Box one: the readout. A ring carrying two readings, then a column of bars.
        LinearLayout now = card(null);
        readoutHost = column();
        now.addView(readoutHost);

        // The real content lives in its own child, so the shimmer can be shown in
        // its place (hidden below) rather than appended after it.
        readoutContent = column();
        readoutContent.setVisibility(View.GONE);

        LinearLayout body = row();

        ring = new Ring(this);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 132), Ui.dp(this, 132));
        rlp.rightMargin = Ui.dp(this, 16);
        body.addView(ring, rlp);

        LinearLayout meters = column();
        mCpuT = meter(meters, Theme.SERIES[2]);
        mBatT = meter(meters, Theme.SERIES[3]);
        mBat  = meter(meters, Theme.SERIES[7]);
        mW    = meter(meters, Theme.SERIES[6]);
        mFps  = meter(meters, Theme.SERIES[4]);
        body.addView(meters, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        readoutContent.addView(body);
        // A key for the two things the ring shows. The bars label themselves, so
        // they are not repeated here.
        LinearLayout legend = Legend.build(this, 3,
                new int[]{ Theme.SERIES[0], Theme.SERIES[1] },
                new String[]{ "CPU %", "RAM" });
        LinearLayout.LayoutParams llp = wrap();
        llp.topMargin = Ui.dp(this, 14);
        readoutContent.addView(legend, llp);

        statusLine = text("", 11.5f, Theme.MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = Ui.dp(this, 10);
        readoutContent.addView(statusLine, slp);
        readoutHost.addView(readoutContent);
        col.addView(now);

        // Box two: the chip live - core clocks and the three temperatures, under
        // one heading (a box carries one title, so the two views share it and are
        // separated by an in-box label each).
        LinearLayout chip = card("CPU & Temperature");
        chartHost = column();
        chip.addView(chartHost);

        chartContent = column();
        chartContent.setVisibility(View.GONE);
        chartContent.addView(groupLabel("Core clocks"));
        coreGrid = new CoreGridView(this);
        // WRAP_CONTENT: the grid derives its own height from the width it is given,
        // so it is exactly as tall as its curves and leaves no empty band above the
        // divider that follows.
        chartContent.addView(coreGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The divider carries EQUAL margins top and bottom, and the label below it
        // gets no extra top margin - so the space above the line (to the last
        // curve) and the space below it (to the next label) are the same.
        View sep = divider();
        LinearLayout.LayoutParams sepLp = (LinearLayout.LayoutParams) sep.getLayoutParams();
        sepLp.topMargin = Ui.dp(this, 12);
        sepLp.bottomMargin = Ui.dp(this, 12);
        chartContent.addView(sep);

        View tempLabel = groupLabel("Temperature");
        ((LinearLayout.LayoutParams) tempLabel.getLayoutParams()).topMargin = 0;
        chartContent.addView(tempLabel);

        tempView = new TempView(this);
        chartContent.addView(tempView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 170)));
        chartHost.addView(chartContent);
        col.addView(chip);
    }

    /**
     * On a device that has never been paired, offer to pair right away.
     *
     * Without a pairing there is no shell, so every screen in the app is empty -
     * which reads as "broken" rather than "not set up". The prompt is put over the
     * home screen with the screen itself blurred behind it, so the user sees where
     * they are and what they are about to unlock, not a bare dialog on a blank page.
     *
     * Only when the app has never paired AND is not connected: a device that is
     * already connected has nothing to ask, and one that paired before reconnects
     * on its own.
     */
    private void promptPairingIfNeeded() {
        if (screen == null || isFinishing()) return;
        if (prefs.paired()) return;
        if (Wireless.connected(this)) return;
        showPairingPrompt();
    }

    private void showPairingPrompt() {
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xCC0D0D10);

        // BLUR THE SCREEN BEHIND THE PROMPT.
        //
        // The blur goes on the scrolling CONTENT, not on the root: the scrim is a
        // child of the root too, so blurring the root would blur the prompt's own
        // card. Blurring the content keeps the user oriented - they can see the
        // shape of the app they are unlocking - while making the card the only
        // thing in focus. RenderEffect needs API 31; on older builds the dark scrim
        // alone still separates the card from the page.
        final View behind = screen.getChildCount() > 0 ? screen.getChildAt(0) : null;
        if (Build.VERSION.SDK_INT >= 31 && behind != null) {
            try {
                behind.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        18f, 18f, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                Diag.log("blur unavailable", t);
            }
        }

        LinearLayout card = column();
        card.setBackground(Ui.roundStroke(Theme.CARD, Ui.dp(this, 24),
                Theme.HAIRLINE, Ui.dp(this, 1)));
        Ui.pad(card, this, 20, 20, 20, 18);

        card.addView(text("Pair this device", 18, Theme.TEXT, Typeface.BOLD));
        TextView body = text("Pulse reads the device over wireless debugging. "
                + "Turn on \"Wireless debugging\" in Settings > Developer options, open "
                + "\"Pair device with pairing code\", then type the 6 digits here. "
                + "The address is found automatically.", 12.5f, Theme.TEXT_DIM,
                Typeface.NORMAL);
        body.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        card.addView(body, blp);

        // Pairing comes first, and loopback is deliberately NOT offered here. The
        // command that switches to loopback travels over a working connection, so
        // with nothing paired there is nothing to send it over - an option here
        // could not work. Saying so is better than letting the user wonder where it
        // went.
        TextView loopNote = text("Once paired, you can switch to loopback mode in "
                + "Settings to keep working without Wi-Fi.", 11.5f, Theme.MUTED,
                Typeface.NORMAL);
        loopNote.setLineSpacing(Ui.dp(this, 2), 1f);
        LinearLayout.LayoutParams lnlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lnlp.topMargin = Ui.dp(this, 8);
        card.addView(loopNote, lnlp);

        final android.widget.EditText code = new android.widget.EditText(this);
        code.setHint("000000");
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        code.setTextColor(Theme.TEXT);
        code.setHintTextColor(Theme.MUTED);
        code.setTextSize(20);
        code.setLetterSpacing(0.3f);
        code.setGravity(Gravity.CENTER);
        code.setBackground(Ui.roundStroke(Theme.CARD_HI, Ui.dp(this, 14),
                Theme.HAIRLINE, Ui.dp(this, 1)));
        code.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 16);
        card.addView(code, clp);

        final TextView status = text("", 11.5f, Theme.MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = Ui.dp(this, 10);
        card.addView(status, slp);

        LinearLayout actions = row();
        LinearLayout.LayoutParams alp = wrap();
        alp.topMargin = Ui.dp(this, 16);
        actions.setLayoutParams(alp);

        final TextView later = button("Later", v -> {
            Anim.bounce(v);
            dismissPairing(scrim);
        });
        // Natural width, not a weighted half: weighting forced both buttons to the
        // same width, which cut off the longer label and made the row twitch when a
        // label changed. Later is fixed at its own size; Pair is sized to its
        // longest state below.
        actions.addView(later);

        // The button is created first and its behaviour attached after, so the
        // click handler can refer to the button itself (to disable it and change
        // its label) without tripping the "might not be initialized" rule.
        final TextView pair = pill("Pair", Theme.ACCENT, 0xFFFFFFFF, null);
        // "Pair" is the widest state it shows (the busy state is a single ellipsis),
        // so locking to it keeps the button from resizing when it flips.
        lockWidthToLongest(pair, "Pair");
        pair.setOnClickListener(v -> {
            String c = code.getText().toString().trim();
            if (c.length() < 6) { toast("Enter the 6-digit code"); return; }
            Anim.bounce(v);
            pair.setEnabled(false);
            pair.setText("…");
            status.setText("Finding the pairing port…");
            new Thread(() -> {
                Wireless.Result r = Wireless.pair(this, c);
                main.post(() -> {
                    if (!r.ok) {
                        pair.setEnabled(true);
                        pair.setText("Pair");
                        status.setText(r.output == null ? "Pairing failed" : r.output);
                        return;
                    }
                    status.setText("Paired. Connecting…");
                    new Thread(() -> {
                        Wireless.Result cr = Wireless.connect(this);
                        main.post(() -> {
                            if (cr.ok) {
                                toast("Connected");
                                dismissPairing(scrim);
                                startLocalSamplerIfNeeded();
                                refreshStatus();
                            } else {
                                pair.setEnabled(true);
                                pair.setText("Pair");
                                status.setText("Paired, but could not connect. "
                                        + "Check that wireless debugging is still on.");
                            }
                        });
                    }, "pulse-connect").start();
                });
            }, "pulse-pair").start();
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        plp.leftMargin = Ui.dp(this, 10);
        actions.addView(pair, plp);
        card.addView(actions);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = Ui.dp(this, 24);
        cardLp.rightMargin = Ui.dp(this, 24);
        scrim.addView(card, cardLp);

        scrim.setAlpha(0f);
        screen.addView(scrim, new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        scrim.animate().alpha(1f).setDuration(220).start();
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(Anim.SETTLE).start();
    }

    /** Remove the prompt and clear the blur, restoring the screen behind it. */
    private void dismissPairing(View scrim) {
        if (Build.VERSION.SDK_INT >= 31 && screen.getChildCount() > 0) {
            try { screen.getChildAt(0).setRenderEffect(null); } catch (Throwable ignored) { }
        }
        scrim.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            try { screen.removeView(scrim); } catch (Throwable ignored) { }
        }).start();
    }

    private Meter meter(LinearLayout host, int color) {
        Meter m = new Meter(this);
        m.set(0f, color, "", "--");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 30));
        host.addView(m, lp);
        return m;
    }

    private View gap(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, dp), 1));
        return v;
    }

    // ------------------------------------------------------------------ live

    @Override public void onSample(Sample s) {
        if (s == null || ring == null) return;

        // First real reading: drop both placeholders and bring both boxes in
        // together, once. Later samples just update in place - no animation on
        // refresh, and no re-showing of the shimmer.
        if (!gaugesReady) {
            gaugesReady = true;
            hideLoading(readoutHost);
            hideLoading(chartHost);
            revealBoth();
        }

        // Ring: CPU load on the outer arc, memory use on the inner one. The memory
        // reading is shown as a small "6.1/11G" under the percentage - small enough
        // to sit inside the inner arc without covering it.
        float cpuFrac = s.cpuPct / 100f;
        float memFrac = s.memTotalKb > 0 ? (s.memUsedKb / (float) s.memTotalKb) : Float.NaN;
        double usedGb = s.memUsedKb / 1048576.0;
        double totalGb = s.memTotalKb / 1048576.0;
        String memText = totalGb > 0
                ? String.format(java.util.Locale.US, "%.1f/%.0fG", usedGb, totalGb)
                : "";
        ring.set(cpuFrac, Theme.SERIES[0], memFrac, Theme.SERIES[1],
                Math.round(s.cpuPct) + "%", memText);

        // Bars: temperature against its range, battery against 100, power against
        // 8 W, frame rate against the panel's 120 Hz.
        mCpuT.set(finite(s.cpuTemp) ? (s.cpuTemp - 20f) / 80f : Float.NaN, Theme.SERIES[2],
                "CPU °C", finite(s.cpuTemp) ? Math.round(s.cpuTemp) + "°" : "--");
        mBatT.set(finite(s.batTemp) ? (s.batTemp - 20f) / 40f : Float.NaN, Theme.SERIES[3],
                "BAT °C", finite(s.batTemp) ? Math.round(s.batTemp) + "°" : "--");
        mBat.set(s.level >= 0 ? s.level / 100f : Float.NaN, Theme.SERIES[7],
                "Battery", s.level >= 0 ? s.level + "%" : "--");
        mW.set(finite(s.watts) ? Math.abs(s.watts) / 8f : Float.NaN, Theme.SERIES[6],
                "Power", finite(s.watts)
                        ? String.format(java.util.Locale.US, "%.1fW", s.watts) : "--");
        mFps.set(s.fps >= 0 ? s.fps / 120f : Float.NaN, Theme.SERIES[4],
                "FPS", s.fps >= 0 ? String.valueOf(Math.round(s.fps)) : "--");

        // Charts: append to the rolling history and redraw.
        hist.addLast(s);
        while (hist.size() > HIST) hist.removeFirst();
        updateCharts();
    }

    /**
     * Bring both boxes in together.
     *
     * The charts need two samples before they have a line to draw, so revealing
     * each box as its own data arrived made them appear one after the other. They
     * are revealed as a pair the moment the first sample lands, so the box with
     * curves simply draws its first line a moment later without a separate fade.
     */
    private void revealBoth() {
        for (View v : new View[]{ readoutContent, chartContent }) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            v.setTranslationY(Ui.dp(this, 10));
            v.animate().alpha(1f).translationY(0f)
                    .setDuration(260).setInterpolator(Anim.EASE).start();
        }
    }

    private void updateCharts() {
        List<Sample> list = new ArrayList<>(hist);
        if (list.size() < 2) return;

        // Core clocks: one mini-curve per core, each against its OWN real ceiling.
        int cores = 0;
        for (Sample s : list) cores = Math.max(cores, s.coreMhz.length);
        if (cores > 0 && coreGrid != null) {
            // Take the maxima the device reported (cpuinfo_max_freq), which is the
            // hardware ceiling per core. Fall back to the highest clock seen only
            // for a core the device did not report one for.
            float[] maxPerCore = new float[cores];
            for (int c = 0; c < cores; c++) {
                float reported = 0;
                for (Sample s : list) {
                    if (c < s.coreMaxMhz.length && s.coreMaxMhz[c] > 0) {
                        reported = s.coreMaxMhz[c];
                        break;
                    }
                }
                if (reported <= 0) {
                    for (Sample s : list)
                        if (c < s.coreMhz.length) reported = Math.max(reported, s.coreMhz[c]);
                }
                maxPerCore[c] = reported > 0 ? reported : 1000f;
            }
            coreGrid.setData(list, maxPerCore);
        }

        // Temperatures: one vertical curve per sensor, each on its own scale.
        if (tempView != null) {
            List<TempView.Line> lines = new ArrayList<>();
            addTemp(lines, list, "GPU",  Theme.SERIES[0], 0);
            addTemp(lines, list, "Skin", Theme.SERIES[3], 1);
            addTemp(lines, list, "Amp",  Theme.SERIES[2], 2);
            tempView.setLines(lines);
        }
    }

    /** index 0 = gpu, 1 = skin, 2 = power amplifier. */
    private void addTemp(List<TempView.Line> out, List<Sample> list,
                         String name, int color, int which) {
        float[] v = new float[list.size()];
        boolean any = false;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < v.length; i++) {
            Sample s = list.get(i);
            float t = which == 0 ? s.gpuTemp : which == 1 ? s.skinTemp : s.paTemp;
            v[i] = t;
            if (finite(t)) {
                any = true;
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
        }
        if (!any) return;
        // A little padding, and a floor on the span so a steady sensor is not
        // stretched into fake drama.
        if (hi - lo < 4f) { float mid = (hi + lo) / 2f; lo = mid - 2f; hi = mid + 2f; }
        out.add(new TempView.Line(name, color, v, lo - 1f, hi + 1f));
    }

    private static boolean finite(float v) { return !Float.isNaN(v); }

    private void startLocalSamplerIfNeeded() {
        if (OverlayService.RUNNING) return;      // the overlay already samples
        if (local != null) return;
        local = new Sampler(this);
        local.addListener(new Sampler.Listener() {
            @Override public void onSample(Sample s, java.util.List<Sample> history) {
                Live.push(s);
            }
            @Override public void onLink(boolean up, String message) {
                main.post(MainActivity.this::refreshStatus);
            }
        });
        local.start();
    }

    private void stopLocalSampler() {
        if (local != null) { local.stop(); local = null; }
    }

    // -------------------------------------------------------------- overlay

    private void toggleOverlay() {
        if (!canDrawOverlay()) {
            toast("Grant the \"Display over other apps\" permission first");
            openOverlaySettings();
            return;
        }
        if (OverlayService.RUNNING) {
            startService(new Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_HIDE));
        } else {
            startOverlay();
        }
        for (int i = 1; i <= 6; i++) main.postDelayed(this::syncOverlayToggle, 120L * i);
    }

    private void startOverlay() {
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_SHOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    /**
     * The header power button reflects the REAL overlay state - filled blue and a
     * white glyph while the overlay runs, a plain circle while it is off. Reads
     * {@link OverlayService#RUNNING} rather than the preference, which records a
     * request and can disagree with what is on screen.
     */
    private void syncOverlayToggle() {
        if (overlayButton == null) return;
        boolean on = OverlayService.RUNNING;
        overlayButton.setBackground(Ui.round(on ? Theme.ACCENT : Theme.CARD_HI, Ui.dp(this, 20)));
        Object tag = overlayButton.getTag();
        if (tag instanceof Icon) ((Icon) tag).setColor(on ? 0xFFFFFFFF : Theme.TEXT_DIM);
    }

    /**
     * The connection line, shown ONLY when something needs attention.
     *
     * A working connection is the normal state, so announcing it with an address
     * on the home screen is noise - the user does not need to read "localhost:34573"
     * to know the app is working. The line appears only when there IS a problem to
     * act on (not paired, paired but disconnected, adb missing), and hides itself
     * again once the connection is healthy.
     */
    private void refreshStatus() {
        if (statusLine == null) return;
        new Thread(() -> {
            String s;
            if (!Wireless.bundled(this)) {
                s = "Bundled adb not found";
            } else if (Wireless.connected(this)) {
                s = null;   // healthy: say nothing
            } else if (prefs.paired()) {
                s = "Paired, not connected · tap the gear to reconnect";
            } else {
                s = "Not paired · open Settings to pair";
            }
            final String out = s;
            main.post(() -> {
                if (statusLine == null) return;
                if (out == null) {
                    statusLine.setVisibility(View.GONE);
                } else {
                    statusLine.setText(out);
                    statusLine.setVisibility(View.VISIBLE);
                }
            });
        }, "pulse-status").start();
    }

    private boolean canDrawOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Diag.log("open overlay settings failed", t);
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    /**
     * If a previous run left an app suspended or disabled, surface it.
     *
     * Those modes survive Pulse being killed, so a user who used them and then
     * closed the app could otherwise be stuck with no idea why an app is gone.
     * The offer to release is the way back.
     */
    private void warnPendingHolds() {
        if (!Freeze.anythingHeld(this)) return;
        if (Freeze.suspended(this).isEmpty() && Freeze.disabled(this).isEmpty()) return;
        int n = Freeze.suspended(this).size() + Freeze.disabled(this).size();
        confirm("Apps still held",
                n + (n == 1 ? " app is" : " apps are") + " still suspended or disabled from a "
                        + "previous session. Release them now?",
                "Release all", Theme.ACCENT, () -> new Thread(() -> {
                    Freeze.releaseAll(this);
                    main.post(() -> toast("All apps released"));
                }, "pulse-release").start());
    }
}
