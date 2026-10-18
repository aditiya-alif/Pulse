// language: Java, file: AppOps.java, target: Android 16 (API 36)
// Per-app permissions and background limits, over `cmd appops`.
//
// The appops database is what actually decides whether an app may use the camera,
// the location, or run in the background - the manifest permission is only half
// the story, and this is the half a user can change without root. Reading it is
// `cmd appops get`, writing is `cmd appops set ... allow|ignore`.
//
// Only a short list of ops is exposed. There are hundreds, most are meaningless
// to a person, and offering them all would be a wall of jargon rather than a tool.
package com.adit.pulse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppOps {
    /** One switchable op: its id, and the words to show for it. */
    static final class Op {
        final String id, label, desc;
        Op(String id, String label, String desc) {
            this.id = id; this.label = label; this.desc = desc;
        }
    }

    /**
     * The ops worth offering. Each is a real, commonly-misused permission or
     * background privilege, and each reads back as allow/ignore/ask/default.
     */
    static final Op[] OPS = {
        new Op("RUN_ANY_IN_BACKGROUND", "Run in background",
                "Whether the app may keep running when it is not on screen."),
        new Op("CAMERA", "Camera", "Whether the app may open the camera."),
        new Op("RECORD_AUDIO", "Microphone", "Whether the app may record audio."),
        new Op("COARSE_LOCATION", "Location (coarse)", "Approximate location."),
        new Op("FINE_LOCATION", "Location (precise)", "Exact GPS location."),
        new Op("READ_CONTACTS", "Contacts", "Read the contact list."),
        new Op("POST_NOTIFICATION", "Notifications", "Post notifications."),
        new Op("WAKE_LOCK", "Keep awake", "Hold the device awake."),
        new Op("VIBRATE", "Vibrate", "Use the vibrator."),
        new Op("SYSTEM_ALERT_WINDOW", "Draw over apps", "Show windows over other apps."),
    };

    private AppOps() { }

    /** The current mode of every op we track, as {opId -> "allow"/"ignore"/...}. */
    static List<String[]> read(String pkg) {
        List<String[]> out = new ArrayList<>();
        String raw = Shell.run("cmd appops get " + pkg);
        if (raw == null) return out;
        for (Op op : OPS) {
            // Lines look like "FINE_LOCATION: ignore" or "RUN_ANY_IN_BACKGROUND: allow".
            Matcher m = Pattern.compile("^\\s*" + op.id + ":\\s*(\\S+)",
                    Pattern.MULTILINE).matcher(raw);
            String mode = m.find() ? m.group(1) : "default";
            out.add(new String[]{ op.id, op.label, mode });
        }
        return out;
    }

    /** Set one op. mode is "allow", "ignore" or "default". */
    static void set(String pkg, String op, String mode) {
        Shell.run("cmd appops set " + pkg + " " + op + " " + mode);
    }

    /** True when the mode means the app is denied. */
    static boolean denied(String mode) {
        return "ignore".equals(mode) || "deny".equals(mode) || "errored".equals(mode);
    }

    /** The uid of a package, or -1. Needed for the netpolicy commands. */
    static int uid(String pkg) {
        String out = Shell.run("pm list packages -U " + pkg);
        if (out == null) return -1;
        Matcher m = Pattern.compile("uid:(\\d+)").matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // --------------------------------------------------------------- netpolicy

    /** True when the app is on the background-network blacklist. */
    static boolean networkRestricted(int uid) {
        String raw = Shell.run("cmd netpolicy list restrict-background-blacklist");
        if (raw == null || uid <= 0) return false;
        return Pattern.compile("\\b" + uid + "\\b").matcher(raw).find();
    }

    /** Restrict or unrestrict the app's background network. */
    static void setNetworkRestricted(int uid, boolean restricted) {
        if (uid <= 0) return;
        Shell.run("cmd netpolicy " + (restricted ? "add" : "remove")
                + " restrict-background-blacklist " + uid);
    }

    // ------------------------------------------------------------ standby bucket

    /**
     * The app's standby bucket, as its number (5 active, 10 working set, 20
     * frequent, 30 rare, 40 restricted) - or -1 when unreadable.
     */
    static int standbyBucket(String pkg) {
        String raw = Shell.run("am get-standby-bucket " + pkg);
        if (raw == null) return -1;
        Matcher m = Pattern.compile("(\\d+)").matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Set the standby bucket by name: active, working-set, frequent, rare, restricted. */
    static void setStandbyBucket(String pkg, String bucket) {
        Shell.run("am set-standby-bucket " + pkg + " " + bucket);
    }

    static String bucketName(int bucket) {
        switch (bucket) {
            case 5: return "Active";
            case 10: return "Working set";
            case 20: return "Frequent";
            case 30: return "Rare";
            case 40: return "Restricted";
            default: return "Unknown";
        }
    }
}
