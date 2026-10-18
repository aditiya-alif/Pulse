// language: Java, file: Freeze.java, target: Android 16 (API 36)
// Keeping an app from running in the background, and the way back.
//
// THREE STRENGTHS, and the difference between them is what happens when Pulse is
// killed - which is the whole safety story:
//
//   FREEZE (default)  reversible. Force-stop once, then a reaper kills the app
//                     again if it comes back. The reaper lives in Pulse, so
//                     force-stopping Pulse stops the reaping and every frozen app
//                     recovers on its own. Nothing is written into the system, so
//                     there is no state to get stuck in.
//
//   SUSPEND           persistent. `pm suspend` freezes the app at the platform
//                     level and SURVIVES Pulse being killed. Recorded in prefs so
//                     the next launch can offer to release it.
//
//   DISABLE           persistent. `pm disable-user` also hides it from the
//                     launcher. Same caveat, same record, same offer to release.
//
// A protected list keeps the phone usable: system packages, the dialer, the
// launcher, the keyboard, and Pulse itself can never be frozen. Freezing those is
// how a "tweak" app bricks a device, and the cost of never doing it is nil.
package com.adit.pulse;

import android.content.Context;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

final class Freeze {
    /**
     * Never freeze these.
     *
     * The launcher, the dialer, the keyboard and SystemUI are what the user needs
     * to REACH any fix if something goes wrong; freezing one of them leaves a
     * phone that cannot be operated. Pulse and Termux are here so the toolbox
     * cannot disable its own controls.
     */
    private static final Set<String> CRITICAL = new HashSet<>(Arrays.asList(
            "android",
            "com.adit.pulse",
            "com.termux",
            "com.android.systemui",
            "com.android.settings",
            "com.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.android.launcher3",
            "com.miui.home",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.miui.securitycenter",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
    ));

    private Freeze() { }

    /** True when this package must never be frozen, suspended or disabled. */
    static boolean isProtected(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (CRITICAL.contains(pkg)) return true;
        // Storage/media providers back the whole file system; disabling one is
        // how photos and downloads stop working.
        return pkg.startsWith("com.android.providers.")
                || pkg.startsWith("com.google.android.providers.");
    }

    // -------------------------------------------------------- reversible freeze

    static Set<String> frozen(Context c) { return new Prefs(c).frozenApps(); }

    static boolean isFrozen(Context c, String pkg) {
        return frozen(c).contains(pkg);
    }

    /**
     * Set the release deadline if one is wanted and not already set.
     *
     * The window is the user's choice (0 = never). The deadline is set once, when
     * the first app is held, and cleared when everything is released - so extending
     * the hold by freezing another app does not keep pushing the deadline out.
     */
    private static void armAutoRelease(Prefs p) {
        if (p.freezeAutoReleaseHours() <= 0) return;
        if (p.freezeReleaseAt() > 0) return;
        p.setFreezeReleaseAt(System.currentTimeMillis()
                + p.freezeAutoReleaseHours() * 3600_000L);
    }

    /** True when the auto-release deadline has passed and something is held. */
    static boolean autoReleaseDue(Context c) {
        Prefs p = new Prefs(c);
        long at = p.freezeReleaseAt();
        return at > 0 && System.currentTimeMillis() >= at && anythingHeld(c);
    }

    // ----------------------------------------------------------- batch freeze

    /** The saved set - the apps that freeze together when the button is pressed. */
    static Set<String> batch(Context c) { return new Prefs(c).freezeSet(); }

    static boolean inBatch(Context c, String pkg) { return batch(c).contains(pkg); }

    static void addToBatch(Context c, String pkg) {
        if (isProtected(pkg)) return;
        Prefs p = new Prefs(c);
        Set<String> s = p.freezeSet();
        s.add(pkg);
        p.setFreezeSet(s);
    }

    static void removeFromBatch(Context c, String pkg) {
        Prefs p = new Prefs(c);
        Set<String> s = p.freezeSet();
        s.remove(pkg);
        p.setFreezeSet(s);
    }

    /** True when the batch is currently frozen. */
    static boolean batchActive(Context c) {
        Set<String> b = batch(c);
        if (b.isEmpty()) return false;
        return frozen(c).containsAll(b);
    }

    /**
     * Freeze every app in the saved batch, in ONE shell command.
     *
     * The whole batch goes out as a single `am force-stop a; am force-stop b; ...`
     * rather than one call each, so pressing the button costs one round trip no
     * matter how many apps are in the set. Protected apps are skipped.
     */
    static int freezeBatch(Context c) {
        Prefs p = new Prefs(c);
        Set<String> b = p.freezeSet();
        if (b.isEmpty()) return 0;
        StringBuilder cmd = new StringBuilder();
        Set<String> nowFrozen = p.frozenApps();
        int n = 0;
        for (String pkg : b) {
            if (isProtected(pkg)) continue;
            cmd.append("am force-stop ").append(pkg).append("; ");
            nowFrozen.add(pkg);
            n++;
        }
        if (n == 0) return 0;
        p.setFrozenApps(nowFrozen);
        armAutoRelease(p);
        Shell.run(cmd.toString());
        return n;
    }

