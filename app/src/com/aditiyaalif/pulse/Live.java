// language: Java, file: Live.java, target: Android 16 (API 36)
// The newest reading, shared between whoever is sampling and whoever is showing.
//
// The overlay service and the home screen both want the latest sample. Rather
// than run two samplers - two adb sessions, twice the dumpsys cost - one sampler
// pushes here and any number of screens read it. The home screen only starts its
// own sampler when the overlay is NOT running, so there is never more than one
// alive at a time.
package com.aditiyaalif.pulse;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

final class Live {
    interface Sink { void onSample(Sample s); }

    /**
     * The newest reading, kept so a screen that returns to the front can show the
     * last values at once instead of resetting to "--" while it waits for the next
     * sample. A stale read for a moment beats an empty one.
     */
    static volatile Sample last;

    private static final List<Sink> SINKS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Live() { }

    /** Called from the sampler thread; sinks are notified on the main thread. */
    static void push(Sample s) {
        last = s;
        final List<Sink> copy;
        synchronized (SINKS) { copy = new ArrayList<>(SINKS); }
        if (copy.isEmpty()) return;
        MAIN.post(() -> { for (Sink k : copy) k.onSample(s); });
    }

    static void add(Sink k) {
        synchronized (SINKS) { if (!SINKS.contains(k)) SINKS.add(k); }
    }

    static void remove(Sink k) {
        synchronized (SINKS) { SINKS.remove(k); }
    }
}
