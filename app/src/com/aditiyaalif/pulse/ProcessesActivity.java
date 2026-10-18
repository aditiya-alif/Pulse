// language: Java, file: ProcessesActivity.java, target: Android 16 (API 36)
// Processes: what is running and what it costs.
//
// The source is `dumpsys cpuinfo`, which reports each process's share of the CPU
// over the last interval directly - no sampling loop of our own. It refreshes on a
// timer while the screen is open, so the list is live rather than a snapshot.
//
// Sortable by CPU, memory or name: a process list that can only sort one way is
// only half a tool. Memory comes from `dumpsys meminfo`, read in the same batch as
// the CPU dump so the two always describe the same moment.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProcessesActivity extends Screen {

    private static final class Proc {
        final String pkg, label;
        final int pid;
        float cpu;
        float memMb = -1;
        Proc(String pkg, int pid, float cpu) {
            this.pkg = pkg; this.pid = pid; this.cpu = cpu;
            int colon = pkg.indexOf(':');
            this.label = colon > 0 ? pkg.substring(0, colon) : pkg;
        }
    }

    /** Sort modes, in the order the chips show them. */
    private static final String[] SORTS = { "CPU", "Memory", "Name" };
    private int sort = 0;

    private LinearLayout listHost, sortRow;
    private TextView summary;
    private String query = "";
    private List<Proc> procs = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = scaffold();
        addBackBar("Processes");
        addHeading("Processes", "What is running right now.");

        LinearLayout top = card(null);
        LinearLayout tr = row();
        summary = text("Reading…", 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
        tr.addView(summary, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tr.addView(pill("Refresh", Theme.ACCENT, 0xFFFFFFFF, v -> {
            Anim.bounce(v);
            load();
        }));
        top.addView(tr);
        top.addView(divider());
        top.addView(groupLabel("Sort by"));
        sortRow = row();
        top.addView(sortRow);
        rebuildSortChips();
        col.addView(top);

        LinearLayout searchCard = card(null);
        EditText search = new EditText(this);
        search.setHint("Filter by name");
        search.setHintTextColor(Theme.MUTED);
        search.setTextColor(Theme.TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.US);
                render();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchCard.addView(search);
        col.addView(searchCard);

        listHost = column();
        col.addView(listHost);
        load();
        // Live: re-read while the screen is open so the shares are current, and so
        // an empty first read (link not up yet) fills itself in.
        autoRefresh(2500);
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    @Override protected void onTick() {
        load();
    }

    private void rebuildSortChips() {
        sortRow.removeAllViews();
        for (int i = 0; i < SORTS.length; i++) {
            final int idx = i;
            boolean on = idx == sort;
            TextView t = pill(SORTS[i], on ? Theme.ACCENT : Theme.CARD_HI,
                    on ? 0xFFFFFFFF : Theme.TEXT_DIM, v -> {
                        Anim.bounce(v);
                        if (idx == sort) return;
                        sort = idx;
                        rebuildSortChips();
                        render();
                    });
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            sortRow.addView(t);
            if (i < SORTS.length - 1) {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
                sortRow.addView(gap);
            }
        }
    }

    // ------------------------------------------------------------------ load

    /** One pass at a time; see BatteryActivity.refreshing for why. */
    private volatile boolean refreshing;

    private void load() {
        if (refreshing) return;
        refreshing = true;
        // Placeholder only on the FIRST load. On a refresh the list is already
        // there, and covering it with a shimmer every tick was the flicker.
        if (firstLoad) main.post(() -> showLoading(listHost, 5));
        new Thread(() -> {
            // The transport may be down when this screen is opened on its own;
            // without a link the dumps are null and the list reads "Unavailable".
            Shell.ensure(this);
            LinkedHashMap<String, String> cmds = new LinkedHashMap<>();
            cmds.put("cpu", "dumpsys cpuinfo");
            cmds.put("mem", "dumpsys meminfo");
            Map<String, String> out = Shell.batched(cmds);

            List<Proc> parsed = parse(out.get("cpu"));
            applyMemory(parsed, out.get("mem"));
            boolean linked = !out.isEmpty();

            main.post(() -> {
                refreshing = false;
                if (linked) procs = parsed;
                summary.setText(!linked ? "Not connected"
                        : procs.isEmpty() ? "No processes reported"
                        : procs.size() + " processes · by " + SORTS[sort].toLowerCase(Locale.US));
                render();
            });
        }, "pulse-procs").start();
    }

    /**
     * Parse the per-process lines: "  15% 1234/com.example: 10% user + 5% kernel".
     * The "TOTAL" and header lines do not match and are skipped.
     *
     * MULTILINE is required: the pattern is anchored with `^`, and without the flag
     * `^` only matches the start of the WHOLE dump - so every process after the
     * first line was missed and the list came back empty.
     */
    private static List<Proc> parse(String out) {
        List<Proc> list = new ArrayList<>();
        if (out == null) return list;
        Matcher m = Pattern.compile("^\\s*([0-9.]+)%\\s+(\\d+)/(\\S+?):", Pattern.MULTILINE)
                .matcher(out);
        while (m.find()) {
            try {
                list.add(new Proc(m.group(3), Integer.parseInt(m.group(2)),
                        Float.parseFloat(m.group(1))));
            } catch (Throwable ignored) { }
        }
        return list;
    }

    /**
     * Fold each process's resident size into the list, keyed by pid.
     *
     * `dumpsys meminfo` opens with a "Total RSS by process" table whose lines read
     * "    348,028K: com.aditiyaalif.pulse (pid 21812 / activities)". That is the modern
     * form; the older form ("** pid N **" headers with a "TOTAL PSS:" line) still
     * appears on some builds, so both are handled - the old one is tried first and
     * the new one fills in whatever it missed.
     */
    private static void applyMemory(List<Proc> procs, String meminfo) {
        if (meminfo == null || procs.isEmpty()) return;
        Map<Integer, Float> byPid = new HashMap<>();

        // Modern form: "<size>K: <name> (pid <n>...)". The name may contain spaces
        // and colons, so the pid is taken from the parentheses, not from splitting.
        Matcher modern = Pattern.compile("^\\s*([0-9,]+)K:\\s+.+?\\(pid\\s+(\\d+)",
                Pattern.MULTILINE).matcher(meminfo);
        while (modern.find()) {
            try {
                float kb = Float.parseFloat(modern.group(1).replace(",", ""));
                byPid.put(Integer.parseInt(modern.group(2)), kb / 1024f);
            } catch (Throwable ignored) { }
        }

        // Legacy form, in case this build prints it.
        if (byPid.isEmpty()) {
            int pid = -1;
            for (String line : meminfo.split("\n")) {
                Matcher h = Pattern.compile("\\*\\*\\s*pid\\s+(\\d+)\\s*\\*\\*").matcher(line);
                if (h.find()) { pid = Integer.parseInt(h.group(1)); continue; }
                if (pid < 0) continue;
                Matcher t = Pattern.compile("TOTAL PSS:\\s*([0-9,]+)").matcher(line);
                if (t.find()) {
                    byPid.put(pid,
                            Float.parseFloat(t.group(1).replace(",", "")) / 1024f);
                    pid = -1;
                }
            }
        }

        for (Proc pr : procs) {
            Float mb = byPid.get(pr.pid);
            if (mb != null) pr.memMb = mb;
        }
    }

    private void render() {
        if (listHost == null) return;
        hideLoading(listHost);
        List<Proc> sorted = new ArrayList<>(procs);
        switch (sort) {
            case 0: Collections.sort(sorted, (a, b) -> Float.compare(b.cpu, a.cpu)); break;
            case 1: Collections.sort(sorted, (a, b) -> Float.compare(b.memMb, a.memMb)); break;
            default: Collections.sort(sorted, (a, b) ->
                    a.label.compareToIgnoreCase(b.label)); break;
        }

        listHost.removeAllViews();
        int shown = 0;
        for (Proc p : sorted) {
            if (!query.isEmpty() && !p.pkg.toLowerCase(Locale.US).contains(query)) continue;
            View row = procRow(p);
            reveal(row, shown);
            listHost.addView(row);
            shown++;
            if (shown >= 60) break;
        }
        if (shown == 0) {
            LinearLayout empty = card(null);
            empty.addView(text(procs.isEmpty() ? "No processes reported."
                    : "Nothing matches.", 12.5f, Theme.MUTED, Typeface.NORMAL));
            listHost.addView(empty);
        }
        endFirstLoad();
    }

    private View procRow(Proc p) {
        LinearLayout card = card(null);
        LinearLayout r = row();
        r.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));

        AppIcon.Info info = AppIcon.get(this, p.label);
        // A process with no resolvable package (a kernel thread, a system helper)
        // gets a drawn fallback rather than an empty gap, so the rows stay aligned.
        View icon = info.icon != null ? appIconView(info.icon) : fallbackIcon();
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32));
        ilp.rightMargin = Ui.dp(this, 10);
        r.addView(icon, ilp);

        LinearLayout mid = column();
        mid.addView(text(info.label.isEmpty() ? p.pkg : info.label, 14, Theme.TEXT,
                Typeface.BOLD));
        mid.addView(text("pid " + p.pid, 11, Theme.MUTED, Typeface.NORMAL));
        r.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // RAM, as its own value beside the CPU share: the two things a reader
        // looks for on this screen. A dash when the size was not reported.
        LinearLayout vals = column();
        TextView ram = text(p.memMb >= 0
                ? String.format(Locale.US, "%.0f MB", p.memMb) : "--",
                13, Theme.SERIES[1], Typeface.BOLD);
        ram.setGravity(Gravity.END);
        vals.addView(ram);
        int color = p.cpu >= 25 ? Theme.CRITICAL : p.cpu >= 10 ? Theme.WARNING : Theme.TEXT_DIM;
        TextView cpu = text(String.format(Locale.US, "%.1f%%", p.cpu), 12, color,
                Typeface.NORMAL);
        cpu.setGravity(Gravity.END);
        vals.addView(cpu);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 74), LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = Ui.dp(this, 8);
        r.addView(vals, vlp);

        r.setOnClickListener(v -> {
            Anim.bounce(v);
            sheet(info.label.isEmpty() ? p.pkg : info.label)
                    .action("Threads", Theme.TEXT, () -> showThreads(p))
                    .action("Force stop", Theme.TEXT, () -> act("am force-stop " + p.label, "Stopped"))
                    .action("Kill background", Theme.TEXT, () -> act("am kill " + p.label, "Killed"))
                    .action("App info", Theme.TEXT, () -> openDeviceSettings(
                            "android.settings.APPLICATION_DETAILS_SETTINGS -d package:"
                                    + p.label));
        });
        card.addView(r);
        return card;
    }

    /**
     * An app icon at a fixed size, scaled to fit and centred.
     *
     * A plain ImageView with a fixed box lets the drawable render at its own
     * intrinsic size, which is why some icons came out tiny (a small adaptive
     * icon) and others filled the row. FIT_CENTER inside a set box makes every
     * icon the same visual size.
     */
    private View appIconView(android.graphics.drawable.Drawable d) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageDrawable(d);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** A drawn stand-in for a process with no package: a rounded grey tile. */
    private View fallbackIcon() {
        View v = new View(this);
        v.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 8)));
        return v;
    }

    private void showThreads(Proc p) {
        new Thread(() -> {
            String out = Shell.run("ls /proc/" + p.pid + "/task 2>/dev/null | wc -l");
            main.post(() -> {
                String n = out == null ? "?" : out.trim();
                toast(p.label + ": " + n + " threads");
            });
        }, "pulse-threads").start();
    }

    private void act(String cmd, String done) {
        new Thread(() -> {
            Shell.run(cmd);
            main.post(() -> { toast(done); load(); });
        }, "pulse-proc-act").start();
    }
}
