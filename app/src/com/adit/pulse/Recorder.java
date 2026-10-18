// language: Java, file: Recorder.java, target: Android 16 (API 36)
// The recording state machine.
//
// The rule the user asked for: a tap starts a capture of whatever app is focused
// AT THAT MOMENT; from then on only that app's samples are kept. If focus leaves
// - a notification, the launcher, a quick switch - the capture PAUSES rather
// than ending, and resumes when focus returns. A second tap ends it and saves.
//
// Pausing rather than stopping is the point: the numbers must describe the game,
// not the interruptions. And a pause is counted, so a heavily-interrupted capture
// says so instead of quietly averaging over dead time.
//
// This class holds no UI and no storage - it is fed samples and reports state.
package com.adit.pulse;

import java.util.ArrayList;
import java.util.List;

final class Recorder {
    interface Listener {
        /** State changed (started, paused, resumed, stopped). */
        void onState(boolean recording, boolean paused, String pkg, long activeMs);
    }

    private boolean active;
    private boolean paused;
    private String targetPkg = "";
    private long startedAt;
    private long activeMs;
    private long lastTickAt;
    private int pauses;

    private final List<Sample> samples = new ArrayList<>();
    private Listener listener;

    /** Cap on a single capture, so a forgotten recording cannot fill the disk. */
    private static final int MAX_SAMPLES = 240 * 60;   // 30 minutes at 0.5 s

    void setListener(Listener l) { this.listener = l; }

    boolean isActive() { return active; }
    boolean isPaused() { return paused; }

    /**
     * Start a capture of {@code pkg}.
     *
     * Returns false when there is no focused app to record - a lock screen or a
     * system surface has no package, and a capture of nothing is not useful.
     */
    boolean start(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        active = true;
        paused = false;
        targetPkg = pkg;
        startedAt = System.currentTimeMillis();
        activeMs = 0;
        lastTickAt = android.os.SystemClock.elapsedRealtime();
        pauses = 0;
        samples.clear();
        fire();
        return true;
    }

    /** Feed one sample. Returns true when it was kept. */
    boolean onSample(Sample s) {
        if (!active || s == null) return false;

        long now = android.os.SystemClock.elapsedRealtime();
        boolean focused = targetPkg.equals(s.pkg);

        if (focused) {
            if (paused) { paused = false; pauses++; fire(); }
            // Count time only while the target is focused.
            long dt = now - lastTickAt;
            if (dt > 0 && dt < 5000) activeMs += dt;
            lastTickAt = now;
            if (samples.size() < MAX_SAMPLES) samples.add(s);
            return true;
        }

        // Focus left the target: pause. Time stops accruing; the first return
        // counts as one pause, so the count means "times focus came back".
        if (!paused) {
            paused = true;
            fire();
        }
        lastTickAt = now;
        return false;
    }

    /** End the capture and return it, or null when nothing was recorded. */
    Recording stop() {
        if (!active) return null;
        Recording r = new Recording();
        r.pkg = targetPkg;
        r.startedAt = startedAt;
        r.endedAt = System.currentTimeMillis();
        r.activeMs = activeMs;
        r.pauses = pauses;
        r.samples = new ArrayList<>(samples);
        r.computeStats();

        active = false;
        paused = false;
        samples.clear();
        fire();
        return r;
    }

    private void fire() {
        Listener l = listener;
        if (l != null) l.onState(active, paused, targetPkg, activeMs);
    }

    /**
     * The elapsed label for the bar.
     *
     * Deliberately NOT extrapolated from the last sample. Extrapolating would
     * make the timer keep climbing through a link drop, reporting time the
     * recorder never actually counted - and the whole point of the active-time
     * figure is that it is time genuinely spent on the target. At 0.5 s sampling
     * the value updates twice a second, which is smooth at second resolution.
     */
    long liveElapsedMs() {
        return active ? activeMs : 0;
    }
}
