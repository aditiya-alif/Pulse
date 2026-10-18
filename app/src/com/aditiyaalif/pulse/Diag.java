// language: Java, file: Diag.java, target: Android 16 (API 36)
// Small append-only log. Used by the adb layer's failure paths, where "it did not
// connect" is otherwise indistinguishable between a dozen causes.
//
// WHERE IT GOES, AND WHY. The app's own files dir (/data/data/<pkg>/files) is
// private: reading it back needs root, so the log was useless for diagnosing a
// device problem from outside. It is written to the app's EXTERNAL files dir
// instead (/sdcard/Android/data/com.aditiyaalif.pulse/files), which adb shell and Termux
// can read without root. If external storage is not mounted, it falls back to the
// internal dir so logging never breaks.
package com.aditiyaalif.pulse;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Diag {
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static File file;
    private static final Object LOCK = new Object();

    private Diag() {}

    static void init(Context c) {
        synchronized (LOCK) {
            if (file != null) return;
            File dir = null;
            try {
                // getExternalFilesDir is readable by adb/other apps on the same
                // user, unlike the private internal dir.
                dir = c.getExternalFilesDir(null);
            } catch (Throwable ignored) { }
            if (dir == null) dir = c.getFilesDir();
            file = new File(dir, "pulse.log");
        }
    }

    static void log(String msg) {
        synchronized (LOCK) {
            if (file == null) return;
            try (FileWriter w = new FileWriter(file, true)) {
                w.write(TS.format(new Date()) + "  " + msg + "\n");
            } catch (Throwable ignored) { }
        }
    }

    static void log(String msg, Throwable t) {
        log(msg + " :: " + (t == null ? "null"
                : t.getClass().getSimpleName() + ": " + t.getMessage()));
    }
}
