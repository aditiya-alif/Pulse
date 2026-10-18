// language: Java, file: Sampler.java, target: Android 16 (API 36)
// Owns the adb sampler session, parses each raw line into a {@link Sample},
// derives the rates, keeps the recent history for the charts, and notifies
// listeners on the main thread.
//
// Runs entirely off the main thread. If the session dies - screen off can drop
// the transport, adb server restart, device reboot - it reconnects on its own
// with a backoff, so an overlay left running recovers without the user touching
// anything.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class Sampler {
    interface Listener {
        /** A new sample is ready. Called on the main thread. */
        void onSample(Sample s, List<Sample> history);

        /** The link to adb went down (or came back). Main thread. */
        void onLink(boolean up, String message);

        /**
         * Whether this listener actually reads {@code history}.
         *
         * The overlay bar does not - it draws the latest sample only - while the
         * monitor panel does. Building the history copy is the one per-sample
         * allocation left on the sampler thread (a 240-element ArrayList), so it
         * is skipped entirely when no live listener wants it.
         */
        default boolean wantsHistory() { return false; }
    }

    /** 1 s cadence (see Wireless.SCRIPT: work ~0.45 s + 1 s sleep); 240 points ≈ 4 min. */
    private static final int HISTORY = 240;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private final List<Listener> listeners = new ArrayList<>();

    private volatile boolean running;
    private volatile boolean linkUp;
    private Thread worker;
    private Sample prev;

    Sampler(Context c) { this.ctx = c.getApplicationContext(); }

    void addListener(Listener l) { synchronized (listeners) { if (!listeners.contains(l)) listeners.add(l); } }
    void removeListener(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    boolean isRunning() { return running; }
    boolean linkUp() { return linkUp; }

    /**
     * True when the bar currently shows FPS.
     *
     * Read from the same preference the bar is built from, so the script and the
     * bar always agree. Checked at session open: changing the bar contents
     * restarts the overlay, which opens a new session with the new value.
     */
    private boolean wantFps() {
        return new Prefs(ctx).barItems().contains("fps");
    }

    /**
     * Try the link once, now, and resume retrying - used by the Reconnect button.
     *
     * The loop already retries on its own; this just makes it try immediately
     * instead of waiting out the current backoff.
     */
    void retry() {
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    /** A copy of the history, oldest first, safe to read from any thread. */
    List<Sample> snapshot() {
        synchronized (history) { return new ArrayList<>(history); }
    }

    void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "pulse-sampler");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
    }

    // ------------------------------------------------------------------ loop

    private void loop() {
        long backoff = 2000;
        while (running) {
            Adb.Session session = null;
            try {
                if (!Adb.connected()) {
                    // KEEP TRYING, FOREVER.
                    //
                    // Another adb tool (LADB) may be toggling wireless debugging on
                    // and off repeatedly, so the link comes and goes on its own. The
                    // sampler must keep reaching for it - going quiet would leave the
                    // overlay dead until the user noticed and pressed Reconnect. It
                    // simply backs off so a long outage is not a tight loop, and the
                    // moment the link is back it resumes.
                    setLink(false, "Reconnecting…");
                    boolean ok = Wireless.autoConnect(ctx);
                    if (!ok) {
                        setLink(false, "Reconnecting…");
                        sleep(backoff);
                        backoff = Math.min(backoff * 2, 20000);
                        continue;
                    }
                    backoff = 2000;
                }

                Wireless.installScript(ctx);
                // Tell the script whether FPS is on the bar: when it is not, the
                // SurfaceFlinger dump+clear pair is skipped entirely, which is
                // ~140 ms of device CPU per cycle.
                session = Adb.open(Wireless.samplerCommand(wantFps()));
                setLink(true, "Connected");
                prev = null;

                String line;
                while (running && (line = session.readLine()) != null) {
                    Sample s = parse(line);
                    if (s == null) continue;
                    boolean baseline = prev != null;
                    derive(s, prev);
                    prev = s;
                    // The FIRST sample of a session has nothing to difference
                    // against, so its CPU load is a meaningless 0 and its battery
                    // fields are not read yet. Publishing it made every screen that
                    // had just (re)opened flash 0% and "--" over the values it was
                    // already showing. Drop it; the next sample is real.
                    if (!baseline) continue;
                    deliver(s);
                }
                // A null here is either a real EOF or the read timeout firing -
                // either way the session is done, and the loop below reconnects.
                // This is what makes the overlay recover from a stalled transport
                // (a screen recording starves the dump services and the sampler's
                // output stops) instead of freezing on its last values until the
                // app is restarted.
                Adb.reset();
                setLink(false, "Connection lost");
            } catch (Throwable t) {
                Diag.log("sampler loop error", t);
                Adb.reset();
                setLink(false, "Sampler stopped: " + t.getClass().getSimpleName());
            } finally {
                if (session != null) session.close();
            }
            if (running) {
                sleep(backoff);
                backoff = Math.min(backoff * 2, 20000);
            }
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void deliver(Sample s) {
        synchronized (history) {
            history.addLast(s);
            while (history.size() > HISTORY) history.removeFirst();
        }
        // Copy the history ONLY if a live listener reads it (the monitor panel
        // does, the overlay bar does not). The copy is a 240-element ArrayList on
        // every sample - small, but it is pure waste when the panel is closed,
        // which is the normal case.
        List<Sample> snap = null;
        synchronized (listeners) {
            for (Listener l : listeners) {
                if (l.wantsHistory()) { snap = snapshot(); break; }
            }
        }
        final List<Sample> hs = snap;
        main.post(() -> {
            synchronized (listeners) {
                for (Listener l : new ArrayList<>(listeners)) l.onSample(s, hs);
            }
        });
    }

    private void setLink(boolean up, String msg) {
        if (up == linkUp && up) return;
        linkUp = up;
        Diag.log("link " + (up ? "up" : "down") + ": " + msg);
        main.post(() -> {
            synchronized (listeners) {
                for (Listener l : new ArrayList<>(listeners)) l.onLink(up, msg);
            }
        });
    }

    // ----------------------------------------------------------------- parse

    /**
     * One raw line -> a Sample, or null when the line is not a complete sample.
     *
     * Field order is fixed by {@link Wireless#SCRIPT}:
     *   S | t | cpu | mt | ma | pkg | fr | lvl | volt | st | cur | ct | bt | gpu
     *     | fq | gt | sk | pa
     * The fields after gpu were added later; a line from an older script has fewer
     * fields, so each is read defensively and simply stays unavailable.
     */
    static Sample parse(String line) {
        if (line == null || !line.startsWith("S|")) return null;
        String[] f = line.split("\\|", -1);
        if (f.length < 14) return null;
        Sample s = new Sample();
        try {
            s.t = Long.parseLong(f[1].trim());
            parseCpu(f[2], s);
            s.memTotalKb = parseLong(f[3]);
            s.memAvailKb = parseLong(f[4]);
            s.pkg = f[5] == null ? "" : f[5].trim();
            // Empty field (no presenting layer) maps to -1, not NaN: the UI shows
            // "--" for -1 and the recording skips it, while NaN would silently
            // poison an average.
            float fps = parseFloat(f[6]);
            s.fpsRaw = Float.isNaN(fps) ? -1 : fps;
            s.level = (int) parseLong(f[7], -1);
            s.voltMv = (int) parseLong(f[8], -1);
            s.status = (int) parseLong(f[9], -1);
            s.currentUa = (int) parseLong(f[10], 0);
            s.cpuTemp = parseFloat(f[11]);
            s.batTemp = parseFloat(f[12]);
            s.gpu = parseFloat(f[13]);
            if (f.length > 14) s.coreMhz = parseCores(f[14]);
            if (f.length > 15) s.gpuTemp = parseFloat(f[15]);
            if (f.length > 16) s.skinTemp = parseFloat(f[16]);
            if (f.length > 17) s.paTemp = parseFloat(f[17]);
            if (f.length > 18) s.coreMaxMhz = parseCores(f[18]);
        } catch (Throwable t) {
            return null;
        }
        return s;
    }

    /** "1300000,700000,...," (kHz, trailing comma) -> MHz. Empty on no data. */
    private static float[] parseCores(String v) {
        if (v == null || v.trim().isEmpty()) return new float[0];
        String[] parts = v.trim().split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            long khz = parseLong(parts[i], 0);
            out[i] = khz / 1000f;   // kHz -> MHz
        }
        return out;
    }

    /** "cpu  1 2 3 ..." -> cumulative total and idle jiffies. */
    private static void parseCpu(String line, Sample s) {
        if (line == null) return;
        String[] tok = line.trim().split("\\s+");
        // tok[0] == "cpu"; fields from tok[1]: user nice system idle iowait irq softirq steal
        long total = 0, idle = 0;
        for (int i = 1; i < tok.length; i++) {
            long v = parseLong(tok[i]);
            total += v;
            if (i == 4 || i == 5) idle += v;   // idle + iowait
        }
        s.cpuTotal = total;
        s.cpuIdle = idle;
    }

    /**
     * Fill in the derived fields by differencing against the previous sample.
     *
     * A metric whose source is missing stays at its "unavailable" sentinel rather
     * than being shown as zero - zero is a reading, and showing it for a value we
     * simply could not read would be a lie the chart then draws.
     */
    void derive(Sample s, Sample p) {
        s.memUsedKb = Math.max(0, s.memTotalKb - s.memAvailKb);
        s.memFreeKb = s.memAvailKb;

        if (p != null) {
            long dt = s.t - p.t;
            long dTotal = s.cpuTotal - p.cpuTotal;
            long dIdle = s.cpuIdle - p.cpuIdle;
            if (dt > 0 && dTotal > 0) {
                float busy = 1f - (dIdle / (float) dTotal);
                s.cpuPct = Math.max(0f, Math.min(100f, busy * 100f));
            }
        }
        // FPS comes from SurfaceFlinger TimeStats' averageFPS, which is computed
        // from frame PRESENT timestamps - so it is capped by the refresh rate and
        // reflects what is on screen. The stats are cleared after every dump (see
        // Wireless.SCRIPT), so the value covers only the interval since the last
        // sample: it is this instant's rate, not a session mean.
        //
        // fpsRaw of -1 means the app has no presenting layer - a system surface,
        // or a process that has not drawn - and stays -1 rather than a false zero.
        s.fps = s.fpsRaw >= 0 ? s.fpsRaw : -1f;

        // POWER, AND WHY THE CASTS ARE LOAD-BEARING.
        //
        // currentUa is microamps and voltMv millivolts, so power in watts is
        // current * volt / 1e9. But BOTH ARE int: 810000 uA * 3900 mV is
        // 3.16e9, past Integer.MAX_VALUE, and an int multiply WRAPS - producing
        // a small negative number. That is not a rounding error, it is a wrong
        // reading with the wrong sign, which is exactly how it showed up: the
        // wattage sat at 1.x-2.x and flickered between + and -. Widening to long
        // before the multiply is what makes it 3.16.
        //
        // The sign convention: NEGATIVE while the battery drains, positive while
        // it charges. It is taken from the charge status rather than the raw
        // current sign, because this device reports positive current while
        // discharging - the opposite of the AOSP convention - so the raw sign
        // cannot be trusted across devices. Status is unambiguous.
        if (s.voltMv > 0) {
            double mag = Math.abs((long) s.currentUa) * (double) s.voltMv / 1_000_000_000.0;
            boolean charging = (s.status == 2 || s.status == 5);   // CHARGING / FULL
            s.watts = (float) (charging ? mag : -mag);
        } else {
            s.watts = Float.NaN;
        }
        s.valid = true;
    }

    private static long parseLong(String v) { return parseLong(v, 0); }

    /**
     * Digits out of a field, or {@code def} when there are none.
     *
     * A missing field and a real zero are different things - "-1 FPS" (no frame
     * stats) must not collapse into "0 FPS" (a frozen app) - so callers that need
     * to tell them apart pass a sentinel default.
     */
    private static long parseLong(String v, long def) {
        if (v == null) return def;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
            else if (b.length() > 0) break;
        }
        if (b.length() == 0) return def;
        try { return Long.parseLong(b.toString()); } catch (Throwable t) { return def; }
    }

    private static float parseFloat(String v) {
        if (v == null || v.trim().isEmpty()) return Float.NaN;
        try { return Float.parseFloat(v.trim()); } catch (Throwable t) { return Float.NaN; }
    }
}