    /** Release the whole batch. The apps recover on their own; nothing to undo. */
    static int releaseBatch(Context c) {
        Prefs p = new Prefs(c);
        Set<String> b = p.freezeSet();
        Set<String> nowFrozen = p.frozenApps();
        nowFrozen.removeAll(b);
        p.setFrozenApps(nowFrozen);
        return b.size();
    }

    /**
     * One reaper pass: force-stop every frozen app that is actually RUNNING.
     *
     * FORCE-STOP, NOT `am kill`. `am kill` only kills a process the system has
     * already put in the background - an app with a foreground service, or one the
     * user just left, is NOT killed by it. Measured on this device: YouTube
     * survived `am kill` and died to `am force-stop`. Since the whole promise of
     * the freeze is "this stays stopped", `am kill` was too weak - the app kept
     * running and could still post notifications.
     *
     * AND ONLY WHAT IS RUNNING. The check runs on the device in the same round
     * trip: `pidof` each package and force-stop only those that answer. A frozen
     * app is normally already dead, so most passes do no force-stop work at all -
     * which is what makes a short interval cheap. Without this, a 5 s reaper would
     * force-stop every app 12 times a minute for nothing.
     */
    static void reap(Context c) {
        Set<String> s = frozen(c);
        if (s.isEmpty()) return;
        StringBuilder cmd = new StringBuilder(s.size() * 60);
        for (String p : s) {
            cmd.append("pidof ").append(p)
               .append(" >/dev/null 2>&1 && am force-stop ").append(p).append("; ");
        }
        Shell.run(cmd.toString());
    }

    // ------------------------------------------------------- persistent suspend

    static Set<String> suspended(Context c) { return new Prefs(c).suspendedApps(); }

    static boolean suspend(Context c, String pkg) {
        if (isProtected(pkg)) return false;
        String out = Shell.run("pm suspend " + pkg);
        if (out == null) return false;
        Prefs p = new Prefs(c);
        Set<String> s = p.suspendedApps();
        s.add(pkg);
        p.setSuspendedApps(s);
        return true;
    }

    static void unsuspend(Context c, String pkg) {
        Shell.run("pm unsuspend " + pkg);
        Prefs p = new Prefs(c);
        Set<String> s = p.suspendedApps();
        s.remove(pkg);
        p.setSuspendedApps(s);
    }

    // ------------------------------------------------------ persistent disable

    static Set<String> disabled(Context c) { return new Prefs(c).disabledApps(); }

    static boolean disable(Context c, String pkg) {
        if (isProtected(pkg)) return false;
        String out = Shell.run("pm disable-user --user 0 " + pkg);
        if (out == null) return false;
        Prefs p = new Prefs(c);
        Set<String> s = p.disabledApps();
        s.add(pkg);
        p.setDisabledApps(s);
        return true;
    }

    static void enable(Context c, String pkg) {
        Shell.run("pm enable --user 0 " + pkg);
        Prefs p = new Prefs(c);
        Set<String> s = p.disabledApps();
        s.remove(pkg);
        p.setDisabledApps(s);
    }

    // ------------------------------------------------------------------ bulk

    /**
     * Undo everything, in one command. The escape hatch: whatever the user did,
     * this returns every app to normal.
     */
    static void releaseAll(Context c) {
        Prefs p = new Prefs(c);
        Set<String> sus = p.suspendedApps();
        Set<String> dis = p.disabledApps();
        if (!sus.isEmpty() || !dis.isEmpty()) {
            StringBuilder cmd = new StringBuilder();
            for (String s : sus) cmd.append("pm unsuspend ").append(s).append("; ");
            for (String s : dis) cmd.append("pm enable --user 0 ").append(s).append("; ");
            Shell.run(cmd.toString());
        }
        p.setSuspendedApps(new HashSet<>());
        p.setDisabledApps(new HashSet<>());
        p.setFrozenApps(new HashSet<>());
        // Everything is released, so the auto-release deadline is cleared too -
        // the next freeze arms a fresh one.
        p.setFreezeReleaseAt(0L);
    }

    /** True when anything is held, so the UI can show a release affordance. */
    static boolean anythingHeld(Context c) {
        Prefs p = new Prefs(c);
        return !p.frozenApps().isEmpty()
                || !p.suspendedApps().isEmpty()
                || !p.disabledApps().isEmpty();
    }

    /**
     * Every app that shows in the launcher - installed OR built in.
     *
     * `pm list packages -3` is the wrong filter for this screen: on this device it
     * hides Chrome, the Play Store, Maps and Gmail, because they ship as SYSTEM
     * packages - yet they are ordinary apps the user opens and would expect to find
     * here. The launcher intent is what actually separates "an app" from "a
     * component": anything with a MAIN/LAUNCHER activity is something the user can
     * open, whether or not it can be uninstalled.
     *
     * `cmd package query-activities` prints one block per match with the component
     * on its own indented line, so the package is taken from those lines.
     */
    static Set<String> launchable(Context c) {
        Set<String> out = new LinkedHashSet<>();
        String raw = Shell.run("cmd package query-activities --brief "
                + "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER");
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            // "com.android.chrome/com.google.android.apps.chrome.Main"
            int slash = t.indexOf('/');
            if (slash <= 0) continue;
            String pkg = t.substring(0, slash);
            // Reject the header and any prose line: a package name has a dot and no
            // spaces.
            if (!pkg.contains(".") || pkg.contains(" ")) continue;
            out.add(pkg);
        }
        return out;
    }
}
