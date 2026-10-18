// language: Java, file: DiagnosticsActivity.java, target: Android 16 (API 36)
// Diagnostics: the logs.
//
// A log dump is far larger than the 15-second ceiling a one-shot command allows,
// so it is tailed by level and filtered on the device - only the lines that matter
// cross the link. The screen loads on open, so it is never a blank page waiting
// for a tap.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class DiagnosticsActivity extends Screen {
    private TextView output;
    private EditText filter;
    private LinearLayout chips;
    private String level = "E";   // errors by default - the useful noise floor

    private static final String[] LEVELS = {"E", "W", "I", "*"};
    private static final String[] LEVEL_NAMES = {"Errors", "Warnings", "Info", "All"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = scaffold();
        addBackBar("Diagnostics");
        addHeading("Diagnostics", "Read the system log.");

        LinearLayout levels = card("Log level");
        chips = row();
        levels.addView(chips);
        col.addView(levels);

        LinearLayout ctl = card(null);
        filter = new EditText(this);
        filter.setHint("Filter by tag or text (optional)");
        filter.setHintTextColor(Theme.MUTED);
        filter.setTextColor(Theme.TEXT);
        filter.setTextSize(14);
        filter.setSingleLine(true);
        filter.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        filter.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        ctl.addView(filter);

        LinearLayout cr = row();
        LinearLayout.LayoutParams crlp = wrap();
        crlp.topMargin = Ui.dp(this, 12);
        cr.setLayoutParams(crlp);
        cr.addView(button("Reload", v -> {
            Anim.bounce(v);
            load();
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = Ui.dp(this, 10);
        cr.addView(button("Clear buffer", v -> {
            Anim.bounce(v);
            confirm("Clear the log buffer?", "Everything logged so far will be erased. "
                    + "Useful for starting a clean capture of a problem.",
                    "Clear", Theme.CRITICAL, () -> new Thread(() -> {
                        Shell.run("logcat -c");
                        main.post(() -> { output.setText(""); toast("Buffer cleared"); });
                    }, "pulse-logclear").start());
        }), blp);
        ctl.addView(cr);
        col.addView(ctl);

        LinearLayout out = card("Output");
        ScrollView scroll = new ScrollView(this);
        output = text("Loading…", 11, Theme.TEXT_DIM, Typeface.NORMAL);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setLineSpacing(Ui.dp(this, 2), 1f);
        scroll.addView(output);
        out.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 340)));
        col.addView(out);

        rebuildChips();
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        // A reload on return keeps the view fresh; the first load already ran in
        // onCreate, so this is not the only thing standing between the user and
        // an empty page.
        load();
    }

    private View gapH() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        return v;
    }

    /**
     * Rebuild the level chips from the current selection.
     *
     * They are rebuilt rather than just recoloured: a chip's background is set
     * when it is created, so a chip built once keeps the colour it was born with -
     * which is why the highlight never moved off Errors however often another
     * chip was tapped.
     */
    private void rebuildChips() {
        chips.removeAllViews();
        for (int i = 0; i < LEVELS.length; i++) {
            final String lvl = LEVELS[i];
            boolean on = lvl.equals(level);
            TextView t = pill(LEVEL_NAMES[i], on ? Theme.ACCENT : Theme.CARD_HI,
                    on ? 0xFFFFFFFF : Theme.TEXT_DIM, v -> {
                        Anim.bounce(v);
                        if (lvl.equals(level)) return;
                        level = lvl;
                        rebuildChips();
                        load();
                    });
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            chips.addView(t);
            if (i < LEVELS.length - 1) chips.addView(gapH());
        }
    }

    private void load() {
        output.setAlpha(0.4f);
        output.setText("Loading…");
        final String lvl = level;
        final String q = filter.getText().toString().trim();
        new Thread(() -> {
            // Ensure the link first: without it every command returns null and the
            // page reads "No matching lines" for a reason the user cannot see.
            Shell.ensure(this);
            String cmd = "logcat -d -t 400 " + lvl;
            if (!q.isEmpty()) cmd += " | grep -i -- \"" + q.replace("\"", "") + "\"";
            String out = Shell.run(cmd);
            main.post(() -> {
                if (out == null) output.setText("Not connected to the device.");
                else if (out.trim().isEmpty()) output.setText("No matching lines.");
                else output.setText(out.trim());
                output.animate().alpha(1f).setDuration(200).start();
            });
        }, "pulse-log").start();
    }
}
