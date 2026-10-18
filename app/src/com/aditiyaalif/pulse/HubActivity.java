// language: Java, file: HubActivity.java, target: Android 16 (API 36)
// Tools. The second welcome, and the way into every module.
//
// A grid of tiles rather than a list: seven modules as a list is a long scroll of
// near-identical rows, which is the shape the brief rules out. As a two-column
// grid the whole thing fits on one screen and reads as a set of destinations.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class HubActivity extends Screen {

    /** One module: its icon, its name, the screen it opens, and its accent. */
    private static final class Module {
        final String icon, name, desc;
        final Class<?> target;
        final int color;
        Module(String icon, String name, String desc, int color, Class<?> target) {
            this.icon = icon; this.name = name; this.desc = desc;
            this.color = color; this.target = target;
        }
    }

    private static final Module[] MODULES = {
        new Module(Icon.GRID,     "Apps",        "Freeze, stop, uninstall",   Theme.SERIES[0], AppsActivity.class),
        new Module(Icon.BATTERY,  "Battery",     "Drain, doze, charging",     Theme.SERIES[2], BatteryActivity.class),
        new Module(Icon.LAYERS,   "Processes",   "Running apps and threads",  Theme.SERIES[1], ProcessesActivity.class),
        new Module(Icon.TERMINAL, "Commands",    "One-tap shell actions",     Theme.SERIES[6], CommandsActivity.class),
        new Module(Icon.PULSE,    "Diagnostics", "Logs and bug reports",      Theme.SERIES[3], DiagnosticsActivity.class),
        new Module(Icon.DEVICE,   "System",      "Apps, storage, sensors",    Theme.SERIES[7], SystemActivity.class),
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = scaffold();
        addBackBar("Tools");
        addHeading("What would you like to do?", "Everything Pulse can reach, grouped by job.");

        // Two tiles to a row, each a weighted half. Recordings is NOT here: it
        // already has its own button in the home header.
        //
        // The row is built from a weighted slot plus a fixed gap plus a weighted
        // slot, and an empty row is filled with a blank weighted slot. An earlier
        // version added the tiles themselves with fixed widths, which made the odd
        // last tile span the whole row and sit out of line with the grid.
        LinearLayout grid = column();
        for (int i = 0; i < MODULES.length; i += 2) {
            LinearLayout r = row();
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) rlp.topMargin = Ui.dp(this, 12);
            r.setLayoutParams(rlp);
            r.addView(tile(MODULES[i]));
            r.addView(tileSpacer());
            if (i + 1 < MODULES.length) {
                r.addView(tile(MODULES[i + 1]));
            } else {
                // An empty half keeps the lone tile the same width as every other.
                r.addView(new View(this), new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(r);
        }
        col.addView(grid);
    }

    private View tileSpacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 12), 1));
        return v;
    }

    private View tile(Module m) {
        LinearLayout t = column();
        t.setBackground(Ui.roundStroke(Theme.CARD, Ui.dp(this, 20),
                Theme.HAIRLINE, Ui.dp(this, 1)));
        Ui.pad(t, this, 15, 15, 15, 15);
        // Every tile is the same width (a weighted half) and the description is
        // kept to one line, so every tile ends up the same height too. A wrapping
        // description made one tile taller than its neighbours, which is what made
        // the grid look uneven - especially the lone tile in the last row.
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.round(Theme.glow(m.color, 0x22), Ui.dp(this, 14)));
        badge.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        Icon ic = new Icon(this, m.icon, m.color);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                Ui.dp(this, 19), Ui.dp(this, 19));
        ilp.gravity = Gravity.CENTER;
        badge.addView(ic, ilp);
        t.addView(badge);

        TextView name = text(m.name, 15, Theme.TEXT, Typeface.BOLD);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = Ui.dp(this, 10);
        t.addView(name, nlp);

        TextView desc = text(m.desc, 11, Theme.MUTED, Typeface.NORMAL);
        desc.setSingleLine(true);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(desc);

        t.setOnClickListener(v -> {
            Anim.bounce(v);
            open(m.target);
        });
        return t;
    }
}
