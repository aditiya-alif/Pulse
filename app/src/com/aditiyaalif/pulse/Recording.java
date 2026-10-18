// language: Java, file: Recording.java, target: Android 16 (API 36)
// One saved capture: the samples taken while the user's chosen app was focused,
// plus the summary statistics derived from them.
//
// A recording belongs to ONE app - the app that was in focus at the instant the
// user started it. Samples taken while a different app is focused are dropped,
// and the gap is counted as a pause, so a capture of a game is not polluted by
// the seconds spent in a notification shade or a launcher. Two captures of the
// same app are two separate recordings, never merged: the list is a history, and
// collapsing it by package would erase exactly the comparison the user wants.
package com.aditiyaalif.pulse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Recording {
    String id;                 // filename-safe unique id
    String pkg = "";           // the app this capture belongs to
    long startedAt;            // wall-clock ms when recording began
    long endedAt;              // wall-clock ms when it stopped
    long activeMs;             // time actually spent with the target focused
    int pauses;                // times focus left the target and came back
    List<Sample> samples = new ArrayList<>();

    // ---- summary statistics, computed once on save -------------------------

    float fpsAvg = Float.NaN, fpsMin = Float.NaN, fpsMax = Float.NaN;
    /** 1% low: the average of the worst 1% of frame-rate samples. */
    float fps1Low = Float.NaN;
    /** 0.1% low: the same over the worst 0.1%, the stutter the eye actually notices. */
    float fps01Low = Float.NaN;
    float fpsStd = Float.NaN;      // standard deviation - smoothness in one number

    float wattAvg = Float.NaN;
    /**
     * Peak power, carrying the sign of the sample it came from.
     *
     * Drain is negative and charge positive - the same convention as
     * {@link #wattAvg} beside it, so the two read as one story rather than one
     * showing a bare magnitude. The peak is the sample of greatest magnitude:
     * during a normal discharge that is the deepest drain (negative), and during
     * charging it is the largest charge (positive).
     */
    float wattPeak = Float.NaN;
    float tempCpuAvg = Float.NaN, tempCpuMax = Float.NaN;
    float tempBatAvg = Float.NaN, tempBatMax = Float.NaN;
    float cpuAvg = Float.NaN, cpuMax = Float.NaN;
    float memAvgGb = Float.NaN, memMaxGb = Float.NaN;
    int battStart = -1, battEnd = -1;   // battery % at start and end, for drain

    /** Wall-clock duration the capture covers, including paused stretches. */
    long durationMs() { return Math.max(0, endedAt - startedAt); }

    /**
     * The span the SAMPLES actually cover, in ms.
     *
     * This is what a chart's X axis ends at, not {@link #durationMs()}: the wall
     * clock runs from the moment recording began to the moment it stopped, which
     * includes the tail after the last sample and any paused stretch. The samples
     * stop earlier, so labelling the axis with the wall clock draws a max the data
     * never reached - the curve ends short of the number. The sample span is the
     * real maximum, and the last sample is drawn at the axis end.
     */
    long spanMs() {
        if (samples.size() < 2) return 0;
        return Math.max(0, (samples.get(samples.size() - 1).t - samples.get(0).t) / 1_000_000);
    }

    /** Battery percentage points consumed (negative means it charged). */
    int battDrop() {
        if (battStart < 0 || battEnd < 0) return 0;
        return battStart - battEnd;
    }

    // ------------------------------------------------------------- statistics

    /**
     * Fill every summary field from {@link #samples}.
     *
     * Percentiles are computed over the per-sample frame rates, which is the
     * frame-rate a user reads off an overlay. The "1% low" is the mean of the
     * worst 1% of those samples - the conventional way a stutter is quoted, and
     * the reason a game can show a good average while feeling bad.
     */
    void computeStats() {
        List<Float> fps = new ArrayList<>();
        List<Float> watt = new ArrayList<>();
        List<Float> tcpu = new ArrayList<>();
        List<Float> tbat = new ArrayList<>();
        List<Float> cpu = new ArrayList<>();
        List<Float> mem = new ArrayList<>();

        // Filter on the DERIVED value being finite, never on a raw field that may
        // not have survived the round trip. watt and mem used to be gated on
        // voltMv and memTotalKb - neither of which is written to JSON - so a
        // reloaded recording collected no wattage or memory at all and both
        // averages read "--" however good the data had been.
        for (Sample s : samples) {
            if (s.fps >= 0) fps.add(s.fps);
            if (!Float.isNaN(s.watts)) watt.add(s.watts);
            if (!Float.isNaN(s.cpuTemp)) tcpu.add(s.cpuTemp);
            if (!Float.isNaN(s.batTemp)) tbat.add(s.batTemp);
            if (!Float.isNaN(s.cpuPct)) cpu.add(s.cpuPct);
            if (s.memUsedKb > 0) mem.add(s.memUsedKb / 1048576f);
        }

        fpsAvg = mean(fps);
        fpsMin = min(fps);
        fpsMax = max(fps);
        fpsStd = std(fps);
        fps1Low = lowPercentile(fps, 0.01f);
        fps01Low = lowPercentile(fps, 0.001f);

        wattAvg = mean(watt);
        // The peak is the sample of LARGEST MAGNITUDE, keeping its own sign, so
        // the number means what it says: -4.52 W is 4.52 W being drawn, +1.20 W
        // is 1.20 W going in. The old code forced it positive, which printed a
        // bare 4.52 beside a -3.21 average and read like charging.
        float peak = Float.NaN;
        for (float w : watt) {
            if (Float.isNaN(peak) || Math.abs(w) > Math.abs(peak)) peak = w;
        }
        wattPeak = peak;

        tempCpuAvg = mean(tcpu);
        tempCpuMax = max(tcpu);
        tempBatAvg = mean(tbat);
        tempBatMax = max(tbat);

        cpuAvg = mean(cpu);
        cpuMax = max(cpu);

        memAvgGb = mean(mem);
        memMaxGb = max(mem);

        if (!samples.isEmpty()) {
            battStart = samples.get(0).level;
            battEnd = samples.get(samples.size() - 1).level;
        }
    }

    private static float mean(List<Float> v) {
        if (v.isEmpty()) return Float.NaN;
        double sum = 0;
        for (float x : v) sum += x;
        return (float) (sum / v.size());
    }

    private static float min(List<Float> v) {
        if (v.isEmpty()) return Float.NaN;
        float m = Float.MAX_VALUE;
        for (float x : v) m = Math.min(m, x);
        return m;
    }

    private static float max(List<Float> v) {
        if (v.isEmpty()) return Float.NaN;
        float m = -Float.MAX_VALUE;
        for (float x : v) m = Math.max(m, x);
        return m;
    }

    private static float std(List<Float> v) {
        if (v.size() < 2) return Float.NaN;
        float m = mean(v);
        double sum = 0;
        for (float x : v) sum += (x - m) * (x - m);
        return (float) Math.sqrt(sum / (v.size() - 1));
    }

    /**
     * The mean of the worst {@code frac} of the values.
     *
     * For 1% low this is the average of the slowest 1% of frames, which is what
     * "1% low" means in every benchmark tool - not the single worst frame (that
     * is the minimum) and not a percentile of the sorted list (that would be the
     * 1st-percentile frame, a different and much jumpier number).
     */
    private static float lowPercentile(List<Float> v, float frac) {
        if (v.isEmpty()) return Float.NaN;
        float[] a = new float[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        int n = Math.max(1, Math.round(a.length * frac));
        double sum = 0;
        for (int i = 0; i < n; i++) sum += a[i];
        return (float) (sum / n);
    }

    // ------------------------------------------------------------------ json

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("pkg", pkg);
            o.put("startedAt", startedAt);
            o.put("endedAt", endedAt);
            o.put("activeMs", activeMs);
            o.put("pauses", pauses);
            o.put("battStart", battStart);
            o.put("battEnd", battEnd);

            // Only the fields the charts and detail view need - not every raw
            // counter - so a long capture stays a small file.
            //
            // EVERY value goes through num(), which maps NaN to JSON null.
            // JSONObject.put(double) THROWS on NaN, and a metric the device
            // cannot provide (GPU here) is NaN on every single sample - so a
            // direct put() aborted the whole serialization partway and saved a
            // recording with an empty sample list, which is why every statistic
            // then read "--".
            JSONArray arr = new JSONArray();
            for (Sample s : samples) {
                JSONArray e = new JSONArray();
                e.put(s.t);
                e.put(num(s.cpuPct));
                e.put(num(s.memUsedKb / 1048576f));
                e.put(num(s.cpuTemp));
                e.put(num(s.batTemp));
                e.put(num(s.fps));
                e.put(num(s.watts));
                e.put(num(s.gpu));
                e.put(s.level);
                arr.put(e);
            }
            o.put("samples", arr);
        } catch (Throwable t) {
            Diag.log("recording toJson failed", t);
        }
        return o;
    }

    static Recording fromJson(JSONObject o) {
        Recording r = new Recording();
        try {
            r.id = o.optString("id", "");
            r.pkg = o.optString("pkg", "");
            r.startedAt = o.optLong("startedAt", 0);
            r.endedAt = o.optLong("endedAt", 0);
            r.activeMs = o.optLong("activeMs", 0);
            r.pauses = o.optInt("pauses", 0);
            r.battStart = o.optInt("battStart", -1);
            r.battEnd = o.optInt("battEnd", -1);

            JSONArray arr = o.optJSONArray("samples");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray e = arr.optJSONArray(i);
                    if (e == null) continue;
                    Sample s = new Sample();
                    s.t = e.optLong(0);
                    s.cpuPct = (float) e.optDouble(1, Double.NaN);
                    s.memUsedKb = (long) (e.optDouble(2, Double.NaN) * 1048576);
                    s.cpuTemp = (float) e.optDouble(3, Double.NaN);
                    s.batTemp = (float) e.optDouble(4, Double.NaN);
                    s.fps = (float) e.optDouble(5, Double.NaN);
                    s.watts = (float) e.optDouble(6, Double.NaN);
                    s.gpu = (float) e.optDouble(7, Double.NaN);
                    s.level = e.optInt(8, -1);
                    s.valid = true;
                    r.samples.add(s);
                }
            }
        } catch (Throwable t) {
            Diag.log("recording fromJson failed", t);
        }
        r.computeStats();
        return r;
    }

    /**
     * A number for JSON, or JSON null when the value is NaN.
     *
     * This is the whole reason recordings used to save empty: {@code put(double)}
     * throws on NaN, and a metric the device cannot supply is NaN on every
     * sample. Mapping NaN to null keeps the field present (so the reader can tell
     * "not measured" from "missing column") without aborting the write.
     */
    private static Object num(float v) {
        if (Float.isNaN(v)) return JSONObject.NULL;
        return Math.round(v * 100.0) / 100.0;
    }
}
