// language: Java, file: Export.java, target: Android 16 (API 36)
// Turning a recording into a file the user can keep.
//
// Two shapes. CSV is the data - every sample, every column, for a spreadsheet or a
// bug report. PNG is the picture - the same curves the detail screen draws, for
// pasting into a chat.
//
// Both are written to the shared Downloads folder THROUGH THE SHELL, not through
// Android's file APIs. That is deliberate: the app has no FileProvider and no
// storage permission, and it does not need either, because it already owns a shell
// that can write the file directly. The heredoc form is the same one used to push
// the sampler script.
package com.aditiyaalif.pulse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Export {
    private static final String DIR = "/sdcard/Download";

    private Export() { }

    /** The CSV text for a recording: a header, then one row per sample. */
    static String csv(Recording r) {
        StringBuilder b = new StringBuilder();
        b.append("time_s,cpu_pct,mem_gb,cpu_c,bat_c,fps,watt,gpu_pct,battery_pct\n");
        if (r.samples.isEmpty()) return b.toString();
        long t0 = r.samples.get(0).t;
        for (Sample s : r.samples) {
            double sec = (s.t - t0) / 1e9;
            b.append(String.format(Locale.US, "%.1f,", sec));
            b.append(n(s.cpuPct)).append(',');
            b.append(s.memUsedKb > 0
                    ? String.format(Locale.US, "%.2f", s.memUsedKb / 1048576.0) : "").append(',');
            b.append(n(s.cpuTemp)).append(',');
            b.append(n(s.batTemp)).append(',');
            b.append(s.fps >= 0 ? String.format(Locale.US, "%.1f", s.fps) : "").append(',');
            b.append(n(s.watts)).append(',');
            b.append(n(s.gpu)).append(',');
            b.append(s.level >= 0 ? String.valueOf(s.level) : "");
            b.append('\n');
        }
        return b.toString();
    }

    private static String n(float v) {
        return Float.isNaN(v) ? "" : String.format(Locale.US, "%.2f", v);
    }

    /** Write the CSV to Downloads. Returns the path, or null. */
    static String writeCsv(Context c, Recording r) {
        String name = "pulse-" + safe(r.id) + ".csv";
        String body = csv(r);
        String out = Shell.run("cat > " + DIR + "/" + name + " <<'PULSE_CSV'\n"
                + body + "PULSE_CSV\necho WROTE");
        return out != null && out.contains("WROTE") ? DIR + "/" + name : null;
    }

    /**
     * Render the curves to a PNG and write it to Downloads.
     *
     * The chart is built and drawn OFF-SCREEN: it is measured and laid out at a
     * fixed size, then drawn into a bitmap rather than to a window. That reuses the
     * exact drawing code the detail screen uses, so the exported picture and the
     * on-screen chart cannot drift apart.
     *
     * The picture is the same pair the detail screen opens with: frame rate against
     * CPU load, one metric per axis. Extra series are ignored - a third scale would
     * make the picture unreadable, which is the whole point of exporting it.
     */
    static String writePng(Context c, Recording r) {
        try {
            int w = 1080, h = 540;
            ChartView chart = new ChartView(c);
            List<ChartView.Series> series = new ArrayList<>();
            for (String key : new String[]{"fps", "cpu"}) {
                Metric m = Metric.byKey(key);
                if (m == null) continue;
                float[] v = values(r, m);
                int n = 0;
                for (float x : v) if (!Float.isNaN(x)) n++;
                if (n < 2) continue;
                series.add(new ChartView.Series(m.shortLabel, m.color(), v,
                        m.unit, m.decimals, m.key.equals("w")));
            }
            if (series.size() < 2) return null;
            chart.setCombined(series);
            chart.setXLabels(null, null, fmtDur(r.spanMs()));

            chart.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,
                            android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(h,
                            android.view.View.MeasureSpec.EXACTLY));
            chart.layout(0, 0, w, h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Theme.BG);
            chart.draw(canvas);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);

            String name = "pulse-" + safe(r.id) + ".png";
            String out = Shell.run("echo '" + b64 + "' | base64 -d > " + DIR + "/" + name
                    + " && echo WROTE");
            return out != null && out.contains("WROTE") ? DIR + "/" + name : null;
        } catch (Throwable t) {
            Diag.log("png export failed", t);
            return null;
        }
    }

    private static float[] values(Recording r, Metric m) {
        float[] v = new float[r.samples.size()];
        for (int i = 0; i < v.length; i++) {
            Sample s = r.samples.get(i);
            switch (m.key) {
                case "cpu":  v[i] = s.cpuPct; break;
                case "mem":  v[i] = s.memUsedKb / 1048576f; break;
                case "tcpu": v[i] = s.cpuTemp; break;
                case "tbat": v[i] = s.batTemp; break;
                case "fps":  v[i] = s.fps < 0 ? Float.NaN : s.fps; break;
                case "gpu":  v[i] = s.gpu; break;
                case "w":    v[i] = s.watts; break;
                case "batt": v[i] = s.level >= 0 ? s.level : Float.NaN; break;
                default:     v[i] = Float.NaN;
            }
        }
        return v;
    }

    private static String safe(String id) {
        if (id == null || id.isEmpty()) return "recording";
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String fmtDur(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + " s";
        return (sec / 60) + " m " + (sec % 60) + " s";
    }
}
