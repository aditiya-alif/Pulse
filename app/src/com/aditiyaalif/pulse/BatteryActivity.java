// language: Java, file: BatteryActivity.java, target: Android 16 (API 36)
// Battery: what is using power, and why.
//
// THIS SCREEN IS ORGANISED AS QUESTIONS, NOT AS DATA. The readings come first
// ("what is the battery doing"), then the three things that actually drain it -
// apps, wake locks, alarms - then sleep, then history, then the controls. Each
// card carries a one-line explanation, because a number like "wake locks held: 5"
// means nothing without saying what a wake lock is.
//
// The controls are LAST and are labelled as what they are: Doze is an advanced
// system behaviour, and the whitelist is a list you should be able to READ before
// you clear it - so the list is shown, not just a button that empties it.
//
// SPEED MATTERS HERE. `dumpsys batterystats` alone is ~1.9 s and `dumpsys battery`
// ~1.8 s; run one after another that is several seconds of empty screen every time
// the screen is opened. So all the reads go out as ONE batched round trip, and the
// last result is kept so a reopened screen shows it immediately instead of
// flashing "--" while the device answers again.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
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

public class BatteryActivity extends Screen {

    /**
     * The last parsed result, kept across opens.
     *
     * Static because it should survive this activity being destroyed and rebuilt -
     * the point is that a reopen shows the previous reading at once. It is only a
     * cache: the fresh read replaces it as soon as it lands.
     */
    private static final class Snapshot {
        int level = -1, tempTenths = -1, voltMv = -1, health = -1, status = -1;
        long currentUa;
        String deep = "?", light = "?";
        List<String[]> drain = new ArrayList<>();
        /** Wake locks as {pkg, seconds}, heaviest first. */
        List<String[]> wakelocks = new ArrayList<>();
        /** Alarms as {pkg, wakeupCount}, heaviest first. */
        List<String[]> alarms = new ArrayList<>();
        /** Deep-sleep summary lines, already worded. */
        List<String> sleepLines = new ArrayList<>();
        String thermal = "--";
        /** Power-history lines, already worded: discharge rate, charge sessions. */
        List<String> powerLines = new ArrayList<>();
        /** Packages exempt from battery optimisation, as {pkg}. */
        List<String> whitelist = new ArrayList<>();
    }
    private static Snapshot cache;

    private TextView level, temp, volt, health, status, current;
    private TextView dozeState, thermalState;
    private LinearLayout drainHost, wakeHost, alarmHost, sleepHost, powerHost, whitelistHost;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = scaffold();
        addBackBar("Battery");
        addHeading("Battery", "What is using power, and why.");

        // ---- 1. The battery right now.
        LinearLayout live = card("Now");
        level = statValue(); temp = statValue(); volt = statValue();
        health = statValue(); status = statValue(); current = statValue();
        live.addView(statRow("Level", level, "Temp", temp, "Voltage", volt));
        LinearLayout r2 = statRow("Health", health, "State", status, "Current", current);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = Ui.dp(this, 12);
        r2.setLayoutParams(r2lp);
        live.addView(r2);
        col.addView(live);

        // ---- 2. Who is draining it.
        LinearLayout drain = card("Biggest drain");
        drain.addView(note("Estimated battery each app has used since the last full charge. "
                + "The top of this list is what to look at first."));
        drainHost = column();
        drain.addView(drainHost);
        col.addView(drain);

        // ---- 3. Who is keeping it awake.
        LinearLayout wl = card("Keeping it awake");
        wl.addView(note("A wake lock stops the phone from going to sleep. These apps "
                + "held one the longest - time the phone could not rest."));
        wakeHost = column();
        wl.addView(wakeHost);
        col.addView(wl);

        // ---- 4. Who is waking it up.
        LinearLayout al = card("Waking it up");
        al.addView(note("Alarms wake the phone from sleep to run something. These apps "
                + "woke it the most - the usual reason a phone drains while idle."));
        alarmHost = column();
        al.addView(alarmHost);
        col.addView(al);

