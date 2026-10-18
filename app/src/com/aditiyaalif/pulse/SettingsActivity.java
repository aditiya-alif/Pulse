// language: Java, file: SettingsActivity.java, target: Android 16 (API 36)
// Connection and overlay settings.
//
// These were the body of the old single screen. They are here rather than on home
// because they are set once and then left alone, whereas home is the screen the
// user opens to see a reading. Pairing, the bar's contents and the reconnect
// controls are the things that are only visited when something needs changing.
package com.aditiyaalif.pulse;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SettingsActivity extends Screen {
    private Prefs prefs;
    private TextView statusText;
    private TextView modeStatus, modeButton;
    private LinearLayout metricRow;
    private BarView barPreview;
    private boolean gpuAvailable;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        LinearLayout col = scaffold();
        addBackBar("Settings");
        addHeading("Settings", "Connection and overlay.");

        buildWirelessCard(col);
        buildConnectionModeCard(col);
        buildBarCard(col);

        // The GPU switch is only meaningful where a readable node exists.
        new Thread(() -> {
            boolean gpu = Wireless.gpuReadable();
            main.post(() -> { gpuAvailable = gpu; rebuildMetricRows(); });
        }, "pulse-gpu").start();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus(null);
        syncMode();
    }

    // ------------------------------------------------------------ wireless

    private void buildWirelessCard(LinearLayout col) {
        LinearLayout card = card("Wireless debugging");
        col.addView(card);

        statusText = text("Checking…", 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
        card.addView(statusText);

        card.addView(divider());

        card.addView(text("Pairing code", 14, Theme.TEXT, Typeface.NORMAL));
        TextView hint = text("Turn on \"Wireless debugging\" in Settings > Developer "
                + "options, open \"Pair device with pairing code\", then enter the "
                + "6 digits shown. The IP and port are found automatically.", 11.5f,
                Theme.MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams hlp = wrap();
        hlp.bottomMargin = Ui.dp(this, 10);
        card.addView(hint, hlp);

        LinearLayout inputRow = row();

        EditText code = new EditText(this);
        code.setHint("000000");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextColor(Theme.TEXT);
        code.setHintTextColor(Theme.MUTED);
        code.setTextSize(18);
        code.setLetterSpacing(0.3f);
        code.setGravity(Gravity.CENTER);
        code.setBackground(Ui.roundStroke(Theme.CARD_HI, Ui.dp(this, 14),
                Theme.HAIRLINE, Ui.dp(this, 1)));
        code.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        inputRow.addView(code, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView pair = text("Pairing", 13.5f, 0xFFFFFFFF, Typeface.BOLD);
        pair.setGravity(Gravity.CENTER);
        pair.setBackground(Ui.round(Theme.ACCENT, Ui.dp(this, 14)));
        pair.setPadding(Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = Ui.dp(this, 10);
        pair.setOnClickListener(v -> {
            String c = code.getText().toString().trim();
            if (c.length() < 6) { toast("Enter the 6-digit pairing code"); return; }
            Anim.bounce(v);
            pair.setEnabled(false);
            pair.setText("…");
            statusText.setText("Finding pairing port…");
            new Thread(() -> {
                Wireless.Result r = Wireless.pair(this, c);
                main.post(() -> {
                    pair.setEnabled(true);
                    pair.setText("Pairing");
                    if (r.ok) {
                        code.setText("");
                        statusText.setText("Paired. Connecting…");
                        new Thread(() -> {
                            Wireless.Result cr = Wireless.connect(this);
                            main.post(() -> refreshStatus(cr.ok ? "Connected" : null));
                        }, "pulse-connect").start();
                    } else {
                        statusText.setText(r.output);
                        toast("Pairing failed");
                    }
                });
            }, "pulse-pair").start();
        });
        inputRow.addView(pair, plp);
        card.addView(inputRow);

        card.addView(divider());

        LinearLayout actions = row();
        TextView connect = button("Reconnect", v -> {
            Anim.bounce(v);
            statusText.setText("Connecting…");
            new Thread(() -> {
                Wireless.Result r = Wireless.connect(this);
                // Nudge the sampler too: it stops retrying after a few failures, so
                // a successful connect here would otherwise leave the overlay still
                // showing "Disconnected". This is the deliberate "try again".
                if (r.ok && OverlayService.RUNNING) {
                    startService(new Intent(this, OverlayService.class)
                            .setAction(OverlayService.ACTION_RECONNECT));
                }
                main.post(() -> refreshStatus(r.ok ? "Connected" : r.output));
            }, "pulse-reconnect").start();
        });
        actions.addView(connect, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView reset = button("Reset pairing", v -> {
            Anim.bounce(v);
            new Thread(() -> {
                Wireless.resetPairing(this);
                main.post(() -> { refreshStatus(null); toast("Pairing reset"); });
            }, "pulse-reset").start();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = Ui.dp(this, 10);
        actions.addView(reset, rlp);
        card.addView(actions);
    }

    // ------------------------------------------------------------------ bar

    // ------------------------------------------------------- connection mode

    /**
     * The connection-mode card: wireless debugging, or loopback.
     *
     * LOOPBACK, IN PLAIN WORDS. Normally Pulse reaches the phone's adb daemon over
     * the wireless-debugging port, which lives on the Wi-Fi interface - so Wi-Fi
     * has to stay on. Loopback instead points the app at 127.0.0.1, the phone's own
     * internal network, after asking adbd to listen on a fixed port there. Nothing
     * leaves the device, so Wi-Fi can be off, mobile data alone is enough, and it
     * works in airplane mode.
     *
     * WHAT IT COSTS. The port is not private - adbd binds it to every network
     * interface, exactly as it does for wireless debugging (see the note in the
     * card). And it does not survive a reboot: the port is runtime state, and
     * making it permanent needs root. So this is a mode you turn on when you want
     * it, not a setting that replaces pairing.
     */
    private void buildConnectionModeCard(LinearLayout col) {
        LinearLayout card = card("Connection mode");

        modeStatus = text("", 12.5f, Theme.TEXT_DIM, Typeface.NORMAL);
        card.addView(modeStatus);

        card.addView(divider());
        card.addView(groupLabel("What loopback is"));
        card.addView(para("Wireless debugging is reached over Wi-Fi, so Wi-Fi must "
                + "stay on. Loopback instead connects Pulse to the phone's own "
                + "internal address (127.0.0.1) - nothing leaves the device - so it "
                + "keeps working with Wi-Fi off, on mobile data only, or in airplane "
                + "mode."));
        card.addView(para("The catch: it lasts until the next restart. After a reboot "
                + "you are back on wireless debugging, and turning loopback on again "
                + "takes one tap while a connection exists."));

        // The security note, with its source, applies to BOTH modes - it is not a
        // loopback-only caveat, and saying so would be misleading.
        card.addView(divider());
        card.addView(groupLabel("About the port"));
        card.addView(para("While either mode is active, the phone's adb daemon "
                + "listens on every network interface, not just the internal one - so "
                + "the port is visible to the local network. This is how Android "
                + "itself behaves for wireless debugging too, and it is a known "
                + "concern: Google issue 526109803 proposes limiting adbd to a single "
                + "interface for exactly this reason, after CVE-2026-0073 (a wireless "
                + "adb pairing bypass). Connections still require an authorised key, "
                + "so visibility is not the same as access."));
        card.addView(para("Source: AOSP, \"Architecture of ADB Wifi\" - "
                + "\"adbd binds its listening sockets to all available network "
                + "interfaces\" - and Google IssueTracker 526109803."));

        card.addView(divider());
        modeButton = pill("", Theme.CARD_HI, Theme.TEXT, v -> {
            Anim.bounce(v);
            toggleMode();
        });
        // Locked to its widest label: it alternates between "Switch to loopback" and
        // "Switch to wireless debugging", and "Switching to loopback…" while busy.
        lockWidthToLongest(modeButton,
                "Switch to loopback", "Switch to wireless debugging",
                "Switching to loopback…", "Restoring wireless…");
        LinearLayout.LayoutParams mlp = wrap();
        mlp.topMargin = Ui.dp(this, 4);
        card.addView(modeButton, mlp);

        col.addView(card);
    }

    /** A wrapping paragraph for the explanatory text. */
    private TextView para(String s) {
        TextView t = text(s, 11.5f, Theme.MUTED, Typeface.NORMAL);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams lp = wrap();
        lp.bottomMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /** Paint the mode card from the stored mode and the live link state. */
    private void syncMode() {
        if (modeStatus == null) return;
        boolean loop = Prefs.LOOPBACK.equals(prefs.connMode());
        if (loop) {
            modeStatus.setText("Loopback - Wi-Fi not needed");
            modeStatus.setTextColor(Theme.ACCENT);
            modeButton.setText("Switch to wireless debugging");
        } else {
            modeStatus.setText("Wireless debugging - needs Wi-Fi");
            modeStatus.setTextColor(Theme.TEXT_DIM);
            modeButton.setText("Switch to loopback");
        }
    }

    /**
     * Flip the mode.
     *
     * Turning loopback ON is refused unless a link already exists - the command to
     * arm it travels over that link, so there is nothing to arm from otherwise.
     * Turning it OFF runs `adb usb`, after which the system brings wireless
     * debugging back on its own and the app reconnects to it.
     */
    private void toggleMode() {
        final boolean toLoop = !Prefs.LOOPBACK.equals(prefs.connMode());
        modeButton.setEnabled(false);
        modeStatus.setText(toLoop ? "Switching to loopback…" : "Restoring wireless…");
        new Thread(() -> {
            Wireless.Result r = toLoop ? Wireless.useLoopback(this) : Wireless.useWireless(this);
            // The switch restarts adbd, which drops the link - so tell the sampler
            // to pick it back up once the switch is done, instead of leaving it
            // parked on "Disconnected".
            if (r.ok && OverlayService.RUNNING) {
                startService(new Intent(this, OverlayService.class)
                        .setAction(OverlayService.ACTION_RECONNECT));
            }
            main.post(() -> {
                modeButton.setEnabled(true);
                toast(r.ok ? r.output : ("Failed: " + r.output));
                syncMode();
                refreshStatus(null);
            });
        }, "pulse-mode").start();
    }

    private void buildBarCard(LinearLayout col) {
        LinearLayout card = card("Bar contents");
        col.addView(card);

        barPreview = new BarView(this);
        LinearLayout.LayoutParams plp = wrap();
        plp.bottomMargin = Ui.dp(this, 16);
        card.addView(barPreview, plp);
        Sample demo = new Sample();
        demo.valid = true;
        demo.cpuPct = 42f;
        demo.memUsedKb = 7L * 1048576;
        demo.memTotalKb = 12L * 1048576;
        demo.cpuTemp = 48f;
        demo.batTemp = 37f;
        demo.fps = 60;
        demo.watts = -3.2f;
        demo.level = 78;
        barPreview.setSample(demo);

        metricRow = column();
        card.addView(metricRow);
        rebuildMetricRows();
    }

    private void rebuildMetricRows() {
        metricRow.removeAllViews();
        Set<String> on = prefs.barItems();
        List<Bar.Item> shown = new ArrayList<>();
        for (Bar.Item it : Bar.ALL) {
            if (it.id.equals("gpu") && !gpuAvailable) continue;
            shown.add(it);
        }

        for (int i = 0; i < shown.size(); i++) {
            Bar.Item it = shown.get(i);
            LinearLayout row = row();
            row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));

            TextView name = text(metricDisplay(it), 14, Theme.TEXT, Typeface.NORMAL);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Toggle sw = new Toggle(this);
            sw.setOn(on.contains(it.id), false);
            sw.setOnChange(() -> {
                Set<String> cur = new LinkedHashSet<>(prefs.barItems());
                if (sw.isOn()) {
                    cur.add(it.id);
                } else {
                    if (cur.size() == 1) {
                        toast("Keep at least one item");
                        sw.setOn(true, true);
                        return;
                    }
                    cur.remove(it.id);
                }
                prefs.setBarItems(cur);
                updatePreview();
                if (OverlayService.RUNNING) restartOverlay();
            });
            row.addView(sw);
            metricRow.addView(row);

            if (i < shown.size() - 1) {
                View line = new View(this);
                line.setBackgroundColor(0x0DFFFFFF);
                metricRow.addView(line, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
            }
        }
        updatePreview();
    }

    private static String metricDisplay(Bar.Item it) {
        if (it.id.equals("temp")) return "CPU °C + BAT °C";
        return it.label;
    }

    private void updatePreview() {
        if (barPreview == null) return;
        barPreview.setItems(new ArrayList<>(prefs.barItems()));
        barPreview.setApp(null, "");
    }

    private void restartOverlay() {
        stopService(new android.content.Intent(this, OverlayService.class));
        main.postDelayed(() -> {
            android.content.Intent i = new android.content.Intent(this, OverlayService.class)
                    .setAction(OverlayService.ACTION_SHOW);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                startForegroundService(i);
            else startService(i);
        }, 350);
    }

    private void refreshStatus(String forced) {
        new Thread(() -> {
            boolean loop = Prefs.LOOPBACK.equals(prefs.connMode());
            String s;
            if (forced != null && !forced.equals("Connected")) {
                s = forced;
            } else if (!Wireless.bundled(this)) {
                s = "Bundled adb not found in the APK";
            } else if (Wireless.connected(this)) {
                // Say WHICH transport is live, not just the address. In loopback the
                // address is adb's internal name for port 5555 ("emulator-5554"),
                // which reads as a second, mystery device if it is shown bare.
                String addr = Adb.address();
                s = loop
                        ? "Connected over loopback" + (addr == null ? "" : " · " + addr)
                        : "Connected over wireless" + (addr == null ? "" : " · " + addr);
            } else if (prefs.paired()) {
                s = loop ? "Loopback mode · not connected"
                         : "Paired, not connected";
            } else {
                s = "Not paired";
            }
            final String out = s;
            main.post(() -> { if (statusText != null) statusText.setText(out); });
        }, "pulse-status").start();
    }
}
