// language: Java, file: Sample.java, target: Android 16 (API 36)
// One parsed reading, plus the derived rates. Raw counters (jiffies, frames,
// bytes) are kept so the next sample can be differenced against them; the
// derived fields are what the UI reads.
package com.adit.pulse;

final class Sample {
    long t;                 // device uptime, ns
    long cpuTotal, cpuIdle; // jiffies, cumulative
    long memTotalKb, memAvailKb;
    String pkg = "";        // focused app package
    /**
     * Frames per second, already computed on the device (deduped by vsync id so a
     * SurfaceView app's double render does not double it). -1 when unreadable.
     */
    /**
     * Frames per second as SurfaceFlinger reports it for the focused app's own
     * presenting layer (averageFPS over the interval since the last sample). -1
     * when the app has no presenting layer. A float: the value carries decimals.
     */
    float fpsRaw = -1;
    int level = -1;         // battery %
    int voltMv = -1;        // battery voltage, mV
    int status = -1;        // 1 unknown 2 charging 3 discharging 4 not-charging 5 full
    int currentUa = 0;      // battery current, microamps (signed; + = charging)
    float cpuTemp = Float.NaN;
    float batTemp = Float.NaN;
    /** GPU utilization %, or NaN when the device's GPU load node is not readable. */
    float gpu = Float.NaN;

    /**
     * Per-core clock in MHz, or empty when the device does not expose it.
     *
     * Eight entries on this device; the array length is whatever the sampler
     * reported, so a device with a different core count still works.
     */
    float[] coreMhz = new float[0];
    /**
     * Each core's hardware maximum clock in MHz, from `cpuinfo_max_freq`. Sent once
     * per session; empty until the first line arrives.
     */
    float[] coreMaxMhz = new float[0];
    /** The other thermal sensors, shown alongside the CPU reading on the home chart. */
    float gpuTemp = Float.NaN, skinTemp = Float.NaN, paTemp = Float.NaN;

    // derived
    float cpuPct;           // 0..100
    long memUsedKb, memFreeKb;
    float fps = -1;         // -1 = unavailable
    float watts;            // signed; + = charging
    boolean valid;
}