        // ---- 5. How well it is sleeping.
        LinearLayout sleep = card("Sleep");
        sleep.addView(note("How much of the time on battery the phone was actually "
                + "asleep. More doze is better."));
        sleepHost = column();
        sleep.addView(sleepHost);
        col.addView(sleep);

        // ---- 6. The system's own tally.
        LinearLayout hist = card("Power history");
        hist.addView(note("The system's own running tally since the last charge."));
        powerHost = column();
        hist.addView(powerHost);
        col.addView(hist);

        // ---- 7. Read-only: what the system is already doing about power.
        col.addView(optimisationCard());

        // Paint the previous reading straight away if there is one.
        if (cache != null) render(cache);

        // The readings move slowly (level, drain, temperature), and a full batch can
        // take several seconds on the device - so this polls far slower than the
        // live screens. Combined with the in-flight guard, the device gets a real
        // gap between passes.
        autoRefresh(8000);
    }

    /**
     * What the system is already doing about power - READ ONLY, on purpose.
     *
     * There were two buttons here and both were removed, for the same reason: they
     * were developer tools wearing a user feature's clothes, and neither had a way
     * back.
     *
     * "Force doze" / "Wake from doze" are TEST commands. Doze is automatic - the
     * phone enters it on its own when left alone - so forcing it does nothing a
     * user wants, and forcing it OFF can make them miss alarms. There is no real
     * doze setting to offer; that pair is all the system exposes.
     *
     * "Clear the list" emptied the battery-optimisation list with no way to put it
     * back: the app would have to remember the entries, and re-adding them one at a
     * time was never offered. A destructive action with no undo should not exist.
     *
     * What remains is the part that IS useful - seeing the current state, and seeing
     * WHICH apps are exempt - which is information, not a lever.
     */
    private View optimisationCard() {
        LinearLayout card = card("Doze & optimisation");
        card.addView(note("How the system is currently saving power. These are "
                + "readings - the phone manages both of these by itself."));

        card.addView(divider());
        card.addView(groupLabel("Doze"));
        card.addView(note("Doze is the deep sleep the phone enters when left alone. "
                + "It is automatic; there is nothing to switch on."));
        dozeState = text("Reading…", 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
        LinearLayout.LayoutParams dslp = wrap();
        dslp.topMargin = Ui.dp(this, 10);
        card.addView(dozeState, dslp);
        thermalState = text("", 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
        LinearLayout.LayoutParams tslp = wrap();
        tslp.topMargin = Ui.dp(this, 6);
        card.addView(thermalState, tslp);

        card.addView(divider());
        card.addView(groupLabel("Battery optimisation"));
        card.addView(note("Apps on this list are exempt from battery optimisation, so "
                + "the system will not hold them back in the background. That keeps "
                + "them working but costs battery. Manage the list in the phone's own "
                + "Settings - it is not changed from here."));
        whitelistHost = column();
        LinearLayout.LayoutParams wlp = wrap();
        wlp.topMargin = Ui.dp(this, 12);
        whitelistHost.setLayoutParams(wlp);
        card.addView(whitelistHost);

        // A way to the system screen that owns this setting, instead of a button
        // that edits it blindly. That is the right place to change it, and the app
        // can take the user there.
        card.addView(button("Open battery settings", v -> {
            Anim.bounce(v);
            openDeviceSettings("android.settings.BATTERY_SAVER_SETTINGS");
        }));
        return card;
    }

    /** A one-line explanation under a card's title. */
    private TextView note(String s) {
        TextView t = text(s, 11.5f, Theme.MUTED, Typeface.NORMAL);
        t.setLineSpacing(Ui.dp(this, 2), 1f);
        LinearLayout.LayoutParams lp = wrap();
        lp.bottomMargin = Ui.dp(this, 10);
        t.setLayoutParams(lp);
        return t;
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onTick() {
        refresh();
    }

    private View gap(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, dp)));
        return v;
    }

    private TextView statValue() { return text("--", 17, Theme.TEXT, Typeface.BOLD); }

    private LinearLayout statRow(String l1, TextView v1, String l2, TextView v2,
                                 String l3, TextView v3) {
        LinearLayout r = row();
        r.addView(cell(l1, v1));
        r.addView(cell(l2, v2));
        r.addView(cell(l3, v3));
        return r;
    }

    private LinearLayout cell(String label, TextView value) {
        LinearLayout c = column();
        c.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(value);
        c.addView(text(label, 10, Theme.MUTED, Typeface.NORMAL));
        return c;
    }

    // --------------------------------------------------------------- refresh

    /**
     * True while a refresh is in flight.
     *
     * The batch below is eight commands run one after another ON THE DEVICE, and
     * `batterystats` alone is ~1.9 s - so a full pass can outlast its own 3 s
     * timer. Without this guard each tick started another pass while the previous
     * was still running, so the work piled up and the device never got a gap. One
     * pass at a time; a tick that lands mid-pass is simply skipped.
     */
    private volatile boolean refreshing;

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        // Placeholder only on the first load, and only when there is no cached
        // reading to show. A refresh must not cover the values already on screen.
        if (firstLoad && cache == null) {
            main.post(() -> {
                showLoading(drainHost, 4);
                showLoading(wakeHost, 3);
                showLoading(alarmHost, 3);
                showLoading(sleepHost, 3);
                showLoading(powerHost, 3);
                showLoading(whitelistHost, 3);
            });
        }
        new Thread(() -> {
            Shell.ensure(this);
            LinkedHashMap<String, String> cmds = new LinkedHashMap<>();
            cmds.put("bat", "dumpsys battery");
            cmds.put("cur", "cmd battery get -f current_now");
            cmds.put("idle", "cmd deviceidle get deep; cmd deviceidle get light");
            cmds.put("power", "dumpsys power");
            cmds.put("alarm", "dumpsys alarm");
            cmds.put("thermal", "dumpsys thermalservice");
            cmds.put("drain", "dumpsys batterystats --charged");
            cmds.put("uidmap", "pm list packages -U");
            cmds.put("whitelist", "dumpsys deviceidle whitelist");
            Map<String, String> out = Shell.batched(cmds);

            Snapshot snap = parse(out);
            main.post(() -> {
                refreshing = false;
                if (snap == null) {
                    if (cache == null) renderEmpty();
                    return;
                }
                cache = snap;
                render(snap);
            });
        }, "pulse-battery").start();
    }

    /** Parse the batched output into a snapshot. Null when the link was down. */
    private Snapshot parse(Map<String, String> out) {
        if (out.isEmpty()) return null;
        Snapshot s = new Snapshot();

        String bat = out.get("bat");
        if (bat != null) {
            s.level = intField(bat, "level");
            s.tempTenths = intField(bat, "temperature");
            s.voltMv = intField(bat, "voltage");
            s.health = intField(bat, "health");
            s.status = intField(bat, "status");
        }
        String cur = out.get("cur");
        s.currentUa = cur == null ? 0 : firstNumber(cur);

        String idle = out.get("idle");
        if (idle != null) {
            String[] lines = idle.split("\n");
            s.deep = lines.length > 0 ? lines[0].trim() : "?";
            s.light = lines.length > 1 ? lines[1].trim() : "?";
        }

        String power = out.get("power");
        if (power != null) {
            Matcher m = Pattern.compile("Wake Locks: size=(\\d+)").matcher(power);
            if (m.find()) s.sleepLines.add("Wake locks held · " + m.group(1));
        }

        String alarm = out.get("alarm");
        if (alarm != null) {
            int n = 0, i = 0;
            while ((i = alarm.indexOf("Alarm{", i)) >= 0) { n++; i += 6; }
            s.sleepLines.add("Scheduled alarms · " + n);
        }

        String thermal = out.get("thermal");
        if (thermal != null) {
            Matcher m = Pattern.compile("Thermal Status: (\\d+)").matcher(thermal);
            s.thermal = thermalName(m.find() ? Integer.parseInt(m.group(1)) : -1);
        }

        s.drain = parseDrain(out.get("drain"), out.get("uidmap"));
        s.wakelocks = parseWakeLocks(out.get("drain"), out.get("uidmap"));
        s.alarms = parseAlarms(out.get("alarm"));
        s.sleepLines.addAll(parseSleep(out.get("drain")));
        s.powerLines = parsePowerHistory(out.get("drain"));
        s.whitelist = parseWhitelist(out.get("whitelist"));
        return s;
    }

    /**
     * The apps exempt from battery optimisation.
     *
     * `dumpsys deviceidle whitelist` prints THREE comma-separated fields per entry:
     * "system-excidle,com.example,10303" - the source, the package, then the uid.
     * The package is the SECOND field, not everything after the first comma (which
     * would drag the uid along with it).
     */
    private List<String> parseWhitelist(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String l : raw.split("\n")) {
            String[] parts = l.trim().split(",");
            if (parts.length < 2) continue;
            String pkg = parts[1].trim();
            if (pkg.contains(".") && !pkg.contains(" ")) out.add(pkg);
        }
        return out;
    }

    /**
     * The device's own power accounting, in plain words.
     *
     * batterystats keeps a running tally of what has been consumed since the last
     * full charge, and how fast: "Discharge: 4226 mAh" plus a per-hour rate, and
     * the screen-on / screen-off split. Read straight out rather than recomputed -
     * it is the system's own number, and it is what the Settings app shows.
     */
    private List<String> parsePowerHistory(String stats) {
        List<String> out = new ArrayList<>();
        if (stats == null) return out;

        String cap = line(stats, "Capacity:");
        if (cap != null) out.add("Capacity · " + cap);
        String drain = line(stats, "Computed drain:");
        if (drain != null) out.add("Drained since full charge · " + drain);
        String actual = line(stats, "actual drain:");
        if (actual != null) out.add("Actual drain · " + actual);

        // The per-hour rate lines, if this build prints them.
        Matcher m = Pattern.compile("^\\s*(Screen on|Screen off|Discharge|Charge)\\s*:\\s*(.+)$",
                Pattern.MULTILINE).matcher(stats);
        int n = 0;
        while (m.find() && n < 6) {
            out.add(m.group(1).trim() + " · " + m.group(2).trim());
            n++;
        }
        return out;
    }

    /**
     * Who is holding the device awake, heaviest first.
     *
     * batterystats prints an "All partial wake locks" section with one line per
     * lock: "Wake lock u0a354 *job*r/org.telegram.messenger/...: 4m 53s (2 times)".
     * The uid prefix (u0a354) maps to a package the same way the drain table does,
     * so the same uid map is reused. Locks are summed per app, because a reader
     * wants "Telegram held it 5 minutes", not a list of every job lock.
     */
    private List<String[]> parseWakeLocks(String stats, String uidMap) {
        List<String[]> out = new ArrayList<>();
        if (stats == null) return out;
        Map<Integer, String> byUid = uidMap(stats, uidMap);

        int i = stats.indexOf("All partial wake locks");
        if (i < 0) return out;
        int end = stats.indexOf("\n  All ", i + 10);
        String block = end < 0 ? stats.substring(i) : stats.substring(i, end);

        Map<String, Long> byPkg = new java.util.LinkedHashMap<>();
        // "Wake lock u0a354 *job*r/pkg/...: 4m 53s (2 times)" - the uid is "u0a354"
        // (user 0, app id 354 -> uid 10354); a bare number is a system uid.
        Matcher line = Pattern.compile("^\\s*Wake lock\\s+(u\\d+a)?(\\d+)?[^:]*?:\\s*"
                + "(?:(\\d+)m\\s+)?(\\d+)s", Pattern.MULTILINE).matcher(block);
        while (line.find()) {
            long sec = 0;
            if (line.group(3) != null) sec += Long.parseLong(line.group(3)) * 60;
            sec += Long.parseLong(line.group(4));
            String pkg = null;
            if (line.group(1) != null && line.group(2) != null) {
                // "u0a354" -> app id 354 -> uid 10354
                pkg = byUid.get(10000 + Integer.parseInt(line.group(2)));
            }
            if (pkg == null) pkg = "system";
            Long cur = byPkg.get(pkg);
            byPkg.put(pkg, (cur == null ? 0 : cur) + sec);
        }
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : byPkg.entrySet()) {
            rows.add(new String[]{ e.getKey(), String.valueOf(e.getValue()) });
        }
        Collections.sort(rows, (a, b) ->
                Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
        for (int k = 0; k < Math.min(rows.size(), 10); k++) out.add(rows.get(k));
        return out;
    }

    /**
     * Who wakes the device most, heaviest first.
     *
     * `dumpsys alarm` groups by uid under "Alarm Stats:". Each group header reads
     * "1000:com.miui.powerkeeper +2m53s running, 11 wakeups" - the uid maps to a
     * package, and the wakeup count is the number a reader cares about (it is what
     * breaks deep sleep). Groups without a package name are labelled by uid.
     */
    private List<String[]> parseAlarms(String alarm) {
        List<String[]> rows = new ArrayList<>();
        if (alarm == null) return rows;
        Matcher m = Pattern.compile(
                "^\\s*(\\d+):([\\w.]+)?\\s+\\+?\\S+\\s+running,\\s+(\\d+)\\s+wakeups",
                Pattern.MULTILINE).matcher(alarm);
        while (m.find()) {
            String name = m.group(2);
            if (name == null || name.isEmpty()) name = "uid " + m.group(1);
            rows.add(new String[]{ name, m.group(3) });
        }
        Collections.sort(rows, (a, b) ->
                Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
        List<String[]> out = new ArrayList<>();
        for (int k = 0; k < Math.min(rows.size(), 10); k++) out.add(rows.get(k));
        return out;
    }

    /**
     * The deep-sleep summary, in plain words.
     *
     * The interesting numbers are how much of the battery time was spent asleep,
     * how much of that was real doze, and how much charge doze cost. Read straight
     * from the "Time on battery" lines rather than recomputed.
     */
    private List<String> parseSleep(String stats) {
        List<String> out = new ArrayList<>();
        if (stats == null) return out;
        String total = line(stats, "Time on battery:");
        String screenOff = line(stats, "Time on battery screen off:");
        String doze = line(stats, "Time on battery screen doze:");
        if (total != null) out.add("On battery · " + total);
        if (screenOff != null) out.add("Screen off · " + screenOff);
        if (doze != null) out.add("Doze · " + doze);
        return out;
    }

    private static String line(String blob, String key) {
        int i = blob.indexOf(key);
        if (i < 0) return null;
        int end = blob.indexOf('\n', i);
        String s = end < 0 ? blob.substring(i) : blob.substring(i, end);
        return s.substring(key.length()).trim();
    }

    private static Map<Integer, String> uidMap(String stats, String uidMapRaw) {
        Map<Integer, String> byUid = new HashMap<>();
        if (uidMapRaw != null) {
            for (String l : uidMapRaw.split("\n")) {
                String pkg = field(l, "package:");
                long uid = firstNumber(field(l, "uid:"));
                if (pkg != null && uid > 0) byUid.put((int) uid, pkg);
            }
        }
        return byUid;
    }

    /**
     * Map per-uid drain to packages.
     *
     * batterystats reports "UID u0a397: 1378 ...", where u0a397 is user 0, app id
     * 397 - so the real uid is 10397. `pm list packages -U` gives the same pairing
     * the other way round, which is what turns a uid back into an app name.
     */
    private List<String[]> parseDrain(String stats, String uidMap) {
        List<String[]> rows = new ArrayList<>();
        if (stats == null) return rows;

        Map<Integer, String> byUid = new HashMap<>();
        if (uidMap != null) {
            for (String line : uidMap.split("\n")) {
                String pkg = field(line, "package:");
                int uid = (int) firstNumber(field(line, "uid:"));
                if (pkg != null && uid > 0) byUid.put(uid, pkg);
            }
        }

        // MULTILINE: the pattern is `^`-anchored, so without it only the very first
        // line of the whole dump was examined and the ranking came back empty.
        Matcher u = Pattern.compile("^\\s*UID (?:u\\d+a)?(\\d+):\\s*([0-9.]+)",
                Pattern.MULTILINE).matcher(stats);
        while (u.find()) {
            int appId = Integer.parseInt(u.group(1));
            double mah = Double.parseDouble(u.group(2));
            String pkg = byUid.get(10000 + appId);
            if (pkg == null) continue;
            if (Freeze.isProtected(pkg)) continue;   // system noise, not user apps
            rows.add(new String[]{pkg, String.valueOf(mah)});
        }
        Collections.sort(rows, (a, b) ->
                Double.compare(Double.parseDouble(b[1]), Double.parseDouble(a[1])));
        return rows;
    }

    // ----------------------------------------------------------------- render

    private void renderEmpty() {
        level.setText("--");
        temp.setText("--");
        volt.setText("--");
        health.setText("--");
        status.setText("--");
        current.setText("--");
        for (LinearLayout h : new LinearLayout[]{ drainHost, wakeHost, alarmHost,
                sleepHost, powerHost, whitelistHost }) {
            if (h == null) continue;
            h.removeAllViews();
            h.addView(text("Not connected.", 12, Theme.MUTED, Typeface.NORMAL));
        }
    }

    private void render(Snapshot s) {
        // Guard: this runs from a background thread's callback, and a callback that
        // lands while the screen is being torn down would otherwise touch views that
        // no longer exist. Cheap to check, and a crash here loses the whole screen.
        if (level == null || drainHost == null) return;
        hideLoading(drainHost);
        hideLoading(wakeHost);
        hideLoading(alarmHost);
        hideLoading(sleepHost);
        hideLoading(powerHost);
        hideLoading(whitelistHost);
        level.setText(s.level >= 0 ? s.level + "%" : "--");
        temp.setText(s.tempTenths >= 0 ? (s.tempTenths / 10f) + "°" : "--");
        volt.setText(s.voltMv > 0 ? String.format(Locale.US, "%.2fV", s.voltMv / 1000f) : "--");
        health.setText(healthName(s.health));
        status.setText(statusName(s.status));
        current.setText(s.currentUa == 0 ? "--"
                : String.format(Locale.US, "%.0fmA", s.currentUa / 1000.0));

        boolean idle = s.deep.equals("IDLE") || s.light.equals("IDLE");
        dozeState.setText(idle ? "In idle · deep " + s.deep + " · light " + s.light
                : "Active · deep " + s.deep + " · light " + s.light);
        dozeState.setTextColor(idle ? Theme.ACCENT : Theme.TEXT_DIM);

        thermalState.setText("Thermal status: " + s.thermal);
        thermalState.setTextColor("Severe".equals(s.thermal) || "Critical".equals(s.thermal)
                ? Theme.CRITICAL : Theme.TEXT_DIM);

        renderSleep(s.sleepLines);
        renderDrain(s.drain);
        renderWakeLocks(s.wakelocks);
        renderAlarms(s.alarms);
        renderPower(s.powerLines);
        renderWhitelist(s.whitelist);
        endFirstLoad();
    }

    /**
     * The battery-optimisation list, shown so it can be read before it is cleared.
     *
     * With the app's own icon and name, because a package name means nothing; a
     * count line first so the size of the list is known at a glance.
     */
    private void renderWhitelist(List<String> pkgs) {
        whitelistHost.removeAllViews();
        hideLoading(whitelistHost);
        if (pkgs.isEmpty()) {
            whitelistHost.addView(text("Nothing is exempt - the system may hold every "
                    + "app back to save power.", 12, Theme.MUTED, Typeface.NORMAL));
            return;
        }
        whitelistHost.addView(text(pkgs.size() + (pkgs.size() == 1
                        ? " app is exempt from battery optimisation:"
                        : " apps are exempt from battery optimisation:"),
                12, Theme.TEXT_DIM, Typeface.NORMAL));
        int limit = Math.min(pkgs.size(), 12);
        for (int i = 0; i < limit; i++) {
            View r = appStatRow(pkgs.get(i), "", Theme.MUTED);
            reveal(r, i);
            whitelistHost.addView(r);
        }
        if (pkgs.size() > limit) {
            whitelistHost.addView(text("…and " + (pkgs.size() - limit) + " more.",
                    11.5f, Theme.MUTED, Typeface.NORMAL));
        }
    }

    private void renderPower(List<String> lines) {
        powerHost.removeAllViews();
        hideLoading(powerHost);
        if (lines.isEmpty()) {
            powerHost.addView(text("No power history recorded yet.", 12, Theme.MUTED,
                    Typeface.NORMAL));
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            TextView t = text(lines.get(i), 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
            t.setLineSpacing(Ui.dp(this, 2), 1f);
            LinearLayout.LayoutParams lp = wrap();
            if (i > 0) lp.topMargin = Ui.dp(this, 6);
            reveal(t, i);
            powerHost.addView(t, lp);
        }
    }

    private void renderSleep(List<String> lines) {
        sleepHost.removeAllViews();
        if (lines.isEmpty()) {
            sleepHost.addView(text("No battery history yet.", 12, Theme.MUTED, Typeface.NORMAL));
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            TextView t = text(lines.get(i), 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
            t.setLineSpacing(Ui.dp(this, 2), 1f);
            LinearLayout.LayoutParams lp = wrap();
            if (i > 0) lp.topMargin = Ui.dp(this, 6);
            reveal(t, i);
            sleepHost.addView(t, lp);
        }
    }

    /** Rows of {pkg, seconds}. */
    private void renderWakeLocks(List<String[]> rows) {
        wakeHost.removeAllViews();
        if (rows.isEmpty()) {
            wakeHost.addView(text("No wake locks recorded yet.", 12, Theme.MUTED,
                    Typeface.NORMAL));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            View r = appStatRow(rows.get(i)[0], formatDuration(Long.parseLong(rows.get(i)[1])),
                    Theme.SERIES[6]);
            reveal(r, i);
            wakeHost.addView(r);
            if (i < rows.size() - 1) wakeHost.addView(divider());
        }
    }

    /** Rows of {name, wakeup count}. */
    private void renderAlarms(List<String[]> rows) {
        alarmHost.removeAllViews();
        if (rows.isEmpty()) {
            alarmHost.addView(text("No alarms recorded yet.", 12, Theme.MUTED, Typeface.NORMAL));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            View r = appStatRow(rows.get(i)[0], rows.get(i)[1] + "×", Theme.SERIES[3]);
            reveal(r, i);
            alarmHost.addView(r);
            if (i < rows.size() - 1) alarmHost.addView(divider());
        }
    }

    /** A row: app icon + name on the left, a coloured value on the right. */
    private View appStatRow(String name, String value, int color) {
        LinearLayout r = row();
        r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        AppIcon.Info info = AppIcon.get(this, name);
        ImageView icon = new ImageView(this);
        if (info.icon != null) {
            icon.setImageDrawable(info.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            icon.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 6)));
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 26), Ui.dp(this, 26));
        ilp.rightMargin = Ui.dp(this, 10);
        r.addView(icon, ilp);
        r.addView(text(info.label.isEmpty() ? name : info.label, 13.5f, Theme.TEXT,
                Typeface.NORMAL), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 13, color, Typeface.BOLD);
        v.setGravity(Gravity.END);
        r.addView(v);
        return r;
    }

    /** Seconds -> "1h 12m" / "4m 53s" / "38s". */
    private static String formatDuration(long sec) {
        if (sec >= 3600) return (sec / 3600) + "h " + ((sec % 3600) / 60) + "m";
        if (sec >= 60) return (sec / 60) + "m " + (sec % 60) + "s";
        return sec + "s";
    }

    private void renderDrain(List<String[]> rows) {
        drainHost.removeAllViews();
        if (rows.isEmpty()) {
            drainHost.addView(text("No per-app drain recorded yet. This fills in after "
                    + "some time on battery.", 12, Theme.MUTED, Typeface.NORMAL));
            return;
        }
        int limit = Math.min(rows.size(), 12);
        for (int i = 0; i < limit; i++) {
            String[] rr = rows.get(i);
            View row = drainRow(rr[0], Double.parseDouble(rr[1]));
            reveal(row, i);
            drainHost.addView(row);
            if (i < limit - 1) drainHost.addView(divider());
        }
    }

    private View drainRow(String pkg, double mah) {
        LinearLayout r = row();
        r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        AppIcon.Info info = AppIcon.get(this, pkg);
        ImageView icon = new ImageView(this);
        if (info.icon != null) {
            icon.setImageDrawable(info.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            icon.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 6)));
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 26), Ui.dp(this, 26));
        ilp.rightMargin = Ui.dp(this, 10);
        r.addView(icon, ilp);
        r.addView(text(info.label.isEmpty() ? pkg : info.label, 13.5f, Theme.TEXT,
                Typeface.NORMAL), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(String.format(Locale.US, "%.1f mAh", mah), 13, Theme.SERIES[2],
                Typeface.BOLD);
        v.setGravity(Gravity.END);
        r.addView(v);
        return r;
    }

    // --------------------------------------------------------------- parsing

    /**
     * Read a "key: number" field from a dumpsys block.
     *
     * Anchored to the start of a line. Without the anchor this matched the first
     * line CONTAINING the key anywhere, and `dumpsys battery` prints
     * "Max charging voltage: 0" before "voltage: 3823" - so the voltage reading
     * was always 0 and the screen showed "--" no matter how long the user waited.
     */
    private static int intField(String blob, String key) {
        Matcher m = Pattern.compile("^\\s*" + Pattern.quote(key) + ":\\s*(-?\\d+)",
                Pattern.MULTILINE).matcher(blob);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String field(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return null;
        int j = i + key.length();
        StringBuilder b = new StringBuilder();
        while (j < line.length()) {
            char c = line.charAt(j);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') break;
            b.append(c);
            j++;
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static long firstNumber(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("-?\\d+").matcher(s);
        return m.find() ? Long.parseLong(m.group()) : 0;
    }

    private static String healthName(int h) {
        switch (h) {
            case 2: return "Good";
            case 3: return "Overheat";
            case 4: return "Dead";
            case 5: return "Over volt";
            case 6: return "Failure";
            case 7: return "Cold";
            default: return "--";
        }
    }

    private static String statusName(int s) {
        switch (s) {
            case 2: return "Charging";
            case 3: return "Discharging";
            case 4: return "Not charging";
            case 5: return "Full";
            default: return "--";
        }
    }

    private static String thermalName(int s) {
        switch (s) {
            case 0: return "None";
            case 1: return "Light";
            case 2: return "Moderate";
            case 3: return "Severe";
            case 4: return "Critical";
            case 5: return "Emergency";
            case 6: return "Shutdown";
            default: return "--";
        }
    }
}
