// language: Java, file: Store.java, target: Android 16 (API 36)
// Recording persistence. One JSON file per capture in files/recordings/.
//
// A file per recording rather than one index file: a capture can hold thousands
// of samples, and rewriting a single growing index on every save would be O(n²)
// and could corrupt the whole history on one bad write. With a file each, a
// damaged recording costs only itself.
package com.aditiyaalif.pulse;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class Store {
    private final File dir;

    Store(Context c) {
        dir = new File(c.getFilesDir(), "recordings");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Diag.log("could not create recordings dir");
        }
    }

    /** Every saved recording, newest first. */
    List<Recording> list() {
        List<Recording> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                if (n <= 0) continue;
                Recording r = Recording.fromJson(
                        new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8)));
                if (r.id == null || r.id.isEmpty()) r.id = f.getName().replace(".json", "");
                out.add(r);
            } catch (Throwable t) {
                Diag.log("failed to read recording " + f.getName(), t);
            }
        }
        out.sort(Comparator.comparingLong((Recording r) -> r.startedAt).reversed());
        return out;
    }

    Recording get(String id) {
        for (Recording r : list()) if (id.equals(r.id)) return r;
        return null;
    }

    /** Save a recording. Returns the stored copy, or null on failure. */
    Recording save(Recording r) {
        if (r == null || r.samples.isEmpty()) return null;
        if (r.id == null || r.id.isEmpty()) {
            r.id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new java.util.Date(r.startedAt));
        }
        File f = new File(dir, r.id + ".json");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(r.toJson().toString().getBytes(StandardCharsets.UTF_8));
            Diag.log("saved recording " + r.id + " (" + r.samples.size() + " samples)");
            return r;
        } catch (Throwable t) {
            Diag.log("failed to save recording", t);
            return null;
        }
    }

    void delete(String id) {
        File f = new File(dir, id + ".json");
        if (f.isFile() && !f.delete()) Diag.log("could not delete " + f.getName());
    }

}
