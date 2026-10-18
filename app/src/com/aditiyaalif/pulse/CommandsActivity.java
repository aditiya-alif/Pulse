// language: Java, file: CommandsActivity.java, target: Android 16 (API 36)
// One-tap shell actions.
//
// ORGANISED AS SWITCHES AND ACTIONS, NOT AS A LIST OF COMMANDS.
//
// The first version listed both halves of every switch - "Wi-Fi on" and "Wi-Fi
// off" as separate rows - which is twelve rows of noise for six controls, and left
// the user to work out which one was currently true. A radio is a state: it is a
// SWITCH that shows where it is now and flips when tapped. Only the things that
// are genuinely one-shot (trim, clear caches, reboot) stay as buttons.
//
// The state is read back from the device rather than remembered, so the switch
// reflects reality even if the user changed Wi-Fi from the notification shade.
package com.aditiyaalif.pulse;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CommandsActivity extends Screen {
    private Prefs prefs;
    private LinearLayout customHost;
    private LinearLayout radioHost;

    /** A radio the app can switch: its label, how to read it, and how to set it. */
    private static final class Radio {
        final String label, readCmd, onCmd, offCmd;
        Radio(String label, String readCmd, String onCmd, String offCmd) {
            this.label = label; this.readCmd = readCmd;
            this.onCmd = onCmd; this.offCmd = offCmd;
        }
    }

    private static final Radio[] RADIOS = {
        new Radio("Wi-Fi", "settings get global wifi_on", "svc wifi enable", "svc wifi disable"),
        new Radio("Mobile data", "settings get global mobile_data",
                "svc data enable", "svc data disable"),
        new Radio("Bluetooth", "settings get global bluetooth_on",
                "svc bluetooth enable", "svc bluetooth disable"),
        new Radio("Airplane mode", "settings get global airplane_mode_on",
                "settings put global airplane_mode_on 1; am broadcast "
                        + "-a android.intent.action.AIRPLANE_MODE --ez state true",
                "settings put global airplane_mode_on 0; am broadcast "
                        + "-a android.intent.action.AIRPLANE_MODE --ez state false"),
    };

    /** A one-shot action: label, command, and whether it needs confirming. */
    private static final class Action {
        final String label, desc, cmd;
        final boolean confirm;
        final String warn;
        Action(String label, String desc, String cmd, boolean confirm, String warn) {
            this.label = label; this.desc = desc; this.cmd = cmd;
            this.confirm = confirm; this.warn = warn;
        }
    }

    private static final Action[] MAINTENANCE = {
        new Action("Trim storage", "Tell the storage to tidy itself. Harmless, and "
                + "the system does it on its own anyway.", "sm fstrim", false, null),
        new Action("Clear app caches", "Frees space by emptying cached files. Apps "
                + "rebuild them as needed.", "pm trim-caches 99999999999", false, null),
        new Action("Drop RAM cache", "Asks the kernel to release cached memory. "
                + "The system refills it; this is mostly cosmetic.", "sync; echo 3 "
                + "> /proc/sys/vm/drop_caches", false, null),
        new Action("Kill background apps", "Stops every app running in the "
                + "background. Foreground apps are untouched.", "am kill-all", false, null),
    };

    private static final Action[] POWER = {
        new Action("Reboot", "The device restarts immediately.", "svc power reboot",
                true, "The device will restart immediately."),
        new Action("Power off", "The device shuts down immediately.",
                "svc power shutdown", true, "The device will shut down immediately."),
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        LinearLayout col = scaffold();
        addBackBar("Commands");
        addHeading("Commands", "Switches and one-tap actions.");

        // Radios: switches whose state is read from the device.
        LinearLayout radioCard = card("Radios");
        radioCard.addView(text("These show the current state and flip when tapped. "
                + "Some need a moment to apply.", 11.5f, Theme.MUTED, Typeface.NORMAL));
        radioHost = column();
        LinearLayout.LayoutParams rhlp = wrap();
        rhlp.topMargin = Ui.dp(this, 6);
        radioHost.setLayoutParams(rhlp);
        radioCard.addView(radioHost);
        col.addView(radioCard);
        buildRadios();

        col.addView(actionCard("Maintenance", MAINTENANCE));
        col.addView(actionCard("Power", POWER));

        // Custom commands.
        LinearLayout custom = card("Your commands");
        custom.addView(text("Add any shell command and run it from here.", 11.5f,
                Theme.MUTED, Typeface.NORMAL));

        EditText name = field("Name", 14, null);
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = Ui.dp(this, 12);
        custom.addView(name, nlp);

        EditText cmd = field("Command", 13, null);
        LinearLayout.LayoutParams clp = wrap();
        clp.topMargin = Ui.dp(this, 8);
        custom.addView(cmd, clp);

        TextView add = pill("Add", Theme.ACCENT, 0xFFFFFFFF, v -> {
            Anim.bounce(v);
            String n = name.getText().toString().trim();
            String c = cmd.getText().toString().trim();
            if (n.isEmpty() || c.isEmpty()) { toast("Enter a name and a command"); return; }
            prefs.addCustomCommand(n, c);
            name.setText("");
            cmd.setText("");
            renderCustom();
        });
        LinearLayout.LayoutParams alp = wrap();
        alp.topMargin = Ui.dp(this, 12);
        custom.addView(add, alp);

        customHost = column();
        LinearLayout.LayoutParams chlp = wrap();
        chlp.topMargin = Ui.dp(this, 8);
        customHost.setLayoutParams(chlp);
        custom.addView(customHost);
        col.addView(custom);

        renderCustom();
    }

    @Override protected void onResume() {
        super.onResume();
        // Read the real radio state every time the screen opens: the user may have
        // changed Wi-Fi or airplane mode from the shade while Pulse was away.
        readRadioStates();
    }

    /** A themed text field. */
    private EditText field(String hint, float size, String initial) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Theme.MUTED);
        e.setTextColor(Theme.TEXT);
        e.setTextSize(size);
        e.setSingleLine(true);
        if (initial != null) e.setText(initial);
        e.setBackground(Ui.round(Theme.CARD_HI, Ui.dp(this, 14)));
        e.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        return e;
    }

    // ---------------------------------------------------------------- radios

    /** Build the radio switches once; their state is filled in by readRadioStates. */
    private void buildRadios() {
        radioHost.removeAllViews();
        for (int i = 0; i < RADIOS.length; i++) {
            Radio r = RADIOS[i];
            LinearLayout row = row();
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

            LinearLayout mid = column();
            mid.addView(text(r.label, 14.5f, Theme.TEXT, Typeface.NORMAL));
            TextView state = text("Reading…", 11.5f, Theme.MUTED, Typeface.NORMAL);
            mid.addView(state);
            row.addView(mid, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Toggle sw = new Toggle(this);
            sw.setOnChange(() -> {
                boolean wantOn = sw.isOn();
                state.setText(wantOn ? "On" : "Off");
                state.setTextColor(wantOn ? Theme.ACCENT : Theme.MUTED);
                new Thread(() -> {
                    Shell.ensure(this);
                    Shell.run(wantOn ? r.onCmd : r.offCmd);
                    sleepQuiet(900);
                    String now = Shell.run(r.readCmd);
                    main.post(() -> applyRadioState(state, sw, now));
                }, "pulse-radio").start();
            });
            row.addView(sw);
            radioHost.addView(row);
            if (i < RADIOS.length - 1) radioHost.addView(divider());
        }
    }

    /**
     * Read every radio's state in ONE round trip, then set the switches.
     *
     * Batched because four separate `settings get` calls are four connections for
     * four tiny reads; the marker trick turns them into one.
     */
    private void readRadioStates() {
        if (radioHost.getChildCount() == 0) buildRadios();
        new Thread(() -> {
            Shell.ensure(this);
            LinkedHashMap<String, String> cmds = new LinkedHashMap<>();
            for (int i = 0; i < RADIOS.length; i++) cmds.put("r" + i, RADIOS[i].readCmd);
            Map<String, String> out = Shell.batched(cmds);
            main.post(() -> {
                for (int i = 0; i < RADIOS.length; i++) {
                    View rowV = radioHost.getChildAt(i * 2);   // rows and dividers interleave
                    if (!(rowV instanceof LinearLayout)) continue;
                    LinearLayout row = (LinearLayout) rowV;
                    if (row.getChildCount() < 2) continue;
                    TextView state = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
                    View last = row.getChildAt(row.getChildCount() - 1);
                    if (!(last instanceof Toggle)) continue;
                    applyRadioState(state, (Toggle) last, out.get("r" + i));
                }
            });
        }, "pulse-radios").start();
    }

    /**
     * Set a radio switch and its caption from a raw settings value.
     *
     * Wi-Fi reports 2 for on (not 1), so anything non-zero counts as on - the exact
     * value differs per radio and per build, and only "off" is consistent.
     */
    private void applyRadioState(TextView state, Toggle sw, String raw) {
        if (raw == null) { state.setText("Unknown"); return; }
        String v = raw.trim();
        if (v.isEmpty()) { state.setText("Unknown"); return; }
        long n;
        try { n = Long.parseLong(v); } catch (Throwable t) { state.setText("Unknown"); return; }
        boolean on = n != 0;
        sw.setOn(on, true);
        state.setText(on ? "On" : "Off");
        state.setTextColor(on ? Theme.ACCENT : Theme.MUTED);
    }

    // --------------------------------------------------------------- actions

    private View actionCard(String title, Action[] actions) {
        LinearLayout card = card(title);
        for (int i = 0; i < actions.length; i++) {
            card.addView(actionRow(actions[i]));
            if (i < actions.length - 1) card.addView(divider());
        }
        return card;
    }

    private View actionRow(Action a) {
        LinearLayout r = row();
        r.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

        LinearLayout mid = column();
        mid.addView(text(a.label, 14.5f, a.confirm ? Theme.CRITICAL : Theme.TEXT,
                Typeface.NORMAL));
        // The description is the point: a label like "Drop RAM cache" says nothing
        // about whether it is safe or useful.
        TextView d = text(a.desc, 11.5f, Theme.MUTED, Typeface.NORMAL);
        d.setLineSpacing(Ui.dp(this, 1), 1f);
        mid.addView(d);
        r.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView run = pill("Run", a.confirm ? Theme.CRITICAL : Theme.CARD_HI,
                a.confirm ? 0xFFFFFFFF : Theme.TEXT, v -> {
                    Anim.bounce(v);
                    if (a.confirm) {
                        confirm(a.label + "?", a.warn, a.label, Theme.CRITICAL,
                                () -> exec(a.cmd, a.label + " sent"));
                    } else {
                        exec(a.cmd, a.label + " done");
                    }
                });
        LinearLayout.LayoutParams rlp = wrap();
        rlp.leftMargin = Ui.dp(this, 10);
        r.addView(run, rlp);
        return r;
    }

    private void exec(String command, String done) {
        new Thread(() -> {
            Shell.ensure(this);
            Shell.run(command);
            main.post(() -> toast(done));
        }, "pulse-cmd").start();
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ------------------------------------------------------------ custom set

    private void renderCustom() {
        customHost.removeAllViews();
        List<String[]> items = prefs.customCommands();
        if (items.isEmpty()) {
            customHost.addView(text("No custom commands yet.", 11.5f, Theme.MUTED,
                    Typeface.NORMAL));
            return;
        }
        for (String[] pair : items) {
            final String nm = pair[0], cm = pair[1];
            LinearLayout r = row();
            r.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

            LinearLayout mid = column();
            mid.addView(text(nm, 14, Theme.TEXT, Typeface.BOLD));
            TextView code = text(cm, 11, Theme.MUTED, Typeface.NORMAL);
            code.setSingleLine(true);
            code.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(code);
            r.addView(mid, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            r.addView(pill("Run", Theme.CARD_HI, Theme.TEXT, v -> {
                Anim.bounce(v);
                exec(cm, nm + " sent");
            }));
            LinearLayout.LayoutParams dlp = wrap();
            dlp.leftMargin = Ui.dp(this, 8);
            TextView del = pill("✕", Theme.CARD_HI, Theme.MUTED, v -> {
                Anim.bounce(v);
                prefs.removeCustomCommand(nm);
                renderCustom();
            });
            r.addView(del, dlp);
            customHost.addView(r);
        }
    }
}
