// language: Java, file: AppIcon.java, target: Android 16 (API 36)
// Resolve a package name into its icon and its human label, cached.
//
// QUERY_ALL_PACKAGES is declared in the manifest: the focused app's package name
// is known, but on API 30+ resolving it into a drawable is still filtered by
// package visibility, and without that permission the icon silently comes back
// null. It is the only reason that permission is here.
package com.adit.pulse;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

final class AppIcon {
    static final class Info {
        final Drawable icon;
        final String label;
        Info(Drawable icon, String label) { this.icon = icon; this.label = label; }
    }

    private static final Map<String, Info> CACHE = new HashMap<>();

    private AppIcon() { }

    /** The icon + label for a package, or a null icon when it cannot be resolved. */
    static Info get(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return new Info(null, "");
        synchronized (CACHE) {
            Info hit = CACHE.get(pkg);
            if (hit != null) return hit;
        }
        Info info = resolve(ctx, pkg);
        synchronized (CACHE) { CACHE.put(pkg, info); }
        return info;
    }

    private static Info resolve(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Drawable icon = pm.getApplicationIcon(ai);
            CharSequence label = pm.getApplicationLabel(ai);
            return new Info(icon, label == null ? pkg : label.toString());
        } catch (Throwable t) {
            // A system surface (or a package we cannot see) has no icon; the
            // caller falls back to the label or to nothing at all.
            String tail = pkg;
            int dot = pkg.lastIndexOf('.');
            if (dot >= 0 && dot < pkg.length() - 1) tail = pkg.substring(dot + 1);
            return new Info(null, tail);
        }
    }
}
