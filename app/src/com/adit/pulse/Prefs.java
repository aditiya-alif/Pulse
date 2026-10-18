// language: Java, file: Prefs.java, target: Android 16 (API 36)
package com.adit.pulse;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Everything the app remembers between launches.
 *
 * The pairing flag is the important one: the brief says a device that has been
 * paired once must reconnect on every launch, and stay paired until the user
 * presses reset. The adb key that makes that work lives in files/adbhome (see
 * {@link Adb#home}); this flag only says whether there is a pairing to reconnect
 * to, so the app does not burn discovery time on a device that never paired.
 */
final class Prefs {
    private static final String NAME = "pulse";

    /** Connection modes. Loopback = adbd on a fixed port over 127.0.0.1. */
    static final String WIRELESS = "wireless";
    static final String LOOPBACK = "loopback";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    boolean paired() { return sp.getBoolean("paired", false); }
    void setPaired(boolean v) { sp.edit().putBoolean("paired", v).apply(); }

    /** The last host:port that actually connected, tried first next time. */
    String lastTarget() { return sp.getString("lastTarget", ""); }
    void setLastTarget(String t) { sp.edit().putString("lastTarget", t == null ? "" : t).apply(); }

    /**
     * Which connection mode is active: "wireless" (default) or "loopback".
     *
     * Loopback points the app at 127.0.0.1 - the phone's own internal network -
     * instead of the wireless-debugging port, so the link keeps working with Wi-Fi
     * off, on mobile data only, or in airplane mode. The mode is remembered so a
     * relaunch goes straight back to the same transport.
     */
    String connMode() { return sp.getString("connMode", "wireless"); }
    void setConnMode(String m) {
        sp.edit().putString("connMode", m == null ? "wireless" : m).apply();
    }

    boolean overlayOn() { return sp.getBoolean("overlay", false); }
    void setOverlayOn(boolean v) { sp.edit().putBoolean("overlay", v).apply(); }

    /**
     * Hold a partial wake lock while the overlay runs.
     *
     * Always on: the brief asks that monitoring survive a screen-off press, and
     * without this the sampler's timer is suspended with the CPU and the history
     * gets a hole exactly where the user was not looking. There is no switch for
     * it any more - it is simply how the overlay runs.
     */
    boolean keepAwake() { return sp.getBoolean("keepAwake", true); }

    /**
     * Which items the one-line bar shows, in the user's chosen order.
     *
     * The bar carries five items and can outgrow a narrow screen; letting the
     * user drop one is why this exists rather than a fixed set.
     */
    Set<String> barItems() {
        Set<String> def = new LinkedHashSet<>(Arrays.asList(Bar.DEFAULT_ON));
        Set<String> s = sp.getStringSet("barItems", null);
        if (s == null) return def;
        // Keep the canonical order regardless of how the set came back.
        Set<String> out = new LinkedHashSet<>();
        for (String k : Bar.ids()) if (s.contains(k)) out.add(k);
        return out.isEmpty() ? def : out;
    }

    void setBarItems(Set<String> ids) {
        sp.edit().putStringSet("barItems", new HashSet<>(ids)).apply();
    }

    // ------------------------------------------------------------ freeze sets

    /**
     * Apps the user has frozen. The reaper only runs while this is non-empty, and
     * a fresh Pulse process starts with whatever was left here - so a device
     * rebooted mid-freeze resumes where it left off.
     */
    Set<String> frozenApps() { return stringSet("frozenApps"); }
    void setFrozenApps(Set<String> v) { sp.edit().putStringSet("frozenApps", new HashSet<>(v)).apply(); }

    /**
     * Apps we suspended or disabled - the two PERSISTENT modes.
     *
     * These are recorded separately from the reversible set on purpose: they
     * survive Pulse being killed, so on the next launch the app must offer to
     * release them (see Freeze.pendingRelease). Without this record a user could
     * suspend an app, force-stop Pulse, and have no way back except knowing the
     * command themselves.
     */
    Set<String> suspendedApps() { return stringSet("suspendedApps"); }
    void setSuspendedApps(Set<String> v) { sp.edit().putStringSet("suspendedApps", new HashSet<>(v)).apply(); }

    Set<String> disabledApps() { return stringSet("disabledApps"); }
    void setDisabledApps(Set<String> v) { sp.edit().putStringSet("disabledApps", new HashSet<>(v)).apply(); }

    /**
     * When the current hold should release itself, in wall-clock ms, or 0 for
     * "never".
     *
     * A freeze the user forgets about is a freeze that quietly keeps an app dead
     * for days. An auto-release gives every hold a backstop: set it when a freeze
     * begins, and the reaper releases everything once the time passes. 0 means the
     * user asked for no limit.
     */
    long freezeReleaseAt() { return sp.getLong("freezeReleaseAt", 0L); }
    void setFreezeReleaseAt(long ms) { sp.edit().putLong("freezeReleaseAt", ms).apply(); }

    /** The auto-release window the user picked, in hours (0 = never). */
    int freezeAutoReleaseHours() { return sp.getInt("freezeAutoReleaseHours", 0); }
    void setFreezeAutoReleaseHours(int h) { sp.edit().putInt("freezeAutoReleaseHours", h).apply(); }

    /**
     * The saved batch: apps the user picked to freeze together with one button.
     *
     * Kept apart from {@link #frozenApps()} on purpose. This is the WISH LIST - what
     * should be frozen when the button is pressed. frozenApps() is what IS frozen
     * right now. Keeping them separate is what lets the button be a single press:
     * the list is built once, then freeze/release toggles the whole set.
     */
    Set<String> freezeSet() { return stringSet("freezeSet"); }
    void setFreezeSet(Set<String> v) { sp.edit().putStringSet("freezeSet", new HashSet<>(v)).apply(); }

    private Set<String> stringSet(String key) {
        Set<String> s = sp.getStringSet(key, null);
        return s == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s);
    }

    // ------------------------------------------------------- custom commands

    /**
     * User-defined shell commands, as {name, command} pairs.
     *
     * Stored as JSON rather than a delimited string: a command is arbitrary text
     * that may contain any separator, so splitting on a character would corrupt it.
     */
    java.util.List<String[]> customCommands() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String raw = sp.getString("customCommands", "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String n = o.optString("n", "");
                String c = o.optString("c", "");
                if (!n.isEmpty() && !c.isEmpty()) out.add(new String[]{n, c});
            }
        } catch (Throwable ignored) { }
        return out;
    }

    void addCustomCommand(String name, String cmd) {
        java.util.List<String[]> items = customCommands();
        // Replace an entry with the same name rather than adding a duplicate.
        items.removeIf(p -> p[0].equalsIgnoreCase(name));
        items.add(new String[]{name, cmd});
        saveCustom(items);
    }

    void removeCustomCommand(String name) {
        java.util.List<String[]> items = customCommands();
        items.removeIf(p -> p[0].equals(name));
        saveCustom(items);
    }

    private void saveCustom(java.util.List<String[]> items) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (String[] p : items) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("n", p[0]);
                o.put("c", p[1]);
                arr.put(o);
            }
        } catch (Throwable ignored) { }
        sp.edit().putString("customCommands", arr.toString()).apply();
    }

    /**
     * The overlay position is deliberately NOT persisted.
     *
     * The brief asks that the bar always appear at the top centre, and only stop
     * following that default once the user has moved it - within that run. So a
     * fresh appearance resets to top centre, and there is nothing to store.
     */
}