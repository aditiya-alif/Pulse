// language: Java, file: SystemActivity.java, target: Android 16 (API 36)
// System: the device itself, and getting data out of Pulse.
//
// THIS IS THE INFORMATION CORNER, AND NOTHING ELSE. It answers "what is this
// device", "how much room is left", "what sensors does it have", "how do I get my
// recordings out". Anything that CHANGES the device belongs on another screen - the
// input-automation card that used to live here was exactly that mistake: a "System"
// page offering to type into other apps is in the wrong place, and its purpose was
// never clear from the screen itself.
//
// Long lists are collapsed behind a tap rather than printed inline: 45 sensors as
// a wall of text pushes everything else off the screen, so the card shows the count
// and opens the full list on demand.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SystemActivity extends Screen {

    private LinearLayout infoHost, storageHost, sensorHost, exportHost, appSizeHost;

    /** Every sensor's display name, kept so the sheet can list them all. */
    private final List<String> sensorNames = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = scaffold();
        addBackBar("System");
        addHeading("System", "This device, and your data.");

        // Device identity.
        LinearLayout info = card("Device");
        infoHost = column();
        info.addView(infoHost);
        col.addView(info);

        // Storage.
        LinearLayout store = card("Storage");
        storageHost = column();
        store.addView(storageHost);
        col.addView(store);

        // Per-app storage: the biggest data directories, so a reader can see what
        // is actually filling the phone rather than just the total.
        LinearLayout biggest = card("Biggest app data");
        biggest.addView(text("The largest app data folders, read from disk.",
                11.5f, Theme.MUTED, Typeface.NORMAL));
        appSizeHost = column();
        LinearLayout.LayoutParams aslp = wrap();
        aslp.topMargin = Ui.dp(this, 10);
        appSizeHost.setLayoutParams(aslp);
        biggest.addView(appSizeHost);
        col.addView(biggest);

        // Sensors: a summary that opens the full list. 45 rows inline would bury
        // every other card, so the count is shown and the list is a tap away.
        LinearLayout sensors = card("Sensors");
        sensorHost = column();
        sensors.addView(sensorHost);
        col.addView(sensors);

        // Diagnostics.
        LinearLayout tools = card("Tools");
        tools.addView(text("A bug report is a full diagnostic bundle the device writes "
                + "to Downloads. It takes a while and is large.", 11.5f, Theme.MUTED,
                Typeface.NORMAL));
        LinearLayout.LayoutParams tlp2 = wrap();
        tlp2.topMargin = Ui.dp(this, 10);
        tools.addView(button("Write bug report", v -> {
            Anim.bounce(v);
            confirm("Write a bug report?",
                    "The device will collect a full diagnostic bundle and write it to "
                            + "Downloads. This takes a minute or two.",
                    "Write", Theme.ACCENT, () -> new Thread(() -> {
                        Shell.run("bugreport /sdcard/Download/ 2>/dev/null &");
                        main.post(() -> toast("Bug report started · check Downloads"));
                    }, "pulse-bugreport").start());
        }), tlp2);
        col.addView(tools);

        // Export recordings.
        LinearLayout exp = card("Export recordings");
        exp.addView(text("Write a recording to Downloads as a spreadsheet (CSV) or a "
                + "picture of its curves (PNG).", 11.5f, Theme.MUTED, Typeface.NORMAL));
        exportHost = column();
        LinearLayout.LayoutParams elp = wrap();
        elp.topMargin = Ui.dp(this, 12);
        exportHost.setLayoutParams(elp);
        exp.addView(exportHost);
        col.addView(exp);

        loadInfo();
        loadExport();
        loadAppSizes();
        // Storage and app counts change slowly; a slow refresh keeps the card
        // filled even if the first read ran before the link was ready.
        autoRefresh(5000);
    }

    @Override protected void onTick() {
        loadInfo();
    }

    /**
     * The largest app data folders.
     *
     * `du` over /data/data needs root, but the shell can still measure its own
     * view of the sizes via `du -s` on the app data paths it is allowed to read -
     * and `dumpsys diskstats` / the package manager give the installed size. This
     * uses `du` on the readable data dir and falls back gracefully: whatever it
     * cannot read is simply absent, and the list shows what it got.
     */
    private void loadAppSizes() {
        if (firstLoad) showLoading(appSizeHost, 4);
        new Thread(() -> {
            Shell.ensure(this);
            // Sum the data dir of every third-party app; the shell can stat most of
            // them, and the ones it cannot are skipped by the 2>/dev/null.
            String cmd = "for p in $(pm list packages -3 | sed 's/package://'); do "
                    + "s=$(du -sk /data/data/$p 2>/dev/null | awk '{print $1}'); "
                    + "[ -n \"$s\" ] && echo \"$s $p\"; done | sort -rn | head -12";
            String out = Shell.run(cmd);
            main.post(() -> renderAppSizes(out));
        }, "pulse-appsizes").start();
    }

    private void renderAppSizes(String out) {
        appSizeHost.removeAllViews();
        hideLoading(appSizeHost);
        if (out == null || out.trim().isEmpty()) {
            appSizeHost.addView(text("App data sizes are not readable without root.",
                    11.5f, Theme.MUTED, Typeface.NORMAL));
            endFirstLoad();
            return;
        }
        int i = 0;
        for (String line : out.split("\n")) {
            String[] f = line.trim().split("\\s+", 2);
            if (f.length < 2) continue;
            long kb;
            try { kb = Long.parseLong(f[0]); } catch (Throwable t) { continue; }
            String pkg = f[1].trim();
            AppIcon.Info info = AppIcon.get(this, pkg);

            LinearLayout r = row();
            r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            r.addView(text(info.label.isEmpty() ? pkg : info.label, 13.5f, Theme.TEXT,
                    Typeface.NORMAL), new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView v = text(kb >= 1048576
                            ? String.format(Locale.US, "%.1f GB", kb / 1048576.0)
                            : String.format(Locale.US, "%.0f MB", kb / 1024.0),
                    13, Theme.SERIES[1], Typeface.BOLD);
            v.setGravity(android.view.Gravity.END);
            r.addView(v);
            reveal(r, i);
            appSizeHost.addView(r);
            i++;
        }
        if (i == 0) {
            appSizeHost.addView(text("App data sizes are not readable without root.",
                    11.5f, Theme.MUTED, Typeface.NORMAL));
        }
        endFirstLoad();
    }

    // ------------------------------------------------------------------ info

    /** One pass at a time; see BatteryActivity.refreshing for why. */
    private volatile boolean refreshing;

    private void loadInfo() {
        if (refreshing) return;
        refreshing = true;
        if (firstLoad) {
            showLoading(infoHost, 5);
            showLoading(storageHost, 2);
        }
        new Thread(() -> {
            // This screen may be the first to run after launch; without a link every
            // reading below is null and the card starts empty. Connect first.
            Shell.ensure(this);
            // One batched round trip instead of eight sequential ones - the app list
            // and the sensor dump are slow, and one after another left the card
            // blank for seconds.
            java.util.LinkedHashMap<String, String> cmds = new java.util.LinkedHashMap<>();
            cmds.put("model", "getprop ro.product.model");
            cmds.put("maker", "getprop ro.product.manufacturer");
            cmds.put("rel", "getprop ro.build.version.release");
            cmds.put("sdk", "getprop ro.build.version.sdk");
            cmds.put("patch", "getprop ro.build.version.security_patch");
            cmds.put("abi", "getprop ro.product.cpu.abi");
            cmds.put("disk", "df -h /data | tail -1");
            cmds.put("apps", "pm list packages");
            cmds.put("sys", "pm list packages -s");
            cmds.put("third", "pm list packages -3");
            cmds.put("sensors", "dumpsys sensorservice");
            java.util.Map<String, String> out = Shell.batched(cmds);
            if (out.isEmpty()) {
                // No link. Remove the placeholders here, or they stay attached and
                // keep animating for as long as the screen is in front.
                main.post(() -> {
                    refreshing = false;
                    hideLoading(infoHost);
                    hideLoading(storageHost);
                    if (infoHost.getChildCount() == 0) {
                        addInfo(infoHost, "Status", "Not connected");
                    }
                    endFirstLoad();
                });
                return;
            }

            final String model = clean(out.get("model"));
            final String maker = clean(out.get("maker"));
            final String rel = clean(out.get("rel"));
            final String sdk = clean(out.get("sdk"));
            final String patch = clean(out.get("patch"));
            final String abi = clean(out.get("abi"));
            final String disk = clean(out.get("disk"));
            final String apps = out.get("apps");
            final String sys = out.get("sys");
            final String third = out.get("third");
            final String sensors = out.get("sensors");

            main.post(() -> {
                refreshing = false;
                infoHost.removeAllViews();
                hideLoading(infoHost);
                addInfo(infoHost, "Model", model);
                addInfo(infoHost, "Maker", maker);
                addInfo(infoHost, "Android", rel + " · SDK " + sdk);
                addInfo(infoHost, "Security patch", patch);
                addInfo(infoHost, "CPU", abi);

                storageHost.removeAllViews();
                hideLoading(storageHost);
                addInfo(storageHost, "Data", disk.isEmpty() ? "Unavailable"
                        : disk.replaceAll("\\s+", " "));
                // App counts belong to the system overview, not the action list.
                addInfo(storageHost, "Apps", count(apps) + " installed · "
                        + count(third) + " user · " + count(sys) + " system");

                applySensors(sensors);
                endFirstLoad();
            });        }, "pulse-system").start();
    }

    private void addInfo(LinearLayout host, String label, String value) {
        LinearLayout r = row();
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        r.addView(text(label, 12.5f, Theme.MUTED, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 12.5f, Theme.TEXT, Typeface.BOLD);
        v.setGravity(android.view.Gravity.END);
        r.addView(v);
        reveal(r, host.getChildCount());
        host.addView(r);
    }

    /**
     * The sensor summary: a count and a few names, with the whole list a tap away.
     *
     * The device reports 45 sensors, and printing all of them inline buried every
     * other card on this screen. The card now states the count and shows the first
     * few, and tapping it opens the full list in a fixed-height sheet that scrolls -
     * the same shape as the pairing prompt, so a long list never grows the page.
     */
    private void applySensors(String out) {
        sensorHost.removeAllViews();
        sensorNames.clear();
        if (out == null) {
            sensorHost.addView(text("Unavailable.", 12, Theme.MUTED, Typeface.NORMAL));
            return;
        }

        // The list section: one line per sensor, "0x00000001) name | vendor | ...".
        // The ")" after the id is part of the format - without it nothing matches,
        // which is why the list came back empty.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^0x[0-9a-f]+\\)\\s+([^|\\n]+?)\\s*\\|",
                        java.util.regex.Pattern.MULTILINE)
                .matcher(out);
        while (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty()) sensorNames.add(name);
        }

        java.util.regex.Matcher c = java.util.regex.Pattern
                .compile("Total (\\d+) h/w sensors").matcher(out);
        int hwTotal = c.find() ? Integer.parseInt(c.group(1)) : sensorNames.size();

        // The device counts HARDWARE sensors in its header, but the list also
        // includes the fusion/virtual ones it derives from them - so the two numbers
        // differ (45 vs 53 here). Saying both is honest; showing only the header
        // count beside a list of 53 would look wrong.
        String summaryText = sensorNames.isEmpty()
                ? hwTotal + " hardware sensors"
                : sensorNames.size() + " sensors · " + hwTotal + " hardware";
        TextView summary = text(summaryText, 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
        sensorHost.addView(summary);
        if (sensorNames.isEmpty()) {
            sensorHost.addView(text("The list could not be read.", 11.5f, Theme.MUTED,
                    Typeface.NORMAL));
            return;
        }
        // A taste of the list, not the whole thing.
        int preview = Math.min(sensorNames.size(), 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < preview; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(sensorNames.get(i));
        }
        if (sensorNames.size() > preview) sb.append(" · …");
        TextView names = text(sb.toString(), 11.5f, Theme.MUTED, Typeface.NORMAL);
        names.setLineSpacing(Ui.dp(this, 1), 1f);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = Ui.dp(this, 6);
        sensorHost.addView(names, nlp);

        // The whole list, on a tap. Same card shape as the pairing prompt, fixed
        // height, scrolling, no blur behind it.
        TextView open = pill("Show all " + sensorNames.size(), Theme.CARD_HI, Theme.TEXT,
                v -> {
                    Anim.bounce(v);
                    showSensorSheet();
                });
        LinearLayout.LayoutParams olp = wrap();
        olp.topMargin = Ui.dp(this, 12);
        sensorHost.addView(open, olp);
    }

    /** The full sensor list in a fixed-height, scrolling card. */
    private void showSensorSheet() {
        Sheet s = sheet("Sensors · " + sensorNames.size());
        // A sheet row per sensor. The sheet itself scrolls, so a long list is fine.
        for (String name : sensorNames) {
            TextView row = text("·  " + name, 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
            row.setLineSpacing(Ui.dp(this, 1), 1f);
            LinearLayout.LayoutParams lp = wrap();
            lp.bottomMargin = Ui.dp(this, 5);
            row.setLayoutParams(lp);
            s.addView(row);
        }
    }

    private static int count(String out) {
        if (out == null) return 0;
        int n = 0, i = 0;
        while ((i = out.indexOf("package:", i)) >= 0) { n++; i += 8; }
        return n;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    // ---------------------------------------------------------------- export

    private void loadExport() {
        new Thread(() -> {
            List<Recording> recs = new Store(this).list();
            main.post(() -> renderExport(recs));
        }, "pulse-export").start();
    }

    private void renderExport(List<Recording> recs) {
        exportHost.removeAllViews();
        if (recs.isEmpty()) {
            exportHost.addView(text("No recordings to export yet.", 11.5f, Theme.MUTED,
                    Typeface.NORMAL));
            return;
        }
        int limit = Math.min(recs.size(), 10);
        for (int i = 0; i < limit; i++) {
            Recording r = recs.get(i);
            AppIcon.Info info = AppIcon.get(this, r.pkg);
            LinearLayout row = row();
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

            LinearLayout mid = column();
            mid.addView(text(info.label.isEmpty() ? r.pkg : info.label, 13.5f, Theme.TEXT,
                    Typeface.BOLD));
            mid.addView(text(r.samples.size() + " samples · " + fmtDur(r.durationMs()),
                    11, Theme.MUTED, Typeface.NORMAL));
            row.addView(mid, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(pill("CSV", Theme.CARD_HI, Theme.TEXT, v -> {
                Anim.bounce(v);
                export(r, true);
            }));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = Ui.dp(this, 8);
            row.addView(pill("PNG", Theme.CARD_HI, Theme.TEXT, v -> {
                Anim.bounce(v);
                export(r, false);
            }), plp);
            exportHost.addView(row);
        }
    }

    private void export(Recording r, boolean csv) {
        new Thread(() -> {
            String path = csv ? Export.writeCsv(this, r) : Export.writePng(this, r);
            main.post(() -> {
                if (path == null) toast("Export failed");
                else toast("Saved to " + path);
            });
        }, "pulse-export-run").start();
    }
}
