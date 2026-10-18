// language: Java, file: Shell.java, target: Android 16 (API 36)
// One-shot shell commands.
//
// Everything the toolbox does - listing packages, reading battery stats, freezing
// an app - is a single shell command whose output is read once. The transport for
// that already exists in Adb.shell, so this is only a thin front door.
//
// Adb's read timeout (15 s) is the ceiling here. A command that outlives it is cut
// off, so anything long-running streams through Adb.open instead. Callers run
// run() off the main thread themselves; every screen here already does.
package com.aditiyaalif.pulse;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

final class Shell {
    private Shell() { }

    /**
     * Make sure there is a link before a screen asks for data.
     *
     * The home screen keeps its own sampler alive, but a module screen opened on
     * its own may find the transport down - and then every command returns null
     * and the screen reads "--" for no visible reason. Reconnecting here means a
     * module works whenever the device has been paired, not only when something
     * else happened to be sampling. Blocking: call it on the worker thread.
     */
    static void ensure(Context ctx) {
        if (Adb.connected()) return;
        Wireless.autoConnect(ctx);
    }

    /** Run a one-shot command. Returns the output, or null when there is no link. */
    static String run(String cmd) {
        return Adb.shell(cmd);
    }

    /**
     * Run several commands in ONE round trip and split the output by marker.
     *
     * A screen that reads eight dumpsys blocks would otherwise open eight sockets
     * one after another, and the slow ones (~1.9 s for batterystats) add up to
     * several seconds of empty screen. Batched, it is one round trip and the whole
     * set arrives together. Returns an empty map when the link is down, so callers
     * can tell "not connected" from "empty".
     */
    static Map<String, String> batched(LinkedHashMap<String, String> cmds) {
        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cmds.entrySet()) {
            sb.append("echo \"@@").append(e.getKey()).append("@@\"\n");
            sb.append(e.getValue()).append('\n');
        }
        String raw = Adb.shell(sb.toString());
        if (raw == null) return out;
        String key = null;
        StringBuilder buf = null;
        for (String line : raw.split("\n", -1)) {
            if (line.startsWith("@@") && line.endsWith("@@") && line.length() > 4) {
                if (key != null) out.put(key, buf.toString());
                key = line.substring(2, line.length() - 2);
                buf = new StringBuilder();
            } else if (buf != null) {
                buf.append(line).append('\n');
            }
        }
        if (key != null) out.put(key, buf.toString());
        return out;
    }
}

