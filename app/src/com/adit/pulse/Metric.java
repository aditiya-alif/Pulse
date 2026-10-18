// language: Java, file: Metric.java, target: Android 16 (API 36)
// The metric set is the single source of truth for: the sampler's field order,
// the bar layout, the chart series, and the legend.
//
// Each metric carries no fixed domain any more. The charts derive their axes from
// the data actually present (see ChartView.window), so a recording whose
// temperature barely moves is drawn against a tight axis instead of a fixed
// 20..100 C band that would flatten it. The only hint kept here is which metrics
// must keep zero in view - power, whose sign is the point.
package com.adit.pulse;

final class Metric {
    final String key;
    final String shortLabel;   // bar / legend chip
    final String unit;         // "" when the value carries its own
    final int colorSlot;       // index into Theme.SERIES (fixed order, never cycled)
    final int decimals;

    private Metric(String key, String shortLabel, String unit, int slot, int decimals) {
        this.key = key;
        this.shortLabel = shortLabel;
        this.unit = unit;
        this.colorSlot = slot;
        this.decimals = decimals;
    }

    int color() { return Theme.SERIES[colorSlot]; }

    /** Whether a chart axis for this metric must contain zero (power only). */
    boolean includeZero() { return "w".equals(key); }

    static final Metric CPU     = new Metric("cpu", "CPU %",  "%",  0, 0);
    static final Metric MEM     = new Metric("mem", "MEM",    "GB", 1, 1);
    static final Metric TEMP_CPU = new Metric("tcpu", "CPU °C", "°C", 2, 0);
    static final Metric TEMP_BAT = new Metric("tbat", "BAT °C", "°C", 3, 0);
    static final Metric FPS     = new Metric("fps", "FPS",    "",   4, 0);
    static final Metric GPU     = new Metric("gpu", "GPU %",  "%",  5, 0);
    static final Metric WATT    = new Metric("w", "W", "W",         6, 1);
    static final Metric BATT    = new Metric("batt", "BAT %", "%",  7, 0);

    static final Metric[] ALL = { CPU, MEM, TEMP_CPU, TEMP_BAT, FPS, GPU, WATT, BATT };

    static Metric byKey(String key) {
        for (Metric m : ALL) if (m.key.equals(key)) return m;
        return null;
    }
}
